-- Fase 2 — Chat cifrado (diseño C) y flujo de disputa.
-- Supabase/PostgreSQL 15+. Ejecutar una vez con el dueño de la migración,
-- después de 003_listings_auctions.sql.
--
-- Piezas:
--   1. profiles.chat_public_key: clave pública X25519 de chat por usuario.
--   2. conversation_keys: session_key envuelta por participante (E2E del hilo).
--   3. dispute_tickets + report_conversation(): "reportar" abre ticket y marca
--      el hilo como disputed; el proceso backend (con ambas mitades de
--      custodia) es el único que puede descifrarlo.
--   4. purge_expired_messages(): el servidor elimina el ciphertext vencido.
--   5. moderation_actions pasa a ser estrictamente append-only (logs de
--      descifrado inmutables: quién, cuándo, ticket, motivo).
--   6. user_notifications + notify_thread_decrypted(): aviso a ambas partes.
--   7. dispute_transcripts: transcripción descifrada, solo lectura de moderación.
BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Clave pública de chat del perfil (par X25519 generado en el dispositivo).
-- ---------------------------------------------------------------------------
ALTER TABLE public.profiles
    ADD COLUMN chat_public_key bytea;
GRANT UPDATE (chat_public_key) ON public.profiles TO authenticated;

-- ---------------------------------------------------------------------------
-- 2. Sobres de sesión por participante: cada uno desenvuelve la session_key
--    con su privada local; el servidor nunca la ve en claro.
-- ---------------------------------------------------------------------------
CREATE TABLE public.conversation_keys (
    conversation_id uuid NOT NULL REFERENCES public.conversations(id),
    user_id uuid NOT NULL REFERENCES public.users(id),
    wrapped_key bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);
ALTER TABLE public.conversation_keys ENABLE ROW LEVEL SECURITY;

CREATE POLICY conversation_keys_read ON public.conversation_keys FOR SELECT TO authenticated
    USING (user_id = (SELECT auth.uid()) OR public.is_participant(conversation_id));
-- Un participante solo puede depositar sobres para miembros de ese hilo; los
-- sobres existentes no se pueden modificar ni borrar desde el cliente.
CREATE POLICY conversation_keys_insert ON public.conversation_keys FOR INSERT TO authenticated
    WITH CHECK (
        public.is_participant(conversation_id)
        AND user_id IN (
            SELECT participant_a FROM public.conversations WHERE id = conversation_id
            UNION ALL
            SELECT participant_b FROM public.conversations WHERE id = conversation_id
        )
    );

-- ---------------------------------------------------------------------------
-- 3. Tickets de disputa. El cliente solo los crea vía report_conversation();
--    la resolución (aprobar/rechazar) corre a cargo de moderación.
-- ---------------------------------------------------------------------------
CREATE TABLE public.dispute_tickets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES public.conversations(id),
    opened_by uuid NOT NULL REFERENCES public.users(id),
    reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 1000),
    status text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'approved', 'decrypted', 'rejected')),
    approved_by uuid REFERENCES public.users(id),
    approved_at timestamptz,
    decrypted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((approved_by IS NULL) = (approved_at IS NULL))
);
ALTER TABLE public.dispute_tickets ENABLE ROW LEVEL SECURITY;

CREATE INDEX dispute_tickets_conversation_idx ON public.dispute_tickets(conversation_id, status);
CREATE INDEX dispute_tickets_status_idx ON public.dispute_tickets(status) WHERE status IN ('open', 'approved');
-- Un solo ticket vivo por conversación.
CREATE UNIQUE INDEX dispute_tickets_live_unique
    ON public.dispute_tickets(conversation_id) WHERE status IN ('open', 'approved');

-- Ambas partes (y moderación) ven los tickets del hilo: transparencia total.
CREATE POLICY dispute_tickets_read ON public.dispute_tickets FOR SELECT TO authenticated
    USING (public.is_participant(conversation_id) OR public.is_moderator());
CREATE POLICY dispute_tickets_moderate ON public.dispute_tickets FOR UPDATE TO authenticated
    USING (public.is_moderator()) WITH CHECK (public.is_moderator());

-- Reportar conversación: valida participante, crea el ticket (idempotente),
-- marca el hilo como disputed y deja constancia en la cola de moderación.
CREATE FUNCTION public.report_conversation(p_conversation_id uuid, p_reason text)
RETURNS public.dispute_tickets
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    ticket public.dispute_tickets%ROWTYPE;
BEGIN
    IF NOT public.is_participant(p_conversation_id) THEN
        RAISE EXCEPTION 'Only participants may report conversations' USING ERRCODE = '42501';
    END IF;
    IF p_reason IS NULL OR btrim(p_reason) = '' OR char_length(p_reason) > 1000 THEN
        RAISE EXCEPTION 'Invalid dispute reason' USING ERRCODE = '22023';
    END IF;

    SELECT * INTO ticket FROM public.dispute_tickets
    WHERE conversation_id = p_conversation_id AND status IN ('open', 'approved')
    LIMIT 1;
    IF ticket.id IS NOT NULL THEN
        RETURN ticket; -- Ya hay disputa viva: no se duplica.
    END IF;

    INSERT INTO public.dispute_tickets (conversation_id, opened_by, reason)
    VALUES (p_conversation_id, (SELECT auth.uid()), btrim(p_reason))
    RETURNING * INTO ticket;

    UPDATE public.conversations SET status = 'disputed'
    WHERE id = p_conversation_id AND status <> 'disputed';

    INSERT INTO public.reports (reporter_id, target_type, target_id, reason)
    VALUES ((SELECT auth.uid()), 'conversation', p_conversation_id, btrim(p_reason));

    RETURN ticket;
END;
$$;

-- Aprobación/rechazo del ticket por moderación (no descifra nada por sí solo).
CREATE FUNCTION public.resolve_dispute_ticket(p_ticket_id uuid, p_approve boolean)
RETURNS public.dispute_tickets
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    ticket public.dispute_tickets%ROWTYPE;
BEGIN
    IF NOT public.is_moderator() THEN
        RAISE EXCEPTION 'Only moderators may resolve dispute tickets' USING ERRCODE = '42501';
    END IF;
    UPDATE public.dispute_tickets
    SET status = CASE WHEN p_approve THEN 'approved' ELSE 'rejected' END,
        approved_by = CASE WHEN p_approve THEN (SELECT auth.uid()) ELSE NULL END,
        approved_at = CASE WHEN p_approve THEN now() ELSE NULL END
    WHERE id = p_ticket_id AND status = 'open'
    RETURNING * INTO ticket;
    IF ticket.id IS NULL THEN
        RAISE EXCEPTION 'Ticket not found or already resolved' USING ERRCODE = 'P0002';
    END IF;
    RETURN ticket;
END;
$$;

-- ---------------------------------------------------------------------------
-- 4. Autodestrucción: el servidor elimina el ciphertext (la fila) vencido.
--    Invocable solo por service_role (pg_cron / Edge scheduler).
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.purge_expired_messages() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    purged integer;
BEGIN
    DELETE FROM public.messages
    WHERE expires_at IS NOT NULL AND expires_at <= now();
    GET DIAGNOSTICS purged = ROW_COUNT;
    RETURN purged;
END;
$$;

-- ---------------------------------------------------------------------------
-- 5. Logs de descifrado (y de moderación en general) INMUTABLES.
--    Quién, cuándo, ticket y motivo quedan escritos para siempre.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.moderation_actions_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    RAISE EXCEPTION 'moderation_actions is append-only' USING ERRCODE = 'P0001';
END;
$$;

CREATE TRIGGER moderation_actions_immutable
BEFORE UPDATE OR DELETE ON public.moderation_actions
FOR EACH ROW EXECUTE FUNCTION public.moderation_actions_immutable();

-- ---------------------------------------------------------------------------
-- 6. Notificaciones a usuarios (p. ej. "tu hilo fue descifrado"). Solo el
--    servicio las crea; el usuario lee las suyas y las marca como leídas.
-- ---------------------------------------------------------------------------
CREATE TABLE public.user_notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES public.users(id),
    type text NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.user_notifications ENABLE ROW LEVEL SECURITY;

CREATE INDEX user_notifications_user_idx ON public.user_notifications(user_id, created_at DESC);

CREATE POLICY user_notifications_read ON public.user_notifications FOR SELECT TO authenticated
    USING (user_id = (SELECT auth.uid()));
CREATE POLICY user_notifications_mark_read ON public.user_notifications FOR UPDATE TO authenticated
    USING (user_id = (SELECT auth.uid())) WITH CHECK (user_id = (SELECT auth.uid()));

-- Avisa a AMBAS partes de que su hilo fue descifrado (lo llama el proceso
-- backend de disputa tras escribir el log en moderation_actions).
CREATE FUNCTION public.notify_thread_decrypted(p_conversation_id uuid, p_reason text)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.user_notifications (user_id, type, title, body)
    SELECT participant, 'thread_decrypted',
           'Tu hilo fue descifrado por un moderador',
           'Motivo: ' || p_reason
    FROM (
        SELECT participant_a AS participant FROM public.conversations WHERE id = p_conversation_id
        UNION
        SELECT participant_b FROM public.conversations WHERE id = p_conversation_id
    ) parties;
END;
$$;

-- ---------------------------------------------------------------------------
-- 7. Transcripción del descifrado de disputa: la escribe el proceso backend
--    (service_role) y solo la lee moderación. Ninguna app la toca.
-- ---------------------------------------------------------------------------
CREATE TABLE public.dispute_transcripts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL REFERENCES public.dispute_tickets(id),
    conversation_id uuid NOT NULL REFERENCES public.conversations(id),
    actor_id uuid REFERENCES public.users(id),
    reason text NOT NULL,
    transcript jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.dispute_transcripts ENABLE ROW LEVEL SECURITY;

CREATE INDEX dispute_transcripts_ticket_idx ON public.dispute_transcripts(ticket_id);

CREATE POLICY dispute_transcripts_read ON public.dispute_transcripts FOR SELECT TO authenticated
    USING (public.is_moderator());

-- ---------------------------------------------------------------------------
-- 8. Privilegios: revocar lo implícito y conceder solo lo necesario.
-- ---------------------------------------------------------------------------
REVOKE ALL ON public.conversation_keys, public.dispute_tickets,
    public.user_notifications, public.dispute_transcripts FROM anon, authenticated;
GRANT SELECT, INSERT ON public.conversation_keys TO authenticated;
GRANT SELECT ON public.dispute_tickets TO authenticated;
GRANT SELECT ON public.user_notifications TO authenticated;
GRANT UPDATE (read_at) ON public.user_notifications TO authenticated;
GRANT SELECT ON public.dispute_transcripts TO authenticated;
GRANT ALL ON public.conversation_keys, public.dispute_tickets,
    public.user_notifications, public.dispute_transcripts TO service_role;
-- moderation_actions: nadie (ni service_role) puede editar/borrar filas.
REVOKE UPDATE, DELETE ON public.moderation_actions FROM authenticated, service_role;

REVOKE ALL ON FUNCTION public.report_conversation(uuid, text),
    public.resolve_dispute_ticket(uuid, boolean),
    public.purge_expired_messages(),
    public.notify_thread_decrypted(uuid, text),
    public.moderation_actions_immutable() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.report_conversation(uuid, text),
    public.resolve_dispute_ticket(uuid, boolean) TO authenticated;
GRANT EXECUTE ON FUNCTION public.purge_expired_messages(),
    public.notify_thread_decrypted(uuid, text) TO service_role;

-- ---------------------------------------------------------------------------
-- 9. Realtime para mensajes y avisos (si la publicación existe en el entorno).
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        BEGIN
            ALTER PUBLICATION supabase_realtime ADD TABLE public.messages;
        EXCEPTION WHEN duplicate_object THEN NULL;
        END;
        BEGIN
            ALTER PUBLICATION supabase_realtime ADD TABLE public.user_notifications;
        EXCEPTION WHEN duplicate_object THEN NULL;
        END;
    END IF;
END;
$$;

COMMIT;

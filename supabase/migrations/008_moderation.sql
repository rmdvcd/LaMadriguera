-- Moderación integral: IA + reglas de dominio + cola humana + sanciones.
-- Aplicar después de 007_monetization_workflow.sql con el dueño de migración.
--
-- Principios (ver README):
--   * Nunca se borra contenido original: se marca oculto y se guarda evidencia.
--   * Sanciones: warn, hide, suspend (30 días), ban. El bloqueo pre-publicación
--     (acción 'block') es un rechazo auditado, no una sanción.
--   * La cola humana recibe solo contenido dudoso marcado por la IA y reportes
--     de usuarios. La plataforma no modera contenido privado (chat cifrado).
--   * Cada grupo tiene reglas visibles y un admin responsable (el owner).
BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Evidencia en el log inmutable + marcas de ocultación (nunca DELETE).
-- ---------------------------------------------------------------------------
ALTER TABLE public.moderation_actions
    ADD COLUMN evidence jsonb,
    ADD CONSTRAINT moderation_actions_action_check
        CHECK (action IN ('block', 'warn', 'hide', 'suspend', 'ban', 'decrypt_conversation')),
    ADD CONSTRAINT moderation_actions_evidence_object
        CHECK (evidence IS NULL OR jsonb_typeof(evidence) = 'object');

ALTER TABLE public.listings
    ADD COLUMN hidden_at timestamptz,
    ADD COLUMN hidden_by uuid REFERENCES public.users(id),
    ADD COLUMN hidden_reason text,
    ADD CONSTRAINT listings_hidden_pair CHECK ((hidden_at IS NULL) = (hidden_by IS NULL));

ALTER TABLE public.bets
    ADD COLUMN hidden_at timestamptz,
    ADD COLUMN hidden_by uuid REFERENCES public.users(id),
    ADD COLUMN hidden_reason text,
    ADD CONSTRAINT bets_hidden_pair CHECK ((hidden_at IS NULL) = (hidden_by IS NULL));

-- Suspensión temporal (30 días) sin perder la cuenta. Sin CHECK de pareja con
-- status: los triggers de otros flujos (p. ej. aprobación de identidad) cambian
-- status y no deben chocar con una columna informativa.
ALTER TABLE public.users
    ADD COLUMN suspended_until timestamptz;

-- ---------------------------------------------------------------------------
-- 2. Reportes: categoría y severidad para los filtros de la cola humana.
-- ---------------------------------------------------------------------------
ALTER TABLE public.reports
    ADD COLUMN category text NOT NULL DEFAULT 'other',
    ADD COLUMN severity smallint NOT NULL DEFAULT 2,
    ADD CONSTRAINT reports_severity_range CHECK (severity BETWEEN 0 AND 5);

-- ---------------------------------------------------------------------------
-- 3. Reglas visibles de cada grupo (admin responsable = owner_id).
-- ---------------------------------------------------------------------------
ALTER TABLE public.betting_groups
    ADD COLUMN rules text NOT NULL DEFAULT '';

-- ---------------------------------------------------------------------------
-- 4. Mensajes de grupo (contenido público de "grupos públicos").
--    Se publican vía RPC; la IA de texto se consulta en cliente ANTES de publicar.
-- ---------------------------------------------------------------------------
CREATE TABLE public.group_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id uuid NOT NULL REFERENCES public.betting_groups(id) ON DELETE CASCADE,
    sender_id uuid NOT NULL REFERENCES public.users(id),
    body text NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 1000),
    hidden_at timestamptz,
    hidden_by uuid REFERENCES public.users(id),
    hidden_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT group_messages_hidden_pair CHECK ((hidden_at IS NULL) = (hidden_by IS NULL))
);
CREATE INDEX group_messages_group_idx ON public.group_messages(group_id, created_at DESC);
CREATE INDEX group_messages_sender_idx ON public.group_messages(sender_id);

-- ---------------------------------------------------------------------------
-- 5. Cola humana: solo IA dudosa + reportes de usuarios.
-- ---------------------------------------------------------------------------
CREATE TABLE public.moderation_reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type text NOT NULL CHECK (target_type IN ('listing', 'group_message', 'bet', 'profile', 'message', 'betting_group', 'conversation')),
    target_id uuid NOT NULL,
    source public.moderation_source NOT NULL CHECK (source IN ('ai', 'user_report')),
    category text NOT NULL DEFAULT 'other',
    severity smallint NOT NULL CHECK (severity BETWEEN 0 AND 5),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'in_review', 'resolved', 'dismissed')),
    excerpt text,
    evidence jsonb,
    ai_provider text,
    ai_scores jsonb,
    report_id uuid REFERENCES public.reports(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    handled_by uuid REFERENCES public.users(id),
    handled_at timestamptz,
    resolution text,
    CONSTRAINT moderation_reviews_evidence_object
        CHECK (evidence IS NULL OR jsonb_typeof(evidence) = 'object'),
    CONSTRAINT moderation_reviews_handled_pair
        CHECK ((handled_by IS NULL) = (handled_at IS NULL))
);
CREATE INDEX moderation_reviews_status_idx ON public.moderation_reviews(status, severity DESC, created_at DESC);
CREATE INDEX moderation_reviews_target_idx ON public.moderation_reviews(target_type, target_id);
CREATE INDEX moderation_reviews_report_idx ON public.moderation_reviews(report_id) WHERE report_id IS NOT NULL;

-- Cada reporte de usuario entra automáticamente a la cola humana.
CREATE FUNCTION public.reports_enqueue_review() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.moderation_reviews(
        target_type, target_id, source, category, severity, status,
        excerpt, report_id
    ) VALUES (
        NEW.target_type, NEW.target_id, 'user_report',
        COALESCE(NULLIF(btrim(NEW.category), ''), 'other'),
        NEW.severity, 'pending', left(NEW.reason, 280), NEW.id
    );
    RETURN NEW;
END;
$$;
CREATE TRIGGER reports_enqueue_review
AFTER INSERT ON public.reports
FOR EACH ROW EXECUTE FUNCTION public.reports_enqueue_review();

-- ---------------------------------------------------------------------------
-- 6. Utilidades de alcance: dueño de grupo sobre el objetivo / autor del objetivo.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.owns_moderation_target(p_target_type text, p_target_id uuid)
RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF p_target_type = 'group_message' THEN
        RETURN EXISTS (
            SELECT 1 FROM public.group_messages gm
            WHERE gm.id = p_target_id AND public.owns_group(gm.group_id)
        );
    END IF;
    IF p_target_type = 'bet' THEN
        RETURN EXISTS (
            SELECT 1 FROM public.bets b
            WHERE b.id = p_target_id AND public.owns_group(b.group_id)
        );
    END IF;
    IF p_target_type = 'betting_group' THEN
        RETURN public.owns_group(p_target_id);
    END IF;
    RETURN false;
END;
$$;

-- Lectura de mensajes de grupo sin depender de RLS cruzados: grupo público,
-- miembros y el dueño. SECURITY DEFINER evita recursión de políticas.
CREATE FUNCTION public.can_read_group_messages(gid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.betting_groups g
        WHERE g.id = gid
          AND (g.visibility = 'public'
               OR public.is_group_member(g.id)
               OR g.owner_id = (SELECT auth.uid()))
    )
$$;

CREATE FUNCTION public.author_of_moderation_target(p_target_type text, p_target_id uuid)
RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_author uuid;
BEGIN
    CASE p_target_type
        WHEN 'listing' THEN
            SELECT owner_id INTO v_author FROM public.listings WHERE id = p_target_id;
        WHEN 'group_message' THEN
            SELECT sender_id INTO v_author FROM public.group_messages WHERE id = p_target_id;
        WHEN 'bet' THEN
            SELECT creator_id INTO v_author FROM public.bets WHERE id = p_target_id;
        WHEN 'profile' THEN
            SELECT user_id INTO v_author FROM public.profiles WHERE user_id = p_target_id;
        WHEN 'message' THEN
            SELECT sender_id INTO v_author FROM public.messages WHERE id = p_target_id;
        WHEN 'betting_group' THEN
            SELECT owner_id INTO v_author FROM public.betting_groups WHERE id = p_target_id;
        ELSE
            v_author := NULL;
    END CASE;
    RETURN v_author;
END;
$$;

-- ---------------------------------------------------------------------------
-- 7. RPC: publicación de mensajes de grupo (miembros de un grupo activo).
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.post_group_message(p_group_id uuid, p_body text)
RETURNS SETOF public.group_messages
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_message public.group_messages%ROWTYPE;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para publicar en el grupo' USING ERRCODE = '42501';
    END IF;
    IF p_body IS NULL OR char_length(btrim(p_body)) < 1 OR char_length(btrim(p_body)) > 1000 THEN
        RAISE EXCEPTION 'El mensaje debe tener entre 1 y 1000 caracteres' USING ERRCODE = '22023';
    END IF;
    IF NOT public.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'Solo los miembros pueden publicar en el grupo' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.betting_groups g WHERE g.id = p_group_id AND g.status = 'active'
    ) THEN
        RAISE EXCEPTION 'El grupo no está activo' USING ERRCODE = '23514';
    END IF;
    INSERT INTO public.group_messages(group_id, sender_id, body)
    VALUES (p_group_id, v_user_id, btrim(p_body))
    RETURNING * INTO v_message;
    RETURN NEXT v_message;
END;
$$;

CREATE FUNCTION public.set_betting_group_rules(p_group_id uuid, p_rules text)
RETURNS SETOF public.betting_groups
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_group public.betting_groups%ROWTYPE;
BEGIN
    IF (SELECT auth.uid()) IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para editar las reglas' USING ERRCODE = '42501';
    END IF;
    IF NOT public.owns_group(p_group_id) THEN
        RAISE EXCEPTION 'Solo el administrador del grupo puede editar las reglas' USING ERRCODE = '42501';
    END IF;
    IF p_rules IS NULL OR char_length(btrim(p_rules)) > 2000 THEN
        RAISE EXCEPTION 'Las reglas no pueden superar los 2000 caracteres' USING ERRCODE = '22023';
    END IF;
    UPDATE public.betting_groups
    SET rules = btrim(p_rules)
    WHERE id = p_group_id
    RETURNING * INTO v_group;
    RETURN NEXT v_group;
END;
$$;

-- ---------------------------------------------------------------------------
-- 8. RPC: cola humana (flag IA dudosa, log de bloqueos IA, sanciones, descartes).
-- ---------------------------------------------------------------------------

-- La IA dudosa (umbral bajo) entra a la cola. Solo el autor del contenido puede
-- marcarse a sí mismo; evita inundar la cola con contenido ajeno.
CREATE FUNCTION public.flag_moderation_review(
    p_target_type text,
    p_target_id uuid,
    p_category text,
    p_severity integer,
    p_excerpt text,
    p_evidence jsonb,
    p_ai_provider text,
    p_ai_scores jsonb
) RETURNS SETOF public.moderation_reviews
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_author uuid;
    v_review public.moderation_reviews%ROWTYPE;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para registrar la revisión' USING ERRCODE = '42501';
    END IF;
    IF p_target_type IS NULL OR p_target_id IS NULL
       OR p_severity IS NULL OR p_severity NOT BETWEEN 0 AND 5 THEN
        RAISE EXCEPTION 'Datos de revisión no válidos' USING ERRCODE = '22023';
    END IF;
    v_author := public.author_of_moderation_target(p_target_type, p_target_id);
    IF v_author IS DISTINCT FROM v_user_id THEN
        RAISE EXCEPTION 'Solo puedes marcar tu propio contenido para revisión' USING ERRCODE = '42501';
    END IF;

    -- Idempotente: una revisión abierta por objetivo basta.
    SELECT * INTO v_review
    FROM public.moderation_reviews r
    WHERE r.target_type = p_target_type
      AND r.target_id = p_target_id
      AND r.status IN ('pending', 'in_review')
    LIMIT 1;
    IF FOUND THEN
        RETURN NEXT v_review;
        RETURN;
    END IF;

    INSERT INTO public.moderation_reviews(
        target_type, target_id, source, category, severity, status,
        excerpt, evidence, ai_provider, ai_scores
    ) VALUES (
        p_target_type, p_target_id, 'ai',
        COALESCE(NULLIF(btrim(p_category), ''), 'other'),
        p_severity, 'pending',
        left(COALESCE(p_excerpt, ''), 280), p_evidence, p_ai_provider, p_ai_scores
    )
    RETURNING * INTO v_review;
    RETURN NEXT v_review;
END;
$$;

-- Registro auditado del bloqueo pre-publicación (la IA grave nunca se publica).
CREATE FUNCTION public.record_ai_moderation_action(
    p_target_type text,
    p_target_id uuid,
    p_category text,
    p_severity integer,
    p_reason text,
    p_evidence jsonb
) RETURNS SETOF public.moderation_actions
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_action public.moderation_actions%ROWTYPE;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para registrar la acción' USING ERRCODE = '42501';
    END IF;
    IF p_target_type IS NULL OR p_target_id IS NULL
       OR p_severity IS NULL OR p_severity NOT BETWEEN 0 AND 5
       OR NULLIF(btrim(p_reason), '') IS NULL THEN
        RAISE EXCEPTION 'Datos de la acción no válidos' USING ERRCODE = '22023';
    END IF;
    INSERT INTO public.moderation_actions(
        target_type, target_id, source, category, severity, action, reason, actor_id, evidence
    ) VALUES (
        p_target_type, p_target_id, 'ai',
        COALESCE(NULLIF(btrim(p_category), ''), 'other'),
        p_severity, 'block', btrim(p_reason), v_user_id, p_evidence
    )
    RETURNING * INTO v_action;
    RETURN NEXT v_action;
END;
$$;

-- Sanciones: warn | hide | suspend (30 días) | ban. Nunca borra contenido:
-- 'hide' marca oculto y guarda la evidencia en moderation_actions.
-- Los admins de grupo solo aplican warn/hide sobre contenido de SU grupo.
CREATE FUNCTION public.apply_moderation_action(
    p_target_type text,
    p_target_id uuid,
    p_action text,
    p_reason text,
    p_category text DEFAULT 'other',
    p_severity integer DEFAULT 3,
    p_evidence jsonb DEFAULT NULL
) RETURNS SETOF public.moderation_actions
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := (SELECT auth.uid());
    v_source public.moderation_source;
    v_author uuid;
    v_evidence jsonb := p_evidence;
    v_action public.moderation_actions%ROWTYPE;
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para aplicar sanciones' USING ERRCODE = '42501';
    END IF;
    IF p_action IS NULL OR p_action NOT IN ('warn', 'hide', 'suspend', 'ban') THEN
        RAISE EXCEPTION 'La sanción debe ser warn, hide, suspend o ban' USING ERRCODE = '22023';
    END IF;
    IF NULLIF(btrim(p_reason), '') IS NULL THEN
        RAISE EXCEPTION 'Indica el motivo de la sanción' USING ERRCODE = '22023';
    END IF;
    IF p_severity IS NULL OR p_severity NOT BETWEEN 0 AND 5 THEN
        RAISE EXCEPTION 'Severidad no válida' USING ERRCODE = '22023';
    END IF;
    IF p_target_type = 'message' THEN
        RAISE EXCEPTION 'La plataforma no modera el chat privado; los reportes se registran pero el contenido no se sanciona' USING ERRCODE = '42501';
    END IF;

    IF public.is_moderator() THEN
        v_source := 'admin';
    ELSIF p_action IN ('warn', 'hide') AND public.owns_moderation_target(p_target_type, p_target_id) THEN
        v_source := 'group_admin';
    ELSE
        RAISE EXCEPTION 'No tienes permisos para aplicar esta sanción' USING ERRCODE = '42501';
    END IF;

    v_author := public.author_of_moderation_target(p_target_type, p_target_id);
    IF v_author IS NULL THEN
        RAISE EXCEPTION 'El contenido ya no existe' USING ERRCODE = 'P0002';
    END IF;

    IF p_action = 'hide' THEN
        IF p_target_type = 'listing' THEN
            IF v_evidence IS NULL THEN v_evidence := (SELECT to_jsonb(l) FROM public.listings l WHERE l.id = p_target_id); END IF;
            UPDATE public.listings
            SET hidden_at = now(), hidden_by = v_actor, hidden_reason = btrim(p_reason)
            WHERE id = p_target_id AND hidden_at IS NULL;
        ELSIF p_target_type = 'group_message' THEN
            IF v_evidence IS NULL THEN v_evidence := (SELECT to_jsonb(gm) FROM public.group_messages gm WHERE gm.id = p_target_id); END IF;
            UPDATE public.group_messages
            SET hidden_at = now(), hidden_by = v_actor, hidden_reason = btrim(p_reason)
            WHERE id = p_target_id AND hidden_at IS NULL;
        ELSIF p_target_type = 'bet' THEN
            IF v_evidence IS NULL THEN v_evidence := (SELECT to_jsonb(b) FROM public.bets b WHERE b.id = p_target_id); END IF;
            UPDATE public.bets
            SET hidden_at = now(), hidden_by = v_actor, hidden_reason = btrim(p_reason)
            WHERE id = p_target_id AND hidden_at IS NULL;
        ELSIF p_target_type = 'betting_group' THEN
            IF v_evidence IS NULL THEN v_evidence := (SELECT to_jsonb(g) FROM public.betting_groups g WHERE g.id = p_target_id); END IF;
            UPDATE public.betting_groups
            SET status = 'suspended'
            WHERE id = p_target_id AND status = 'active';
        ELSE
            RAISE EXCEPTION 'Solo se oculta contenido: publicaciones, mensajes de grupo, apuestas o grupos' USING ERRCODE = '22023';
        END IF;
    END IF;

    IF p_action = 'warn' THEN
        INSERT INTO public.user_notifications(user_id, type, title, body)
        VALUES (v_author, 'moderation_warn', 'Aviso de moderación', btrim(p_reason));
    ELSIF p_action = 'suspend' THEN
        IF v_source <> 'admin' THEN
            RAISE EXCEPTION 'Solo la plataforma puede suspender cuentas' USING ERRCODE = '42501';
        END IF;
        IF p_target_type NOT IN ('profile', 'listing', 'group_message', 'bet', 'betting_group') THEN
            RAISE EXCEPTION 'La suspensión requiere un autor identificable' USING ERRCODE = '22023';
        END IF;
        UPDATE public.users
        SET status = 'suspended', suspended_until = now() + interval '30 days'
        WHERE id = v_author AND status NOT IN ('banned');
        INSERT INTO public.user_notifications(user_id, type, title, body)
        VALUES (v_author, 'moderation_suspended', 'Cuenta suspendida 30 días', btrim(p_reason));
    ELSIF p_action = 'ban' THEN
        IF v_source <> 'admin' THEN
            RAISE EXCEPTION 'Solo la plataforma puede expulsar cuentas' USING ERRCODE = '42501';
        END IF;
        UPDATE public.users
        SET status = 'banned', suspended_until = NULL
        WHERE id = v_author;
        INSERT INTO public.user_notifications(user_id, type, title, body)
        VALUES (v_author, 'moderation_banned', 'Cuenta expulsada', btrim(p_reason));
    END IF;

    INSERT INTO public.moderation_actions(
        target_type, target_id, source, category, severity, action, reason, actor_id, evidence
    ) VALUES (
        p_target_type, p_target_id, v_source,
        COALESCE(NULLIF(btrim(p_category), ''), 'other'),
        p_severity, p_action, btrim(p_reason), v_actor, v_evidence
    )
    RETURNING * INTO v_action;

    -- La cola se resuelve con la sanción; el contenido original permanece.
    UPDATE public.moderation_reviews
    SET status = 'resolved', handled_by = v_actor, handled_at = now(), resolution = btrim(p_reason)
    WHERE target_type = p_target_type
      AND target_id = p_target_id
      AND status IN ('pending', 'in_review');

    RETURN NEXT v_action;
END;
$$;

CREATE FUNCTION public.dismiss_moderation_review(p_review_id uuid, p_reason text)
RETURNS SETOF public.moderation_reviews
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := (SELECT auth.uid());
    v_review public.moderation_reviews%ROWTYPE;
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para descartar revisiones' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO v_review FROM public.moderation_reviews WHERE id = p_review_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Revisión no encontrada' USING ERRCODE = 'P0002';
    END IF;
    IF v_review.status NOT IN ('pending', 'in_review') THEN
        RAISE EXCEPTION 'La revisión ya fue resuelta' USING ERRCODE = '23514';
    END IF;
    IF NOT public.is_moderator()
       AND NOT public.owns_moderation_target(v_review.target_type, v_review.target_id) THEN
        RAISE EXCEPTION 'No tienes permisos para descartar esta revisión' USING ERRCODE = '42501';
    END IF;
    UPDATE public.moderation_reviews
    SET status = 'dismissed', handled_by = v_actor, handled_at = now(),
        resolution = COALESCE(NULLIF(btrim(p_reason), ''), 'Sin sanción')
    WHERE id = p_review_id
    RETURNING * INTO v_review;
    RETURN NEXT v_review;
END;
$$;

-- Cola del grupo para el admin responsable: solo objetivos de SU grupo.
CREATE FUNCTION public.group_moderation_queue(p_group_id uuid)
RETURNS SETOF public.moderation_reviews
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF (SELECT auth.uid()) IS NULL OR NOT public.owns_group(p_group_id) THEN
        RAISE EXCEPTION 'Solo el administrador del grupo puede ver su cola' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
    SELECT r.*
    FROM public.moderation_reviews r
    WHERE (r.target_type = 'betting_group' AND r.target_id = p_group_id)
       OR (r.target_type = 'group_message' AND r.target_id IN (
              SELECT gm.id FROM public.group_messages gm WHERE gm.group_id = p_group_id))
       OR (r.target_type = 'bet' AND r.target_id IN (
              SELECT b.id FROM public.bets b WHERE b.group_id = p_group_id))
    ORDER BY r.created_at DESC;
END;
$$;

-- Suspensión de 30 días: las vencidas vuelven a activas al consultarse.
CREATE FUNCTION public.expire_user_suspensions()
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_count integer;
BEGIN
    UPDATE public.users
    SET status = 'active', suspended_until = NULL
    WHERE status = 'suspended' AND suspended_until IS NOT NULL AND suspended_until <= now();
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

-- ---------------------------------------------------------------------------
-- 9. RLS y permisos.
-- ---------------------------------------------------------------------------
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.moderation_reviews ENABLE ROW LEVEL SECURITY;

CREATE POLICY group_messages_read ON public.group_messages FOR SELECT TO authenticated
USING (
    public.can_read_group_messages(group_id)
    AND (hidden_at IS NULL OR sender_id = (SELECT auth.uid()) OR public.is_moderator()
         OR public.owns_group(group_id))
);
-- Sin INSERT/UPDATE/DELETE directos: publicar es RPC y ocultar es sanción.

-- La cola humana: moderadores de plataforma y el admin responsable del grupo.
CREATE POLICY moderation_reviews_read ON public.moderation_reviews FOR SELECT TO authenticated
USING (public.is_moderator() OR public.owns_moderation_target(target_type, target_id));
-- Sin escritura directa: el trigger de reportes y las RPC restringen todo.

-- Los reportes también los ve el admin del grupo del contenido reportado.
DROP POLICY reports_read ON public.reports;
CREATE POLICY reports_read ON public.reports FOR SELECT TO authenticated
USING (
    reporter_id = (SELECT auth.uid())
    OR public.is_moderator()
    OR public.owns_moderation_target(target_type, target_id)
);

-- Ocultar marca la fila; el contenido original permanece pero sale del feed ajeno.
DROP POLICY listings_read ON public.listings;
CREATE POLICY listings_read ON public.listings FOR SELECT TO anon, authenticated
USING (
    owner_id = (SELECT auth.uid())
    OR (
        status IN (
            'active', 'fixed', 'auction_scheduled', 'auction_live',
            'auction_ended', 'awarded', 'no_reserve_met', 'expired'
        )
        AND (visibility = 'public' OR public.is_provider())
        AND hidden_at IS NULL
    )
);
-- Los moderadores ven el contenido oculto; política separada porque anon no
-- puede ejecutar is_moderator (solo authenticated/service_role).
CREATE POLICY listings_moderator_read ON public.listings FOR SELECT TO authenticated
USING (public.is_moderator());

DROP POLICY bets_read ON public.bets;
CREATE POLICY bets_read ON public.bets FOR SELECT TO authenticated
USING (
    (
        public.is_group_member(group_id)
        OR creator_id = (SELECT auth.uid())
        OR acceptor_id = (SELECT auth.uid())
    )
    AND (
        hidden_at IS NULL
        OR creator_id = (SELECT auth.uid())
        OR acceptor_id = (SELECT auth.uid())
        OR public.is_moderator()
        OR public.owns_group(group_id)
    )
);

REVOKE ALL ON public.group_messages, public.moderation_reviews FROM anon, authenticated;
GRANT SELECT ON public.group_messages, public.moderation_reviews TO authenticated;
GRANT ALL ON public.group_messages, public.moderation_reviews TO service_role;

REVOKE ALL ON FUNCTION public.owns_moderation_target(text, uuid),
    public.can_read_group_messages(uuid),
    public.author_of_moderation_target(text, uuid),
    public.post_group_message(uuid, text),
    public.set_betting_group_rules(uuid, text),
    public.flag_moderation_review(text, uuid, text, integer, text, jsonb, text, jsonb),
    public.record_ai_moderation_action(text, uuid, text, integer, text, jsonb),
    public.apply_moderation_action(text, uuid, text, text, text, integer, jsonb),
    public.dismiss_moderation_review(uuid, text),
    public.group_moderation_queue(uuid),
    public.expire_user_suspensions(),
    public.reports_enqueue_review() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.owns_moderation_target(text, uuid),
    public.can_read_group_messages(uuid),
    public.author_of_moderation_target(text, uuid),
    public.post_group_message(uuid, text),
    public.set_betting_group_rules(uuid, text),
    public.flag_moderation_review(text, uuid, text, integer, text, jsonb, text, jsonb),
    public.record_ai_moderation_action(text, uuid, text, integer, text, jsonb),
    public.apply_moderation_action(text, uuid, text, text, text, integer, jsonb),
    public.dismiss_moderation_review(uuid, text),
    public.group_moderation_queue(uuid),
    public.expire_user_suspensions() TO authenticated, service_role;

-- Sanciones y log viven en tablas de solo servicio/RPC; el cliente solo inserta
-- reportes. moderation_actions sigue estrictamente append-only (trigger 004).
GRANT INSERT ON public.reports TO authenticated;

COMMIT;

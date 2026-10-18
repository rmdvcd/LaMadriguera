-- Fase 8 (distribución fuera de Play): notificaciones push y preferencias.
-- Supabase/PostgreSQL 15+. Aplicar después de 008_moderation.sql.
--
-- Piezas:
--   1. user_push_tokens    — tokens FCM por dispositivo (RLS: solo el dueño).
--   2. user_notification_settings — interruptores por tipo de aviso y chat_id
--      opcional de Telegram (solo se usa si el usuario lo comparte).
--   3. Triggers que generan user_notifications para los 4 eventos de push:
--      oferta_recibida (puja nueva), mensaje_nuevo, subasta_ganada.
--      (pago_confirmado ya lo inserta la fase 5: type 'payment_confirmed'.)
--      user_notifications ya está en la publicación Realtime (004), así que el
--      fallback in-app funciona incluso sin FCM.
--   4. Webhook opcional hacia la Edge Function `push-dispatch` (FCM + Telegram).

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Dispositivos con token FCM. Un token físico identifica UN dispositivo;
--    al cambiar de cuenta el cliente hace upsert on-conflict (token único).
--    El logout elimina la fila: sin sesión no hay push.
-- ---------------------------------------------------------------------------
CREATE TABLE public.user_push_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    token text NOT NULL UNIQUE,
    platform text NOT NULL DEFAULT 'android' CHECK (platform IN ('android')),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.user_push_tokens ENABLE ROW LEVEL SECURITY;

CREATE INDEX user_push_tokens_user_idx ON public.user_push_tokens(user_id);

CREATE POLICY user_push_tokens_select ON public.user_push_tokens FOR SELECT TO authenticated
    USING (user_id = (SELECT auth.uid()));
CREATE POLICY user_push_tokens_insert ON public.user_push_tokens FOR INSERT TO authenticated
    WITH CHECK (user_id = (SELECT auth.uid()));
CREATE POLICY user_push_tokens_touch ON public.user_push_tokens FOR UPDATE TO authenticated
    USING (user_id = (SELECT auth.uid())) WITH CHECK (user_id = (SELECT auth.uid()));
CREATE POLICY user_push_tokens_delete ON public.user_push_tokens FOR DELETE TO authenticated
    USING (user_id = (SELECT auth.uid()));

REVOKE ALL ON public.user_push_tokens FROM anon, authenticated;
GRANT SELECT, INSERT, DELETE ON public.user_push_tokens TO authenticated;
GRANT UPDATE (last_seen_at) ON public.user_push_tokens TO authenticated;
GRANT ALL ON public.user_push_tokens TO service_role;

-- ---------------------------------------------------------------------------
-- 2. Preferencias por usuario. El bot de Telegram es OPCIONAL y solo se usa
--    si el usuario compartió su chat_id (telegram_chat_id no nulo).
--    telegram_chat_id valida formato de id numérico (los del bot/puente lo
--    devuelven así, p. ej. 123456789 o -1001234567890).
-- ---------------------------------------------------------------------------
CREATE TABLE public.user_notification_settings (
    user_id uuid PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    push_enabled boolean NOT NULL DEFAULT true,
    notify_offers boolean NOT NULL DEFAULT true,
    notify_messages boolean NOT NULL DEFAULT true,
    notify_payments boolean NOT NULL DEFAULT true,
    notify_auctions boolean NOT NULL DEFAULT true,
    telegram_enabled boolean NOT NULL DEFAULT false,
    telegram_chat_id text CHECK (telegram_chat_id IS NULL OR telegram_chat_id ~ '^-?[0-9]{1,20}$'),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (NOT telegram_enabled OR telegram_chat_id IS NOT NULL)
);
ALTER TABLE public.user_notification_settings ENABLE ROW LEVEL SECURITY;

CREATE POLICY user_notification_settings_rw ON public.user_notification_settings
    FOR ALL TO authenticated
    USING (user_id = (SELECT auth.uid()))
    WITH CHECK (user_id = (SELECT auth.uid()));

REVOKE ALL ON public.user_notification_settings FROM anon, authenticated;
GRANT SELECT, INSERT, UPDATE ON public.user_notification_settings TO authenticated;
GRANT ALL ON public.user_notification_settings TO service_role;

-- Fila por defecto para cada usuario (mismo patrón que betting_controls en 005).
CREATE FUNCTION public.create_notification_settings_for_user() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.user_notification_settings (user_id)
    VALUES (NEW.id)
    ON CONFLICT (user_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_create_notification_settings
    AFTER INSERT ON public.users
    FOR EACH ROW EXECUTE FUNCTION public.create_notification_settings_for_user();

-- Backfill para cuentas ya existentes.
INSERT INTO public.user_notification_settings (user_id)
SELECT id FROM public.users
ON CONFLICT (user_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. Generadores de user_notifications para los tres eventos nuevos.
--    (El cuarto, pago confirmado, ya existe: 'payment_confirmed' en 007.)
-- ---------------------------------------------------------------------------

-- 3.1 Oferta recibida: alguien pujó sobre una publicación en subasta propia.
CREATE FUNCTION public.notify_bid_received() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_owner uuid;
    v_title text;
    v_currency text;
BEGIN
    SELECT l.owner_id, left(l.title, 80), l.currency
    INTO v_owner, v_title, v_currency
    FROM public.listings l
    WHERE l.id = NEW.listing_id;

    IF v_owner IS NOT NULL AND v_owner <> NEW.bidder_id THEN
        INSERT INTO public.user_notifications (user_id, type, title, body)
        VALUES (
            v_owner,
            'oferta_recibida',
            'Oferta recibida',
            'Nueva puja de ' || to_char(NEW.amount, 'FM999999990.##') || ' ' || v_currency
                || ' en "' || v_title || '".'
        );
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bids_notify_owner
    AFTER INSERT ON public.bids
    FOR EACH ROW EXECUTE FUNCTION public.notify_bid_received();

-- 3.2 Mensaje nuevo: avisa al otro participante. El cuerpo es GENÉRICO a
--     propósito: el chat está cifrado de extremo a extremo y el servidor no
--     puede (ni debe) leer el contenido.
CREATE FUNCTION public.notify_new_message() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_peer uuid;
    v_title text;
BEGIN
    SELECT CASE WHEN c.participant_a = NEW.sender_id THEN c.participant_b
                ELSE c.participant_a END,
           left(l.title, 80)
    INTO v_peer, v_title
    FROM public.conversations c
    LEFT JOIN public.listings l ON l.id = c.listing_id
    WHERE c.id = NEW.conversation_id;

    -- Sin participante "otro" (emisor ajeno a la conversación): nada que avisar.
    IF v_peer IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO public.user_notifications (user_id, type, title, body)
    VALUES (
        v_peer,
        'mensaje_nuevo',
        'Mensaje nuevo',
        'Tienes un mensaje nuevo' || coalesce(' en el chat de "' || v_title || '"', '') || '.'
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER messages_notify_peer
    AFTER INSERT ON public.messages
    FOR EACH ROW EXECUTE FUNCTION public.notify_new_message();

-- 3.3 Subasta ganada: al adjudicar ('awarded'), se avisa al mejor postor.
CREATE FUNCTION public.notify_auction_awarded() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_bidder uuid;
    v_amount numeric;
BEGIN
    IF NEW.status = 'awarded' AND OLD.status IS DISTINCT FROM NEW.status THEN
        SELECT b.bidder_id, b.amount
        INTO v_bidder, v_amount
        FROM public.bids b
        WHERE b.listing_id = NEW.id
          AND b.status = 'winning'
        ORDER BY b.amount DESC, b.created_at ASC, b.id ASC
        LIMIT 1;

        IF v_bidder IS NOT NULL THEN
            INSERT INTO public.user_notifications (user_id, type, title, body)
            VALUES (
                v_bidder,
                'subasta_ganada',
                '¡Subasta ganada!',
                'Tu oferta de ' || to_char(v_amount, 'FM999999990.##') || ' ' || NEW.currency
                    || ' ganó "' || left(NEW.title, 80) || '". Acuerda el pago con el vendedor por el chat.'
            );
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER listings_awarded_notify
    AFTER UPDATE OF status ON public.listings
    FOR EACH ROW EXECUTE FUNCTION public.notify_auction_awarded();

-- ---------------------------------------------------------------------------
-- 4. Webhook hacia la Edge Function `push-dispatch` (push FCM + fallback
--    Telegram). Solo se crea si el entorno expone supabase_functions.http_request
--    y si al aplicar la migración se indicó la URL:
--
--      set app.push_dispatch_url = 'https://<ref>.supabase.co/functions/v1/push-dispatch';
--      set app.push_dispatch_secret = '<secreto compartido con la función>';
--
--    Sin webhook, la app sigue notificando in-app (Realtime) y la función se
--    puede colgar después desde el dashboard (Database Webhooks) o re-ejecutar
--    este bloque con las GUCs puestas.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_url text := btrim(coalesce(current_setting('app.push_dispatch_url', true), ''));
    v_secret text := btrim(coalesce(current_setting('app.push_dispatch_secret', true), ''));
BEGIN
    IF v_url = '' THEN
        RAISE NOTICE 'push-dispatch no enganchado: define app.push_dispatch_url al aplicar la migración (el aviso in-app sigue funcionando).';
        RETURN;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'supabase_functions' AND p.proname = 'http_request'
    ) THEN
        RAISE WARNING 'supabase_functions.http_request no existe: configura el webhook desde el dashboard.';
        RETURN;
    END IF;
    IF v_secret = '' THEN
        RAISE WARNING 'push-dispatch: sin app.push_dispatch_secret, la función solo aceptará peticiones con el secreto vacío (NO recomendado).';
    END IF;
    EXECUTE format(
        'CREATE TRIGGER user_notifications_push_dispatch
             AFTER INSERT ON public.user_notifications
             FOR EACH ROW
             EXECUTE FUNCTION supabase_functions.http_request(%L, %L, %L, %L, %L)',
        v_url,
        'POST',
        json_build_object('Content-Type', 'application/json', 'x-push-secret', v_secret)::text,
        '{}',
        '1000'
    );
END;
$$;

-- ---------------------------------------------------------------------------
-- 5. Privilgios de funciones.
-- ---------------------------------------------------------------------------
REVOKE ALL ON FUNCTION public.notify_bid_received(), public.notify_new_message(),
    public.notify_auction_awarded(), public.create_notification_settings_for_user() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.notify_bid_received(), public.notify_new_message(),
    public.notify_auction_awarded(), public.create_notification_settings_for_user() FROM anon, authenticated;

COMMIT;

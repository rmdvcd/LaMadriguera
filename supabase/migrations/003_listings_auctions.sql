-- Fase 2: módulo de listings (ofertas y demandas) y modo subasta configurable.
-- Supabase/PostgreSQL 15+. Ejecutar una vez con el dueño de la migración (postgres),
-- después de 002_auth_identity_privacy.sql.

-- ---------------------------------------------------------------------------
-- 1. Estados del ciclo de vida de listings y subastas:
--    fixed -> auction_scheduled -> auction_live -> auction_ended ->
--    awarded | no_reserve_met | expired
--    (En PostgreSQL, los nuevos valores de enum deben confirmarse antes de
--    referenciarse en políticas o funciones SQL.)
-- ---------------------------------------------------------------------------
BEGIN;
ALTER TYPE public.listing_status ADD VALUE IF NOT EXISTS 'fixed';
ALTER TYPE public.listing_status ADD VALUE IF NOT EXISTS 'auction_scheduled';
ALTER TYPE public.listing_status ADD VALUE IF NOT EXISTS 'auction_ended';
ALTER TYPE public.listing_status ADD VALUE IF NOT EXISTS 'no_reserve_met';
ALTER TYPE public.listing_status ADD VALUE IF NOT EXISTS 'expired';
COMMIT;

BEGIN;

-- Visibilidad configurable para demandas (kind = 'request'):
-- 'public' (todos) o 'providers_only' (solo proveedores verificados o con ofertas).
CREATE TYPE public.listing_visibility AS ENUM ('public', 'providers_only');

-- ---------------------------------------------------------------------------
-- 2. Columnas adicionales en listings: foto opcional y visibilidad.
-- ---------------------------------------------------------------------------
ALTER TABLE public.listings
    ADD COLUMN photo_url text,
    ADD COLUMN visibility public.listing_visibility NOT NULL DEFAULT 'public',
    ADD CONSTRAINT listings_offer_visibility_check
        CHECK (kind = 'request' OR visibility = 'public');

GRANT UPDATE (photo_url, visibility) ON public.listings TO authenticated;

-- ---------------------------------------------------------------------------
-- 3. Funciones auxiliares de seguridad para subastas y visibilidad de demandas.
-- ---------------------------------------------------------------------------
-- Un proveedor es un usuario autenticado con identidad verificada o con al menos
-- una oferta publicada activa.
CREATE FUNCTION public.is_provider() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT (SELECT auth.uid()) IS NOT NULL AND (
        EXISTS (
            SELECT 1 FROM public.users u
            WHERE u.id = (SELECT auth.uid())
              AND u.verification_level = 'identity'
              AND u.status = 'active'
        )
        OR EXISTS (
            SELECT 1 FROM public.listings l
            WHERE l.owner_id = (SELECT auth.uid())
              AND l.kind = 'offer'
              AND l.status IN ('active', 'fixed', 'auction_scheduled', 'auction_live')
        )
    )
$$;

-- Determina si el usuario tiene alguna disputa abierta en conversaciones o apuestas.
CREATE FUNCTION public.has_open_dispute(uid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.conversations c
        WHERE c.status = 'disputed'
          AND uid IN (c.participant_a, c.participant_b)
    ) OR EXISTS (
        SELECT 1 FROM public.bets b
        WHERE b.status = 'disputed'
          AND uid IN (b.creator_id, b.acceptor_id)
    )
$$;

-- ---------------------------------------------------------------------------
-- 4. Políticas RLS de lectura de listings:
--    - Oculta 'closed' y 'paused_payment' salvo para su dueño.
--    - Si kind = 'request' y visibility = 'providers_only', solo la ven el
--      dueño y los proveedores.
-- ---------------------------------------------------------------------------
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
    )
);

-- ---------------------------------------------------------------------------
-- 5. Validación y sincronización de auction_config y bloqueo de edición tras pujas.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.validate_and_sync_listing_auction() RETURNS trigger
LANGUAGE plpgsql SET search_path = '' AS $$
DECLARE
    v_starting_price numeric;
    v_min_increment numeric;
    v_reserve_price numeric;
    v_starts_at timestamptz;
    v_ends_at timestamptz;
    v_auto_extend int;
    v_requires_identity boolean;
    v_visible_bids text;
    v_has_bids boolean;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        -- Solo el owner puede editar la configuración mientras no haya pujas.
        -- Cuando pg_trigger_depth() > 1, el cambio proviene de la auto-extensión
        -- disparada por una nueva puja válida.
        IF pg_trigger_depth() <= 1 AND (
            OLD.sale_mode IS DISTINCT FROM NEW.sale_mode
            OR OLD.auction_config IS DISTINCT FROM NEW.auction_config
        ) THEN
            SELECT EXISTS (
                SELECT 1 FROM public.bids b WHERE b.listing_id = OLD.id
            ) INTO v_has_bids;
            IF v_has_bids THEN
                RAISE EXCEPTION 'Solo el dueño puede editar la configuración mientras no haya pujas'
                    USING ERRCODE = '42501';
            END IF;
        END IF;
    END IF;

    -- Solo validar la estructura completa de subasta cuando se activa o modifica auction_config.
    IF NEW.sale_mode = 'auction' AND (
        TG_OP = 'INSERT'
        OR OLD.sale_mode IS DISTINCT FROM NEW.sale_mode
        OR OLD.auction_config IS DISTINCT FROM NEW.auction_config
    ) THEN
        IF NEW.auction_config IS NULL OR jsonb_typeof(NEW.auction_config) <> 'object' THEN
            RAISE EXCEPTION 'auction_config es obligatorio en modo subasta'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.auction_config ? 'starting_price'
           AND NEW.auction_config ? 'min_increment'
           AND NEW.auction_config ? 'starts_at'
           AND NEW.auction_config ? 'ends_at' THEN

            v_starting_price := (NEW.auction_config->>'starting_price')::numeric;
            v_min_increment := (NEW.auction_config->>'min_increment')::numeric;
            v_starts_at := (NEW.auction_config->>'starts_at')::timestamptz;
            v_ends_at := (NEW.auction_config->>'ends_at')::timestamptz;
            v_auto_extend := COALESCE((NEW.auction_config->>'auto_extend_minutes')::int, 2);
            v_visible_bids := COALESCE(NEW.auction_config->>'visible_bids', 'all');

            IF v_starting_price IS NULL OR v_starting_price <= 0 THEN
                RAISE EXCEPTION 'starting_price debe ser mayor que 0'
                    USING ERRCODE = '23514';
            END IF;
            IF v_min_increment IS NULL OR v_min_increment <= 0 THEN
                RAISE EXCEPTION 'min_increment debe ser mayor que 0'
                    USING ERRCODE = '23514';
            END IF;
            IF v_starts_at IS NULL OR v_ends_at IS NULL OR v_ends_at <= v_starts_at THEN
                RAISE EXCEPTION 'ends_at debe ser posterior a starts_at'
                    USING ERRCODE = '23514';
            END IF;
            IF v_auto_extend < 0 THEN
                RAISE EXCEPTION 'auto_extend_minutes no puede ser negativo'
                    USING ERRCODE = '23514';
            END IF;
            IF v_visible_bids NOT IN ('all', 'owner') THEN
                RAISE EXCEPTION 'visible_bids debe ser all u owner'
                    USING ERRCODE = '23514';
            END IF;

            IF NEW.auction_config ? 'reserve_price'
               AND jsonb_typeof(NEW.auction_config->'reserve_price') <> 'null' THEN
                v_reserve_price := (NEW.auction_config->>'reserve_price')::numeric;
                IF v_reserve_price < v_starting_price THEN
                    RAISE EXCEPTION 'reserve_price debe ser mayor o igual a starting_price'
                        USING ERRCODE = '23514';
                END IF;
            END IF;

            -- Si es subasta con dinero (starting_price > 0), requires_identity por defecto true.
            v_requires_identity := COALESCE(
                (NEW.auction_config->>'requires_identity')::boolean,
                v_starting_price > 0
            );

            NEW.auction_config := NEW.auction_config || jsonb_build_object(
                'auto_extend_minutes', v_auto_extend,
                'requires_identity', v_requires_identity,
                'visible_bids', v_visible_bids
            );

            IF pg_trigger_depth() <= 1 AND NEW.status IN ('active', 'fixed', 'auction_scheduled', 'auction_live') THEN
                IF now() < v_starts_at THEN
                    NEW.status := 'auction_scheduled';
                ELSIF now() < v_ends_at THEN
                    NEW.status := 'auction_live';
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER listings_auction_guard
BEFORE INSERT OR UPDATE ON public.listings
FOR EACH ROW EXECUTE FUNCTION public.validate_and_sync_listing_auction();

-- ---------------------------------------------------------------------------
-- 6. Políticas y trigger de validación/auto-extensión en pujas (public.bids).
--    - Lectura: el propio postor, el dueño del listing, o cualquiera si
--      visible_bids = 'all'.
--    - Inserción: requiere verification_level = 'identity' si requires_identity,
--      máximo 3 pujas por usuario por subasta, sin disputas abiertas, monto
--      válido y ventana temporal activa. Auto-extiende ends_at si aplica.
-- ---------------------------------------------------------------------------
DROP POLICY bids_read ON public.bids;
CREATE POLICY bids_read ON public.bids FOR SELECT TO authenticated USING (
    bidder_id = (SELECT auth.uid())
    OR EXISTS (
        SELECT 1 FROM public.listings l
        WHERE l.id = listing_id
          AND (
              l.owner_id = (SELECT auth.uid())
              OR COALESCE(l.auction_config->>'visible_bids', 'all') = 'all'
          )
    )
);

DROP POLICY bids_insert ON public.bids;
CREATE POLICY bids_insert ON public.bids FOR INSERT TO authenticated WITH CHECK (
    bidder_id = (SELECT auth.uid())
    AND status = 'valid'
    AND EXISTS (
        SELECT 1 FROM public.listings l
        WHERE l.id = listing_id
          AND l.owner_id <> (SELECT auth.uid())
          AND l.sale_mode = 'auction'
          AND l.status IN ('active', 'auction_scheduled', 'auction_live')
    )
);

CREATE FUNCTION public.validate_and_process_bid() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_listing public.listings%ROWTYPE;
    v_user_verification public.verification_level;
    v_user_status public.user_status;
    v_starting_price numeric;
    v_min_increment numeric;
    v_starts_at timestamptz;
    v_ends_at timestamptz;
    v_auto_extend int;
    v_requires_identity boolean;
    v_user_bids_count int;
    v_current_max numeric;
    v_min_required numeric;
    v_new_ends_at timestamptz;
BEGIN
    SELECT * INTO v_listing
    FROM public.listings
    WHERE id = NEW.listing_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Publicación no encontrada' USING ERRCODE = '23503';
    END IF;

    IF v_listing.sale_mode <> 'auction' THEN
        RAISE EXCEPTION 'La publicación no está en modo subasta' USING ERRCODE = '23514';
    END IF;

    IF v_listing.owner_id = NEW.bidder_id THEN
        RAISE EXCEPTION 'El dueño no puede pujar en su propia subasta' USING ERRCODE = '42501';
    END IF;

    IF v_listing.auction_config IS NULL THEN
        RAISE EXCEPTION 'La subasta no tiene configuración válida' USING ERRCODE = '23514';
    END IF;

    v_starting_price := COALESCE((v_listing.auction_config->>'starting_price')::numeric, v_listing.price);
    v_min_increment := COALESCE((v_listing.auction_config->>'min_increment')::numeric, 1);
    v_starts_at := (v_listing.auction_config->>'starts_at')::timestamptz;
    v_ends_at := (v_listing.auction_config->>'ends_at')::timestamptz;
    v_auto_extend := COALESCE((v_listing.auction_config->>'auto_extend_minutes')::int, 2);
    v_requires_identity := COALESCE((v_listing.auction_config->>'requires_identity')::boolean, v_starting_price > 0);

    IF v_starts_at IS NOT NULL AND NEW.created_at < v_starts_at THEN
        RAISE EXCEPTION 'La subasta aún no ha comenzado' USING ERRCODE = '23514';
    END IF;

    IF v_ends_at IS NOT NULL AND NEW.created_at >= v_ends_at THEN
        RAISE EXCEPTION 'La subasta ya ha finalizado' USING ERRCODE = '23514';
    END IF;

    IF v_listing.status NOT IN ('active', 'auction_scheduled', 'auction_live') THEN
        RAISE EXCEPTION 'La subasta no admite pujas en su estado actual' USING ERRCODE = '23514';
    END IF;

    -- Promoción automática a auction_live si estaba programada y ya empezó.
    IF v_listing.status IN ('active', 'auction_scheduled') THEN
        UPDATE public.listings
        SET status = 'auction_live'
        WHERE id = v_listing.id;
    END IF;

    -- Seguridad 1: Pujar requiere verification_level = 'identity' si requires_identity = true.
    SELECT verification_level, status
    INTO v_user_verification, v_user_status
    FROM public.users
    WHERE id = NEW.bidder_id;

    IF v_user_status IN ('suspended', 'banned') THEN
        RAISE EXCEPTION 'Tu cuenta no puede realizar pujas' USING ERRCODE = '42501';
    END IF;

    IF v_requires_identity AND COALESCE(v_user_verification, 'none') <> 'identity' THEN
        RAISE EXCEPTION 'Debes verificar tu identidad para pujar en esta subasta'
            USING ERRCODE = '42501';
    END IF;

    -- Seguridad 2: No se puede pujar si el usuario tiene una disputa abierta.
    IF public.has_open_dispute(NEW.bidder_id) THEN
        RAISE EXCEPTION 'No puedes pujar mientras tengas una disputa abierta'
            USING ERRCODE = '42501';
    END IF;

    -- Seguridad 3: Máximo 3 pujas por usuario por subasta.
    SELECT count(*) INTO v_user_bids_count
    FROM public.bids
    WHERE listing_id = NEW.listing_id
      AND bidder_id = NEW.bidder_id;

    IF v_user_bids_count >= 3 THEN
        RAISE EXCEPTION 'Máximo 3 pujas por usuario por subasta'
            USING ERRCODE = '23514';
    END IF;

    -- Validación del importe de la puja.
    SELECT max(amount) INTO v_current_max
    FROM public.bids
    WHERE listing_id = NEW.listing_id
      AND status IN ('valid', 'winning');

    IF v_current_max IS NULL THEN
        v_min_required := v_starting_price;
    ELSE
        v_min_required := v_current_max + v_min_increment;
    END IF;

    IF NEW.amount < v_min_required THEN
        RAISE EXCEPTION 'El monto de la puja no alcanza el mínimo requerido (%)' , v_min_required
            USING ERRCODE = '23514';
    END IF;

    -- Auto-extensión si la puja entra en los últimos auto_extend_minutes antes de ends_at.
    IF v_ends_at IS NOT NULL AND v_auto_extend > 0
       AND (v_ends_at - NEW.created_at) <= make_interval(mins => v_auto_extend) THEN
        v_new_ends_at := v_ends_at + make_interval(mins => v_auto_extend);
        UPDATE public.listings
        SET auction_config = jsonb_set(
            auction_config,
            '{ends_at}',
            to_jsonb(to_char(v_new_ends_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))
        )
        WHERE id = v_listing.id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER bids_before_insert_guard
BEFORE INSERT ON public.bids
FOR EACH ROW EXECUTE FUNCTION public.validate_and_process_bid();

-- ---------------------------------------------------------------------------
-- 7. RPC para pujar y para cerrar subastas vencidas.
--    Estados: fixed -> auction_scheduled -> auction_live -> auction_ended ->
--             awarded | no_reserve_met | expired
--    Al terminar: si hay pujas y se alcanzó reserve, gana la mayor;
--    si no se alcanzó reserve -> no_reserve_met; si no hay pujas -> expired.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.place_auction_bid(
    p_listing_id uuid,
    p_amount numeric,
    p_device_hash text DEFAULT NULL,
    p_ip_hash text DEFAULT NULL
) RETURNS public.bids
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_uid uuid;
    v_bid public.bids%ROWTYPE;
BEGIN
    v_uid := (SELECT auth.uid());
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Autenticación requerida' USING ERRCODE = '42501';
    END IF;

    INSERT INTO public.bids (listing_id, bidder_id, amount, device_hash, ip_hash, status)
    VALUES (p_listing_id, v_uid, p_amount, p_device_hash, p_ip_hash, 'valid')
    RETURNING * INTO v_bid;

    RETURN v_bid;
END;
$$;

CREATE FUNCTION public.close_auction(p_listing_id uuid) RETURNS public.listings
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_listing public.listings%ROWTYPE;
    v_ends_at timestamptz;
    v_reserve numeric;
    v_winning_bid_id uuid;
    v_winning_amount numeric;
    v_final_status public.listing_status;
BEGIN
    SELECT * INTO v_listing
    FROM public.listings
    WHERE id = p_listing_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Publicación no encontrada' USING ERRCODE = '23503';
    END IF;

    IF v_listing.sale_mode <> 'auction' THEN
        RAISE EXCEPTION 'La publicación no es una subasta' USING ERRCODE = '23514';
    END IF;

    -- Idempotente si la subasta ya fue resuelta.
    IF v_listing.status IN ('awarded', 'no_reserve_met', 'expired') THEN
        RETURN v_listing;
    END IF;

    v_ends_at := (v_listing.auction_config->>'ends_at')::timestamptz;
    IF v_ends_at IS NOT NULL AND now() < v_ends_at AND v_listing.status <> 'auction_ended' THEN
        RAISE EXCEPTION 'La subasta aún no ha concluido' USING ERRCODE = '23514';
    END IF;

    -- Paso por auction_ended antes de adjudicar / resolver estado terminal.
    UPDATE public.listings
    SET status = 'auction_ended'
    WHERE id = p_listing_id;

    SELECT id, amount
    INTO v_winning_bid_id, v_winning_amount
    FROM public.bids
    WHERE listing_id = p_listing_id
      AND status IN ('valid', 'winning')
    ORDER BY amount DESC, created_at ASC, id ASC
    LIMIT 1;

    IF v_winning_bid_id IS NULL THEN
        v_final_status := 'expired';
    ELSE
        IF v_listing.auction_config ? 'reserve_price'
           AND jsonb_typeof(v_listing.auction_config->'reserve_price') <> 'null' THEN
            v_reserve := (v_listing.auction_config->>'reserve_price')::numeric;
        ELSE
            v_reserve := NULL;
        END IF;

        IF v_reserve IS NOT NULL AND v_winning_amount < v_reserve THEN
            v_final_status := 'no_reserve_met';
            UPDATE public.bids
            SET status = 'losing'
            WHERE listing_id = p_listing_id
              AND status IN ('valid', 'winning');
        ELSE
            v_final_status := 'awarded';
            UPDATE public.bids
            SET status = CASE
                WHEN id = v_winning_bid_id THEN 'winning'::public.bid_status
                ELSE 'losing'::public.bid_status
            END
            WHERE listing_id = p_listing_id
              AND status IN ('valid', 'winning');
        END IF;
    END IF;

    UPDATE public.listings
    SET status = v_final_status,
        price = CASE WHEN v_final_status = 'awarded' THEN v_winning_amount ELSE price END
    WHERE id = p_listing_id
    RETURNING * INTO v_listing;

    RETURN v_listing;
END;
$$;

-- Job / barrido de subastas: promueve subastas programadas que ya iniciaron y
-- cierra todas las subastas cuyo ends_at ya venció.
CREATE FUNCTION public.close_due_auctions() RETURNS SETOF public.listings
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_id uuid;
BEGIN
    UPDATE public.listings
    SET status = 'auction_live'
    WHERE sale_mode = 'auction'
      AND status IN ('auction_scheduled', 'active')
      AND (auction_config->>'starts_at')::timestamptz <= now()
      AND (auction_config->>'ends_at')::timestamptz > now();

    FOR v_id IN
        SELECT id FROM public.listings
        WHERE sale_mode = 'auction'
          AND status IN ('auction_scheduled', 'auction_live', 'active', 'auction_ended')
          AND (auction_config->>'ends_at')::timestamptz <= now()
    LOOP
        RETURN NEXT public.close_auction(v_id);
    END LOOP;
    RETURN;
END;
$$;

REVOKE ALL ON FUNCTION
    public.is_provider(),
    public.has_open_dispute(uuid),
    public.validate_and_sync_listing_auction(),
    public.validate_and_process_bid(),
    public.place_auction_bid(uuid, numeric, text, text),
    public.close_auction(uuid),
    public.close_due_auctions()
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.is_provider() TO anon, authenticated;
GRANT EXECUTE ON FUNCTION
    public.has_open_dispute(uuid),
    public.place_auction_bid(uuid, numeric, text, text),
    public.close_auction(uuid),
    public.close_due_auctions()
TO authenticated, service_role;

-- ---------------------------------------------------------------------------
-- 8. Bucket público de fotos de publicaciones (máx 1 MB validado en cliente).
--    Escritura y borrado acotados a la carpeta <auth.uid()>/ de cada usuario.
-- ---------------------------------------------------------------------------
INSERT INTO storage.buckets (id, name, public)
VALUES ('listing-photos', 'listing-photos', true)
ON CONFLICT (id) DO NOTHING;

CREATE POLICY listing_photos_select ON storage.objects FOR SELECT TO anon, authenticated
USING (bucket_id = 'listing-photos');

CREATE POLICY listing_photos_insert ON storage.objects FOR INSERT TO authenticated
WITH CHECK (
    bucket_id = 'listing-photos'
    AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
);

CREATE POLICY listing_photos_delete ON storage.objects FOR DELETE TO authenticated
USING (
    bucket_id = 'listing-photos'
    AND ((storage.foldername(name))[1] = (SELECT auth.uid())::text OR public.is_admin())
);

COMMIT;

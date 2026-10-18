-- Apuestas P2P responsables: la aplicación no procesa pagos.
-- Aplicar después de 004_chat_encryption_disputes.sql con el dueño de migración.
BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Forma de una apuesta y trazabilidad completa.
-- ---------------------------------------------------------------------------
ALTER TABLE public.bets
    ALTER COLUMN odds DROP NOT NULL,
    ALTER COLUMN odds DROP DEFAULT;
ALTER TABLE public.bets RENAME COLUMN evidence_url TO evidence_ref;
-- Los registros previos usaban odds=1 por defecto de plataforma; no fue una propuesta del usuario.
UPDATE public.bets SET odds = NULL;
ALTER TABLE public.bets
    ADD COLUMN evidence_text text,
    ADD COLUMN accepted_at timestamptz,
    ADD COLUMN decision_by uuid REFERENCES public.users(id),
    ADD COLUMN decision_note text,
    ADD CONSTRAINT bets_stake_currency_cup CHECK (stake_currency = 'CUP'),
    ADD CONSTRAINT bets_odds_positive CHECK (
        odds IS NULL OR (odds > 0 AND odds < 'Infinity'::numeric AND odds <> 'NaN'::numeric)
    ),
    ADD CONSTRAINT bets_evidence_text_length CHECK (evidence_text IS NULL OR char_length(evidence_text) <= 2000),
    ADD CONSTRAINT bets_decision_note_length CHECK (decision_note IS NULL OR char_length(decision_note) <= 2000);

UPDATE public.bets SET accepted_at = COALESCE(settled_at, created_at) WHERE acceptor_id IS NOT NULL;
ALTER TABLE public.bets
    ADD CONSTRAINT bets_acceptor_time_pair CHECK ((acceptor_id IS NULL) = (accepted_at IS NULL));

ALTER TABLE public.betting_groups
    ADD CONSTRAINT betting_group_name_length CHECK (char_length(btrim(name)) BETWEEN 1 AND 80),
    ADD CONSTRAINT betting_group_topic_length CHECK (topic IS NULL OR char_length(topic) <= 200);

CREATE TABLE public.betting_controls (
    user_id uuid PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    daily_limit_cup numeric NOT NULL DEFAULT 1000
        CHECK (daily_limit_cup >= 1 AND daily_limit_cup <= 1000000
            AND daily_limit_cup < 'Infinity'::numeric AND daily_limit_cup <> 'NaN'::numeric),
    self_excluded_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Inmutable timeline: aceptaciones, declaraciones de resultado, evidencia,
-- desacuerdos y decisiones quedan conservados aunque cambie el estado actual.
CREATE TABLE public.bet_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bet_id uuid NOT NULL REFERENCES public.bets(id) ON DELETE CASCADE,
    actor_id uuid NOT NULL REFERENCES public.users(id),
    event_type text NOT NULL CHECK (event_type IN (
        'created', 'accepted', 'settlement_claim', 'settlement_confirmed', 'disputed', 'admin_decision'
    )),
    outcome text CHECK (outcome IS NULL OR outcome IN ('creator', 'acceptor', 'void')),
    evidence_text text,
    evidence_ref text,
    decision_note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (evidence_text IS NULL OR char_length(evidence_text) <= 2000),
    CHECK (decision_note IS NULL OR char_length(decision_note) <= 2000),
    CHECK (event_type NOT IN ('settlement_claim', 'settlement_confirmed', 'disputed')
        OR NULLIF(btrim(evidence_text), '') IS NOT NULL OR evidence_ref IS NOT NULL),
    CHECK (event_type <> 'admin_decision' OR NULLIF(btrim(decision_note), '') IS NOT NULL)
);

CREATE INDEX betting_controls_exclusion_idx ON public.betting_controls(self_excluded_until)
    WHERE self_excluded_until IS NOT NULL;
CREATE INDEX bets_created_day_idx ON public.bets(creator_id, created_at DESC);
CREATE INDEX bets_accepted_day_idx ON public.bets(acceptor_id, accepted_at DESC);
CREATE INDEX bet_events_history_idx ON public.bet_events(bet_id, created_at, id);

-- Cada usuario tiene controles por defecto, también los usuarios que ya existían.
INSERT INTO public.betting_controls(user_id)
SELECT id FROM public.users ON CONFLICT (user_id) DO NOTHING;

CREATE FUNCTION public.create_betting_controls_for_user() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.betting_controls(user_id) VALUES (NEW.id)
    ON CONFLICT (user_id) DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER users_create_betting_controls
AFTER INSERT ON public.users
FOR EACH ROW EXECUTE FUNCTION public.create_betting_controls_for_user();

-- ---------------------------------------------------------------------------
-- 2. Lectura y evidencia: miembros/participantes, evidencias privadas.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.can_read_bet(p_bet_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.bets b
        WHERE b.id = p_bet_id
          AND (
              b.creator_id = (SELECT auth.uid())
              OR b.acceptor_id = (SELECT auth.uid())
              OR public.is_group_member(b.group_id)
          )
    )
$$;

CREATE FUNCTION public.can_read_bet_evidence(p_name text) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    folders text[];
    evidence_bet_id uuid;
    evidence_group_id uuid;
BEGIN
    folders := storage.foldername(p_name);
    IF array_length(folders, 1) <> 3
       OR folders[2] !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
       OR folders[3] !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN
        RETURN false;
    END IF;
    evidence_group_id := folders[2]::uuid;
    evidence_bet_id := folders[3]::uuid;
    RETURN EXISTS (
        SELECT 1 FROM public.bets b
        WHERE b.id = evidence_bet_id
          AND b.group_id = evidence_group_id
          AND public.can_read_bet(b.id)
    );
END;
$$;

ALTER TABLE public.betting_controls ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bet_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY betting_controls_read_self ON public.betting_controls FOR SELECT TO authenticated
    USING (user_id = (SELECT auth.uid()));
CREATE POLICY bet_events_read ON public.bet_events FOR SELECT TO authenticated
    USING (public.can_read_bet(bet_id));

-- El cliente solo sube evidencias en una ruta propia y solo para una apuesta
-- que puede leer. Se firma URL temporalmente en cliente; el bucket nunca es público.
INSERT INTO storage.buckets (id, name, public)
VALUES ('bet-evidence', 'bet-evidence', false)
ON CONFLICT (id) DO NOTHING;

CREATE POLICY bet_evidence_insert ON storage.objects FOR INSERT TO authenticated
WITH CHECK (
    bucket_id = 'bet-evidence'
    AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
    AND public.can_read_bet_evidence(name)
);
CREATE POLICY bet_evidence_read ON storage.objects FOR SELECT TO authenticated
USING (bucket_id = 'bet-evidence' AND public.can_read_bet_evidence(name));
-- No client DELETE policy: after an event records a capture, its source stays available.
-- Unattached uploads are reclaimed only by a trusted retention job.
GRANT SELECT, INSERT ON storage.objects TO authenticated;

-- Las filas de historial son append-only, incluyendo para service_role.
CREATE FUNCTION public.bet_events_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    RAISE EXCEPTION 'bet_events is append-only' USING ERRCODE = 'P0001';
END;
$$;
CREATE TRIGGER bet_events_immutable
BEFORE UPDATE OR DELETE ON public.bet_events
FOR EACH ROW EXECUTE FUNCTION public.bet_events_immutable();

-- ---------------------------------------------------------------------------
-- 3. Reglas privadas usadas por RPCs: identidad + carnet 18+ + autoexclusión,
--    límite diario serializado por usuario (día UTC).
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.assert_betting_eligible(p_user_id uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    excluded_until timestamptz;
BEGIN
    IF p_user_id IS NULL OR p_user_id IS DISTINCT FROM (SELECT auth.uid()) THEN
        RAISE EXCEPTION 'La sesión no puede apostar en nombre de otro usuario' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM public.users u
        WHERE u.id = p_user_id
          AND u.status = 'active'
          AND u.verification_level = 'identity'
    ) OR NOT EXISTS (
        SELECT 1
        FROM public.identity_verifications iv
        WHERE iv.user_id = p_user_id
          AND iv.doc_type = 'carnet'
          AND iv.status = 'approved'
          AND iv.verified_by IS NOT NULL
          AND iv.verified_at IS NOT NULL
          AND iv.birthdate <= (((now() AT TIME ZONE 'UTC')::date - interval '18 years')::date)
    ) THEN
        RAISE EXCEPTION 'Para apostar necesitas identidad aprobada y tener al menos 18 años según el carnet' USING ERRCODE = '42501';
    END IF;

    INSERT INTO public.betting_controls(user_id) VALUES (p_user_id)
    ON CONFLICT (user_id) DO NOTHING;
    SELECT bc.self_excluded_until INTO excluded_until
    FROM public.betting_controls bc
    WHERE bc.user_id = p_user_id
    FOR UPDATE;
    IF excluded_until IS NOT NULL AND excluded_until > now() THEN
        RAISE EXCEPTION 'La autoexclusión de 30 días está activa' USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE FUNCTION public.enforce_daily_betting_limit(p_user_id uuid, p_stake numeric) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_limit numeric;
    v_used numeric;
    v_day_start timestamptz;
BEGIN
    SELECT daily_limit_cup INTO v_limit
    FROM public.betting_controls
    WHERE user_id = p_user_id;

    v_day_start := date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    SELECT COALESCE(SUM(b.stake_amount), 0) INTO v_used
    FROM public.bets b
    WHERE (b.creator_id = p_user_id
           AND b.created_at >= v_day_start
           AND b.created_at < v_day_start + interval '1 day')
       OR (b.acceptor_id = p_user_id
           AND b.accepted_at >= v_day_start
           AND b.accepted_at < v_day_start + interval '1 day');

    IF v_used + p_stake > v_limit THEN
        RAISE EXCEPTION 'El límite diario de % CUP se alcanzaría; ya registras % CUP hoy', v_limit, v_used
            USING ERRCODE = '22003';
    END IF;
END;
$$;

-- ---------------------------------------------------------------------------
-- 4. Invitaciones, alta voluntaria en grupos públicos y expulsión por owner.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.join_public_betting_group(p_group_id uuid)
RETURNS public.betting_group_members
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_group public.betting_groups%ROWTYPE;
    v_member public.betting_group_members%ROWTYPE;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para unirte' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO v_group FROM public.betting_groups WHERE id = p_group_id AND status = 'active';
    IF v_group.id IS NULL OR v_group.visibility <> 'public' THEN
        RAISE EXCEPTION 'El grupo no está disponible para unirse' USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.betting_group_members(group_id, user_id)
    VALUES (p_group_id, v_user_id)
    ON CONFLICT (group_id, user_id) DO NOTHING;
    SELECT * INTO v_member FROM public.betting_group_members
    WHERE group_id = p_group_id AND user_id = v_user_id;
    RETURN v_member;
END;
$$;

CREATE FUNCTION public.invite_betting_group_member(p_group_id uuid, p_phone text)
RETURNS public.betting_group_members
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_owner_id uuid := (SELECT auth.uid());
    v_member_id uuid;
    v_member public.betting_group_members%ROWTYPE;
    v_digits text;
BEGIN
    IF NOT public.owns_group(p_group_id) THEN
        RAISE EXCEPTION 'Solo el administrador del grupo puede invitar' USING ERRCODE = '42501';
    END IF;
    v_digits := regexp_replace(COALESCE(p_phone, ''), '[^0-9]', '', 'g');
    IF char_length(v_digits) < 7 OR char_length(v_digits) > 15 THEN
        RAISE EXCEPTION 'No se pudo invitar a ese número' USING ERRCODE = '22023';
    END IF;
    -- Resolve only a phone confirmed by Supabase Auth; public.users.phone is editable contact data.
    SELECT u.id INTO v_member_id
    FROM auth.users u
    WHERE regexp_replace(COALESCE(u.phone, ''), '[^0-9]', '', 'g') = v_digits
      AND u.phone_confirmed_at IS NOT NULL
      AND u.id <> v_owner_id
    LIMIT 1;
    IF v_member_id IS NULL THEN
        RAISE EXCEPTION 'No se pudo invitar a ese número' USING ERRCODE = 'P0002';
    END IF;
    INSERT INTO public.betting_group_members(group_id, user_id)
    VALUES (p_group_id, v_member_id)
    ON CONFLICT (group_id, user_id) DO NOTHING;
    SELECT * INTO v_member FROM public.betting_group_members
    WHERE group_id = p_group_id AND user_id = v_member_id;
    RETURN v_member;
END;
$$;

CREATE FUNCTION public.remove_betting_group_member(p_group_id uuid, p_user_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NOT public.owns_group(p_group_id) THEN
        RAISE EXCEPTION 'Solo el administrador del grupo puede expulsar miembros' USING ERRCODE = '42501';
    END IF;
    IF p_user_id = (SELECT auth.uid()) THEN
        RAISE EXCEPTION 'El administrador no puede expulsarse a sí mismo' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.betting_group_members
    WHERE group_id = p_group_id AND user_id = p_user_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Miembro no encontrado' USING ERRCODE = 'P0002';
    END IF;
END;
$$;

-- ---------------------------------------------------------------------------
-- 5. Apuestas: crear, aceptar, declarar, disputar y decidir, todo atómico.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.create_bet(
    p_group_id uuid,
    p_description text,
    p_stake_amount numeric,
    p_odds numeric DEFAULT NULL
) RETURNS public.bets
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_bet public.bets%ROWTYPE;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para crear una apuesta' USING ERRCODE = '42501';
    END IF;
    IF p_description IS NULL OR char_length(btrim(p_description)) < 5 OR char_length(btrim(p_description)) > 500 THEN
        RAISE EXCEPTION 'La descripción debe tener entre 5 y 500 caracteres' USING ERRCODE = '22023';
    END IF;
    IF p_stake_amount IS NULL OR p_stake_amount <= 0 OR p_stake_amount >= 'Infinity'::numeric
       OR p_stake_amount = 'NaN'::numeric OR scale(p_stake_amount) > 2 THEN
        RAISE EXCEPTION 'El monto en CUP debe ser positivo y admitir como máximo 2 decimales' USING ERRCODE = '22023';
    END IF;
    IF p_odds IS NOT NULL AND (p_odds <= 0 OR p_odds >= 'Infinity'::numeric OR p_odds = 'NaN'::numeric OR scale(p_odds) > 4) THEN
        RAISE EXCEPTION 'La cuota propuesta debe ser positiva y admitir como máximo 4 decimales' USING ERRCODE = '22023';
    END IF;
    IF NOT public.is_group_member(p_group_id)
       OR NOT EXISTS (SELECT 1 FROM public.betting_groups g WHERE g.id = p_group_id AND g.status = 'active') THEN
        RAISE EXCEPTION 'Debes ser miembro de un grupo activo para crear una apuesta' USING ERRCODE = '42501';
    END IF;

    PERFORM public.assert_betting_eligible(v_user_id);
    PERFORM public.enforce_daily_betting_limit(v_user_id, p_stake_amount);

    INSERT INTO public.bets(group_id, creator_id, description, stake_amount, stake_currency, odds)
    VALUES (p_group_id, v_user_id, btrim(p_description), p_stake_amount, 'CUP', p_odds)
    RETURNING * INTO v_bet;
    INSERT INTO public.bet_events(bet_id, actor_id, event_type)
    VALUES (v_bet.id, v_user_id, 'created');
    RETURN v_bet;
END;
$$;

CREATE FUNCTION public.accept_bet(p_bet_id uuid)
RETURNS public.bets
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_bet public.bets%ROWTYPE;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para aceptar una apuesta' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO v_bet FROM public.bets WHERE id = p_bet_id FOR UPDATE;
    IF v_bet.id IS NULL OR v_bet.status <> 'open' OR v_bet.creator_id = v_user_id THEN
        RAISE EXCEPTION 'La apuesta ya no está disponible' USING ERRCODE = 'P0002';
    END IF;
    IF NOT public.is_group_member(v_bet.group_id) THEN
        RAISE EXCEPTION 'Solo un miembro del grupo puede aceptar' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.betting_groups g
        WHERE g.id = v_bet.group_id AND g.status = 'active'
    ) THEN
        RAISE EXCEPTION 'El grupo no está activo para aceptar apuestas' USING ERRCODE = '42501';
    END IF;

    PERFORM public.assert_betting_eligible(v_user_id);
    PERFORM public.enforce_daily_betting_limit(v_user_id, v_bet.stake_amount);

    UPDATE public.bets
    SET acceptor_id = v_user_id, accepted_at = now(), status = 'accepted'
    WHERE id = v_bet.id
    RETURNING * INTO v_bet;
    INSERT INTO public.bet_events(bet_id, actor_id, event_type)
    VALUES (v_bet.id, v_user_id, 'accepted');
    RETURN v_bet;
END;
$$;

CREATE FUNCTION public.assert_bet_evidence(
    p_bet public.bets,
    p_evidence_text text,
    p_evidence_ref text
) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_text text := NULLIF(btrim(p_evidence_text), '');
BEGIN
    IF (v_text IS NULL AND p_evidence_ref IS NULL) OR char_length(COALESCE(v_text, '')) > 2000 THEN
        RAISE EXCEPTION 'La resolución requiere texto o una captura como evidencia' USING ERRCODE = '22023';
    END IF;
    IF p_evidence_ref IS NOT NULL AND (
        p_evidence_ref NOT LIKE (SELECT auth.uid())::text || '/' || p_bet.group_id::text || '/' || p_bet.id::text || '/%'
        OR NOT EXISTS (
            SELECT 1 FROM storage.objects o
            WHERE o.bucket_id = 'bet-evidence' AND o.name = p_evidence_ref
        )
    ) THEN
        RAISE EXCEPTION 'La captura adjunta no pertenece a esta apuesta' USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE FUNCTION public.submit_bet_result(
    p_bet_id uuid,
    p_outcome text,
    p_evidence_text text DEFAULT NULL,
    p_evidence_ref text DEFAULT NULL
) RETURNS public.bets
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_bet public.bets%ROWTYPE;
    v_existing_outcome text;
    v_event_type text;
BEGIN
    SELECT * INTO v_bet FROM public.bets WHERE id = p_bet_id FOR UPDATE;
    IF v_bet.id IS NULL OR v_user_id IS NULL
       OR (v_user_id <> v_bet.creator_id AND v_user_id IS DISTINCT FROM v_bet.acceptor_id) THEN
        RAISE EXCEPTION 'Solo quienes participan pueden registrar un resultado' USING ERRCODE = '42501';
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('creator', 'acceptor') OR v_bet.acceptor_id IS NULL THEN
        RAISE EXCEPTION 'Selecciona al creador o al aceptante como resultado' USING ERRCODE = '22023';
    END IF;
    IF v_bet.status NOT IN ('accepted', 'settled_creator', 'settled_acceptor') THEN
        RAISE EXCEPTION 'La apuesta no admite un resultado en su estado actual' USING ERRCODE = '42501';
    END IF;
    PERFORM public.assert_bet_evidence(v_bet, p_evidence_text, p_evidence_ref);

    IF v_bet.status = 'accepted' THEN
        UPDATE public.bets
        SET status = CASE WHEN p_outcome = 'creator' THEN 'settled_creator'::public.bet_status ELSE 'settled_acceptor'::public.bet_status END,
            settled_by = v_user_id,
            settled_at = now(),
            evidence_text = NULLIF(btrim(p_evidence_text), ''),
            evidence_ref = p_evidence_ref
        WHERE id = v_bet.id
        RETURNING * INTO v_bet;
        v_event_type := 'settlement_claim';
    ELSE
        IF EXISTS (
            SELECT 1 FROM public.bet_events e
            WHERE e.bet_id = v_bet.id AND e.event_type IN ('settlement_confirmed', 'admin_decision')
        ) THEN
            RAISE EXCEPTION 'El resultado ya fue confirmado o decidido de forma definitiva' USING ERRCODE = '42501';
        END IF;
        IF v_bet.settled_by = v_user_id THEN
            RAISE EXCEPTION 'Ya registraste tu resultado; espera la respuesta de la otra persona' USING ERRCODE = '42501';
        END IF;
        v_existing_outcome := CASE WHEN v_bet.status = 'settled_creator' THEN 'creator' ELSE 'acceptor' END;
        IF p_outcome = v_existing_outcome THEN
            v_event_type := 'settlement_confirmed';
        ELSE
            UPDATE public.bets SET status = 'disputed' WHERE id = v_bet.id RETURNING * INTO v_bet;
            v_event_type := 'disputed';
        END IF;
    END IF;

    INSERT INTO public.bet_events(bet_id, actor_id, event_type, outcome, evidence_text, evidence_ref)
    VALUES (v_bet.id, v_user_id, v_event_type, p_outcome, NULLIF(btrim(p_evidence_text), ''), p_evidence_ref);
    RETURN v_bet;
END;
$$;

CREATE FUNCTION public.open_bet_dispute(
    p_bet_id uuid,
    p_evidence_text text DEFAULT NULL,
    p_evidence_ref text DEFAULT NULL
) RETURNS public.bets
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_bet public.bets%ROWTYPE;
BEGIN
    SELECT * INTO v_bet FROM public.bets WHERE id = p_bet_id FOR UPDATE;
    IF v_bet.id IS NULL OR v_user_id IS NULL
       OR (v_user_id <> v_bet.creator_id AND v_user_id IS DISTINCT FROM v_bet.acceptor_id) THEN
        RAISE EXCEPTION 'Solo quienes participan pueden abrir una disputa' USING ERRCODE = '42501';
    END IF;
    IF v_bet.status NOT IN ('accepted', 'settled_creator', 'settled_acceptor') THEN
        RAISE EXCEPTION 'La apuesta no se puede disputar en su estado actual' USING ERRCODE = '42501';
    END IF;
    IF v_bet.status IN ('settled_creator', 'settled_acceptor') AND EXISTS (
        SELECT 1 FROM public.bet_events e
        WHERE e.bet_id = v_bet.id AND e.event_type IN ('settlement_confirmed', 'admin_decision')
    ) THEN
        RAISE EXCEPTION 'El resultado ya fue confirmado o decidido de forma definitiva' USING ERRCODE = '42501';
    END IF;
    IF v_bet.status IN ('settled_creator', 'settled_acceptor') AND v_bet.settled_by = v_user_id THEN
        RAISE EXCEPTION 'La otra persona debe responder a tu resultado antes de abrir una disputa' USING ERRCODE = '42501';
    END IF;
    PERFORM public.assert_bet_evidence(v_bet, p_evidence_text, p_evidence_ref);

    UPDATE public.bets SET status = 'disputed' WHERE id = v_bet.id RETURNING * INTO v_bet;
    INSERT INTO public.bet_events(bet_id, actor_id, event_type, evidence_text, evidence_ref)
    VALUES (v_bet.id, v_user_id, 'disputed', NULLIF(btrim(p_evidence_text), ''), p_evidence_ref);
    RETURN v_bet;
END;
$$;

CREATE FUNCTION public.resolve_bet_dispute(
    p_bet_id uuid,
    p_outcome text,
    p_decision_note text
) RETURNS public.bets
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_bet public.bets%ROWTYPE;
BEGIN
    SELECT * INTO v_bet FROM public.bets WHERE id = p_bet_id FOR UPDATE;
    IF v_bet.id IS NULL OR NOT public.owns_group(v_bet.group_id) THEN
        RAISE EXCEPTION 'Solo el administrador del grupo puede decidir la disputa' USING ERRCODE = '42501';
    END IF;
    IF v_bet.status <> 'disputed' THEN
        RAISE EXCEPTION 'La apuesta no está en disputa' USING ERRCODE = '42501';
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('creator', 'acceptor', 'void')
       OR char_length(btrim(COALESCE(p_decision_note, ''))) = 0
       OR char_length(p_decision_note) > 2000 THEN
        RAISE EXCEPTION 'La decisión requiere un resultado y una explicación' USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'acceptor' AND v_bet.acceptor_id IS NULL THEN
        RAISE EXCEPTION 'La apuesta no tiene aceptante' USING ERRCODE = '22023';
    END IF;

    UPDATE public.bets
    SET status = CASE p_outcome
            WHEN 'creator' THEN 'settled_creator'::public.bet_status
            WHEN 'acceptor' THEN 'settled_acceptor'::public.bet_status
            ELSE 'void'::public.bet_status
        END,
        settled_by = v_user_id,
        settled_at = now(),
        evidence_text = NULL,
        evidence_ref = NULL,
        decision_by = v_user_id,
        decision_note = btrim(p_decision_note)
    WHERE id = v_bet.id
    RETURNING * INTO v_bet;

    INSERT INTO public.bet_events(bet_id, actor_id, event_type, outcome, decision_note)
    VALUES (v_bet.id, v_user_id, 'admin_decision', p_outcome, btrim(p_decision_note));
    RETURN v_bet;
END;
$$;

CREATE FUNCTION public.update_betting_daily_limit(p_daily_limit_cup numeric)
RETURNS public.betting_controls
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_control public.betting_controls%ROWTYPE;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para cambiar el límite' USING ERRCODE = '42501';
    END IF;
    IF p_daily_limit_cup IS NULL OR p_daily_limit_cup < 1 OR p_daily_limit_cup > 1000000
       OR p_daily_limit_cup >= 'Infinity'::numeric OR p_daily_limit_cup = 'NaN'::numeric OR scale(p_daily_limit_cup) > 2 THEN
        RAISE EXCEPTION 'El límite debe estar entre 1 y 1 000 000 CUP, con hasta 2 decimales' USING ERRCODE = '22023';
    END IF;
    INSERT INTO public.betting_controls(user_id) VALUES (v_user_id) ON CONFLICT (user_id) DO NOTHING;
    UPDATE public.betting_controls
    SET daily_limit_cup = p_daily_limit_cup, updated_at = now()
    WHERE user_id = v_user_id
    RETURNING * INTO v_control;
    RETURN v_control;
END;
$$;

CREATE FUNCTION public.self_exclude_betting()
RETURNS public.betting_controls
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := (SELECT auth.uid());
    v_control public.betting_controls%ROWTYPE;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para autoexcluirte' USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.betting_controls(user_id) VALUES (v_user_id) ON CONFLICT (user_id) DO NOTHING;
    SELECT * INTO v_control FROM public.betting_controls WHERE user_id = v_user_id FOR UPDATE;
    IF v_control.self_excluded_until IS NOT NULL AND v_control.self_excluded_until > now() THEN
        RAISE EXCEPTION 'La autoexclusión ya está activa' USING ERRCODE = '42501';
    END IF;
    UPDATE public.betting_controls
    SET self_excluded_until = now() + interval '30 days', updated_at = now()
    WHERE user_id = v_user_id
    RETURNING * INTO v_control;
    RETURN v_control;
END;
$$;

-- ---------------------------------------------------------------------------
-- 6. RLS y privilegios: no hay escrituras directas de estados o membresías.
-- ---------------------------------------------------------------------------
DROP POLICY bets_insert ON public.bets;
DROP POLICY bets_read ON public.bets;
DROP POLICY members_insert ON public.betting_group_members;
DROP POLICY members_delete ON public.betting_group_members;

CREATE POLICY bets_read ON public.bets FOR SELECT TO authenticated
USING (
    public.is_group_member(group_id)
    OR creator_id = (SELECT auth.uid())
    OR acceptor_id = (SELECT auth.uid())
);

REVOKE INSERT, UPDATE, DELETE ON public.bets FROM authenticated;
REVOKE INSERT, DELETE ON public.betting_group_members FROM authenticated;
REVOKE ALL ON public.betting_controls, public.bet_events FROM anon, authenticated;
GRANT SELECT ON public.betting_controls, public.bet_events TO authenticated;
GRANT SELECT, INSERT ON public.betting_groups TO authenticated;
GRANT SELECT ON public.betting_group_members, public.bets TO authenticated;

REVOKE ALL ON public.betting_controls, public.bet_events FROM service_role;
GRANT SELECT, INSERT, UPDATE ON public.betting_controls TO service_role;
GRANT SELECT, INSERT ON public.bet_events TO service_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.bets, public.betting_group_members TO service_role;

REVOKE ALL ON FUNCTION public.can_read_bet(uuid), public.can_read_bet_evidence(text),
    public.create_betting_controls_for_user(), public.assert_betting_eligible(uuid),
    public.enforce_daily_betting_limit(uuid, numeric), public.assert_bet_evidence(public.bets, text, text),
    public.bet_events_immutable() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.can_read_bet(uuid), public.can_read_bet_evidence(text) TO authenticated;

REVOKE ALL ON FUNCTION public.join_public_betting_group(uuid),
    public.invite_betting_group_member(uuid, text), public.remove_betting_group_member(uuid, uuid),
    public.create_bet(uuid, text, numeric, numeric), public.accept_bet(uuid),
    public.submit_bet_result(uuid, text, text, text), public.open_bet_dispute(uuid, text, text),
    public.resolve_bet_dispute(uuid, text, text), public.update_betting_daily_limit(numeric),
    public.self_exclude_betting() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.join_public_betting_group(uuid),
    public.invite_betting_group_member(uuid, text), public.remove_betting_group_member(uuid, uuid),
    public.create_bet(uuid, text, numeric, numeric), public.accept_bet(uuid),
    public.submit_bet_result(uuid, text, text, text), public.open_bet_dispute(uuid, text, text),
    public.resolve_bet_dispute(uuid, text, text), public.update_betting_daily_limit(numeric),
    public.self_exclude_betting() TO authenticated;

COMMIT;

-- Monetización de listings. Aplicar después de 005_peer_betting.sql.
-- El nuevo enum value se confirma antes de usarlo en funciones (migración 007).
ALTER TYPE public.payment_status ADD VALUE IF NOT EXISTS 'cancelled';

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Catálogo y cuenta destino. No se activan tarifas de ejemplo:
--    el catálogo y la cuenta se habilitan solo después de la configuración del negocio.
-- ---------------------------------------------------------------------------
CREATE TABLE public.monetization_products (
    code text PRIMARY KEY CHECK (code ~ '^[a-z0-9_]{3,64}$'),
    product_type public.product_type NOT NULL,
    tier text NOT NULL,
    title text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 100),
    description text NOT NULL CHECK (char_length(description) <= 500),
    amount numeric NOT NULL DEFAULT 0 CHECK (amount >= 0 AND amount < 'Infinity'::numeric AND amount <> 'NaN'::numeric),
    currency text NOT NULL DEFAULT 'CUP' CHECK (currency = 'CUP'),
    duration_hours int NOT NULL CHECK (duration_hours BETWEEN 1 AND 8760),
    reach_scope text CHECK (reach_scope IS NULL OR reach_scope IN ('municipality', 'province', 'national')),
    requires_municipality boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT false,
    sort_order int NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid REFERENCES public.users(id),
    CHECK (NOT is_active OR amount > 0),
    CHECK ((product_type = 'reach') = (reach_scope IS NOT NULL)),
    CHECK (product_type <> 'feature' OR requires_municipality)
);
ALTER TABLE public.monetization_products ENABLE ROW LEVEL SECURITY;

-- Catálogo sin tarifas de referencia: amount=0 + is_active=false hasta configurar precios reales.
INSERT INTO public.monetization_products
    (code, product_type, tier, title, description, amount, duration_hours, reach_scope,
     requires_municipality, sort_order, is_active)
VALUES
    ('boost_24h', 'boost', '24h', 'Boost por 24 horas',
     'Mejora el posicionamiento de la publicación durante un día.', 0, 24, NULL, false, 10, false),
    ('boost_7d', 'boost', '7d', 'Boost por 7 días',
     'Mejora el posicionamiento de la publicación durante una semana.', 0, 168, NULL, false, 20, false),
    ('feature_category_municipality', 'feature', 'category_municipality', 'Destacado en categoría y municipio',
     'Espacio promocionado, como máximo 1 de cada 5 posiciones, durante 7 días.', 0, 168, NULL, true, 30, false),
    ('reach_municipality', 'reach', 'municipality', 'Más alcance: municipio',
     'Muestra la publicación a usuarios del municipio durante 7 días.', 0, 168, 'municipality', true, 40, false),
    ('reach_province', 'reach', 'province', 'Más alcance: provincia',
     'Amplía la visibilidad a toda la provincia durante 7 días.', 0, 168, 'province', true, 50, false),
    ('reach_national', 'reach', 'national', 'Más alcance: nacional',
     'Amplía la visibilidad a todo el país durante 7 días.', 0, 168, 'national', true, 60, false);

CREATE TABLE public.monetization_settings (
    id smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    transfer_account text,
    account_holder text,
    payment_window_hours int NOT NULL DEFAULT 24 CHECK (payment_window_hours = 24),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid REFERENCES public.users(id),
    CHECK (transfer_account IS NULL OR transfer_account ~ '^[0-9]{8,20}$'),
    CHECK (account_holder IS NULL OR char_length(btrim(account_holder)) <= 100)
);
ALTER TABLE public.monetization_settings ENABLE ROW LEVEL SECURITY;
INSERT INTO public.monetization_settings(id, transfer_account, account_holder)
VALUES (1, NULL, NULL);

-- ---------------------------------------------------------------------------
-- 2. Evidencia y conciliación: hashes no reutilizables, blacklist y bitácora.
-- ---------------------------------------------------------------------------
ALTER TABLE public.payments
    ADD COLUMN currency text NOT NULL DEFAULT 'CUP' CHECK (currency = 'CUP'),
    ADD COLUMN product_code text REFERENCES public.monetization_products(code),
    ADD COLUMN product_tier text,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN expires_at timestamptz,
    ADD COLUMN transfer_last4 text,
    ADD COLUMN transfer_at timestamptz,
    ADD COLUMN submitted_at timestamptz,
    ADD COLUMN receipt_sha256 text,
    ADD COLUMN rejection_reason text,
    ADD COLUMN listing_status_before_review public.listing_status,
    ADD COLUMN reviewed_by uuid REFERENCES public.users(id),
    ADD COLUMN reviewed_at timestamptz,
    ADD COLUMN reconciliation_amount numeric,
    ADD COLUMN reconciliation_transfer_at timestamptz,
    ADD COLUMN reconciliation_last4 text,
    ADD COLUMN reconciliation_reference text,
    ADD CONSTRAINT payments_transfer_last4_check
        CHECK (transfer_last4 IS NULL OR transfer_last4 ~ '^[0-9]{4}$'),
    ADD CONSTRAINT payments_reconciliation_last4_check
        CHECK (reconciliation_last4 IS NULL OR reconciliation_last4 ~ '^[0-9]{4}$'),
    ADD CONSTRAINT payments_receipt_sha256_check
        CHECK (receipt_sha256 IS NULL OR receipt_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT payments_rejection_reason_length
        CHECK (rejection_reason IS NULL OR char_length(rejection_reason) BETWEEN 1 AND 500),
    ADD CONSTRAINT payments_reviewed_pair_check
        CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)),
    ADD CONSTRAINT payments_reconciliation_amount_check
        CHECK (reconciliation_amount IS NULL OR
            (reconciliation_amount > 0 AND reconciliation_amount < 'Infinity'::numeric AND reconciliation_amount <> 'NaN'::numeric));

-- Las órdenes antiguas no se usaban para la nueva conciliación, pero reciben expiración coherente.
UPDATE public.payments
SET expires_at = created_at + interval '24 hours'
WHERE expires_at IS NULL;

CREATE UNIQUE INDEX payments_code_unique ON public.payments(code) WHERE code IS NOT NULL;
CREATE UNIQUE INDEX payments_receipt_hash_unique ON public.payments(receipt_sha256) WHERE receipt_sha256 IS NOT NULL;
CREATE UNIQUE INDEX payments_reconciliation_reference_unique
    ON public.payments(reconciliation_reference)
    WHERE reconciliation_reference IS NOT NULL AND status = 'confirmed';
CREATE INDEX payments_review_queue_idx ON public.payments(submitted_at, created_at)
    WHERE status = 'review';
CREATE INDEX payments_target_history_idx ON public.payments(target_id, created_at DESC);

ALTER TABLE public.boosts
    ADD COLUMN product_type public.product_type NOT NULL DEFAULT 'boost',
    ADD COLUMN reach_scope text CHECK (reach_scope IS NULL OR reach_scope IN ('municipality', 'province', 'national'));
CREATE INDEX boosts_active_listing_idx ON public.boosts(listing_id, ends_at DESC);

CREATE TABLE public.payment_receipt_blacklist (
    receipt_sha256 text PRIMARY KEY CHECK (receipt_sha256 ~ '^[0-9a-f]{64}$'),
    reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    payment_id uuid REFERENCES public.payments(id),
    created_by uuid NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.payment_receipt_blacklist ENABLE ROW LEVEL SECURITY;

CREATE TABLE public.payment_reconciliation_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid NOT NULL REFERENCES public.payments(id),
    user_id uuid NOT NULL REFERENCES public.users(id),
    actor_id uuid REFERENCES public.users(id),
    event_type text NOT NULL CHECK (event_type IN ('submitted', 'confirmed', 'rejected', 'cancelled')),
    amount numeric,
    transfer_at timestamptz,
    reference text,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (amount IS NULL OR (amount > 0 AND amount < 'Infinity'::numeric AND amount <> 'NaN'::numeric)),
    CHECK (reason IS NULL OR char_length(reason) <= 500)
);
ALTER TABLE public.payment_reconciliation_events ENABLE ROW LEVEL SECURITY;
CREATE INDEX payment_reconciliation_history_idx
    ON public.payment_reconciliation_events(payment_id, created_at, id);

CREATE FUNCTION public.payment_reconciliation_events_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    RAISE EXCEPTION 'payment reconciliation events are append-only' USING ERRCODE = 'P0001';
END;
$$;
CREATE TRIGGER payment_reconciliation_events_immutable
BEFORE UPDATE OR DELETE ON public.payment_reconciliation_events
FOR EACH ROW EXECUTE FUNCTION public.payment_reconciliation_events_immutable();

-- ---------------------------------------------------------------------------
-- 3. Políticas y grants: operaciones de dinero solo por RPC SECURITY DEFINER.
-- ---------------------------------------------------------------------------
CREATE POLICY monetization_products_read ON public.monetization_products FOR SELECT TO authenticated
    USING (is_active OR public.is_admin());
CREATE POLICY monetization_settings_read ON public.monetization_settings FOR SELECT TO authenticated
    USING (true);
CREATE POLICY payment_receipt_blacklist_admin_read ON public.payment_receipt_blacklist FOR SELECT TO authenticated
    USING (public.is_admin());
CREATE POLICY payment_reconciliation_events_read ON public.payment_reconciliation_events FOR SELECT TO authenticated
    USING (user_id = (SELECT auth.uid()) OR public.is_admin());

DROP POLICY IF EXISTS payments_insert ON public.payments;
REVOKE INSERT, UPDATE, DELETE ON public.payments FROM anon, authenticated;
REVOKE ALL ON public.monetization_products, public.monetization_settings,
    public.payment_receipt_blacklist, public.payment_reconciliation_events FROM anon, authenticated;
GRANT SELECT ON public.payments, public.monetization_products, public.monetization_settings,
    public.payment_receipt_blacklist, public.payment_reconciliation_events TO authenticated;
GRANT ALL ON public.monetization_products, public.monetization_settings,
    public.payment_receipt_blacklist, public.payment_reconciliation_events TO service_role;
REVOKE UPDATE, DELETE ON public.payment_reconciliation_events FROM anon, authenticated, service_role;

INSERT INTO storage.buckets(id, name, public)
VALUES ('payment-proofs', 'payment-proofs', false)
ON CONFLICT (id) DO NOTHING;

CREATE POLICY payment_proofs_insert ON storage.objects FOR INSERT TO authenticated
WITH CHECK (
    bucket_id = 'payment-proofs'
    AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
    AND array_length(storage.foldername(name), 1) = 2
);
CREATE POLICY payment_proofs_read ON storage.objects FOR SELECT TO authenticated
USING (
    bucket_id = 'payment-proofs'
    AND ((storage.foldername(name))[1] = (SELECT auth.uid())::text OR public.is_admin())
);
CREATE POLICY payment_proofs_delete_unreferenced ON storage.objects FOR DELETE TO authenticated
USING (
    bucket_id = 'payment-proofs'
    AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
    AND NOT EXISTS (SELECT 1 FROM public.payments p WHERE p.proof_ref = storage.objects.name)
);

COMMIT;

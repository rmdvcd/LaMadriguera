-- Flujo de órdenes, evidencias y conciliación. Aplicar después de 006.
BEGIN;

-- Admin y cobrador son roles confiables de app_metadata (nunca user_metadata).
CREATE FUNCTION public.is_payment_reviewer() RETURNS boolean
LANGUAGE sql STABLE SET search_path = '' AS $$
    SELECT COALESCE(
        auth.jwt() -> 'app_metadata' ->> 'role' IN ('admin', 'cashier'),
        false
    )
$$;
REVOKE ALL ON FUNCTION public.is_payment_reviewer() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_payment_reviewer() TO authenticated, service_role;

-- El usuario puede expirar únicamente sus órdenes; el barrido global queda reservado al servidor.
CREATE FUNCTION public.expire_my_pending_monetization_payments()
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_payment record;
    v_count integer := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para consultar tus pagos' USING ERRCODE = '42501';
    END IF;
    FOR v_payment IN
        UPDATE public.payments p
        SET status = 'cancelled'
        WHERE p.user_id = v_user_id AND p.status = 'pending' AND p.expires_at <= now()
        RETURNING p.id, p.user_id, p.code
    LOOP
        v_count := v_count + 1;
        INSERT INTO public.payment_reconciliation_events(payment_id, user_id, event_type, reason)
        VALUES (v_payment.id, v_payment.user_id, 'cancelled', 'Venció la ventana de pago de 24 horas');
        INSERT INTO public.user_notifications(user_id, type, title, body)
        VALUES (v_payment.user_id, 'payment_cancelled', 'Pago vencido',
                'La orden ' || COALESCE(v_payment.code, '') || ' venció. Puedes crear una nueva orden.');
    END LOOP;
    RETURN v_count;
END;
$$;

-- La conciliación y las URLs firmadas también están disponibles al rol cobrador.
DROP POLICY payments_read ON public.payments;
CREATE POLICY payments_read ON public.payments FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) OR public.is_payment_reviewer());
DROP POLICY monetization_products_read ON public.monetization_products;
CREATE POLICY monetization_products_read ON public.monetization_products FOR SELECT TO authenticated
USING (is_active OR public.is_payment_reviewer());
DROP POLICY payment_receipt_blacklist_admin_read ON public.payment_receipt_blacklist;
CREATE POLICY payment_receipt_blacklist_admin_read ON public.payment_receipt_blacklist FOR SELECT TO authenticated
USING (public.is_payment_reviewer());
DROP POLICY payment_reconciliation_events_read ON public.payment_reconciliation_events;
CREATE POLICY payment_reconciliation_events_read ON public.payment_reconciliation_events FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) OR public.is_payment_reviewer());
DROP POLICY payment_proofs_read ON storage.objects;
CREATE POLICY payment_proofs_read ON storage.objects FOR SELECT TO authenticated
USING (
    bucket_id = 'payment-proofs'
    AND ((storage.foldername(name))[1] = (SELECT auth.uid())::text OR public.is_payment_reviewer())
);

-- El código, precio, producto y vencimiento nacen en el servidor: el cliente no puede
-- insertar ni editar payments directamente.
CREATE FUNCTION public.create_monetization_payment(p_listing_id uuid, p_product_code text)
RETURNS SETOF public.payments
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_listing public.listings%ROWTYPE;
    v_product public.monetization_products%ROWTYPE;
    v_settings public.monetization_settings%ROWTYPE;
    v_payment public.payments%ROWTYPE;
    v_code text;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Inicia sesión para crear el pago' USING ERRCODE = '42501';
    END IF;
    PERFORM public.expire_my_pending_monetization_payments();

    SELECT * INTO v_listing
    FROM public.listings
    WHERE id = p_listing_id
    FOR UPDATE;
    IF NOT FOUND OR v_listing.owner_id <> v_user_id THEN
        RAISE EXCEPTION 'La publicación no existe o no te pertenece' USING ERRCODE = '42501';
    END IF;
    IF v_listing.status NOT IN ('active', 'fixed', 'auction_scheduled', 'auction_live') THEN
        RAISE EXCEPTION 'La publicación no está disponible para promocionarse' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO v_product
    FROM public.monetization_products
    WHERE code = p_product_code AND is_active;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'El producto de promoción ya no está disponible' USING ERRCODE = '23514';
    END IF;
    IF v_product.requires_municipality AND v_listing.municipality_id IS NULL THEN
        RAISE EXCEPTION 'Selecciona un municipio en la publicación antes de comprar alcance' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO v_settings FROM public.monetization_settings WHERE id = 1;
    IF NOT FOUND OR NULLIF(btrim(v_settings.transfer_account), '') IS NULL THEN
        RAISE EXCEPTION 'La cuenta Transfermóvil de destino aún no está configurada' USING ERRCODE = '23514';
    END IF;

    -- Vence automáticamente las órdenes pendientes del dueño antes de revisar conflictos.
    IF EXISTS (
        SELECT 1 FROM public.payments
        WHERE target_id = p_listing_id
          AND user_id = v_user_id
          AND status IN ('pending', 'review')
    ) THEN
        RAISE EXCEPTION 'Ya existe un pago abierto para esta publicación' USING ERRCODE = '23505';
    END IF;

    LOOP
        v_code := 'LM-' || substr(upper(replace(gen_random_uuid()::text, '-', '')), 1, 4);
        EXIT WHEN NOT EXISTS (SELECT 1 FROM public.payments WHERE code = v_code);
    END LOOP;

    INSERT INTO public.payments (
        user_id, product_type, target_id, amount, currency, method, code, status,
        product_code, product_tier, created_at, expires_at
    ) VALUES (
        v_user_id, v_product.product_type, p_listing_id, v_product.amount, v_product.currency,
        'transfermovil', v_code, 'pending', v_product.code, v_product.tier,
        now(), now() + make_interval(hours => v_settings.payment_window_hours)
    ) RETURNING * INTO v_payment;

    RETURN NEXT v_payment;
END;
$$;

-- Esta función solo la invoca la Edge Function con service_role. El hash se calcula
-- allí sobre los bytes descargados de Storage; no se acepta un hash calculado por el cliente.
CREATE FUNCTION public.submit_monetization_proof(
    p_user_id uuid,
    p_payment_id uuid,
    p_proof_ref text,
    p_receipt_sha256 text,
    p_transfer_last4 text,
    p_transfer_at timestamptz
)
RETURNS SETOF public.payments
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_payment public.payments%ROWTYPE;
    v_listing public.listings%ROWTYPE;
    v_proof_path_prefix text;
BEGIN
    IF p_user_id IS NULL OR p_payment_id IS NULL THEN
        RAISE EXCEPTION 'Pago no válido' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO v_payment FROM public.payments WHERE id = p_payment_id FOR UPDATE;
    IF NOT FOUND OR v_payment.user_id <> p_user_id THEN
        RAISE EXCEPTION 'Pago no encontrado' USING ERRCODE = '42501';
    END IF;
    IF v_payment.status <> 'pending' THEN
        RAISE EXCEPTION 'El pago ya no está esperando un comprobante' USING ERRCODE = '23514';
    END IF;

    IF v_payment.expires_at IS NULL OR now() >= v_payment.expires_at THEN
        UPDATE public.payments SET status = 'cancelled' WHERE id = p_payment_id RETURNING * INTO v_payment;
        INSERT INTO public.payment_reconciliation_events(payment_id, user_id, event_type, reason)
        VALUES (p_payment_id, p_user_id, 'cancelled', 'Venció la ventana de pago de 24 horas');
        INSERT INTO public.user_notifications(user_id, type, title, body)
        VALUES (p_user_id, 'payment_cancelled', 'Pago vencido',
                'El plazo de 24 horas venció. Puedes crear una nueva orden de promoción.');
        RETURN NEXT v_payment;
        RETURN;
    END IF;

    IF p_receipt_sha256 IS NULL OR p_receipt_sha256 !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Hash de comprobante no válido' USING ERRCODE = '22023';
    END IF;
    IF p_transfer_last4 IS NULL OR p_transfer_last4 !~ '^[0-9]{4}$' THEN
        RAISE EXCEPTION 'Indica los últimos cuatro dígitos de la cuenta de origen' USING ERRCODE = '22023';
    END IF;
    IF p_transfer_at IS NULL
       OR p_transfer_at < v_payment.created_at - interval '5 minutes'
       OR p_transfer_at > LEAST(now() + interval '5 minutes', v_payment.expires_at) THEN
        RAISE EXCEPTION 'La fecha/hora de transferencia está fuera de la ventana permitida' USING ERRCODE = '23514';
    END IF;
    IF NULLIF(btrim(p_proof_ref), '') IS NULL THEN
        RAISE EXCEPTION 'Adjunta la captura del comprobante' USING ERRCODE = '22023';
    END IF;

    v_proof_path_prefix := p_user_id::text || '/' || p_payment_id::text || '/';
    IF left(p_proof_ref, length(v_proof_path_prefix)) <> v_proof_path_prefix
       OR p_proof_ref !~ '\.(jpg|png)$'
       OR NOT EXISTS (
            SELECT 1 FROM storage.objects o
            WHERE o.bucket_id = 'payment-proofs' AND o.name = p_proof_ref
       ) THEN
        RAISE EXCEPTION 'No se encontró la captura subida para este pago' USING ERRCODE = '23503';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.payment_receipt_blacklist b
        WHERE b.receipt_sha256 = p_receipt_sha256
    ) THEN
        RAISE EXCEPTION 'Este comprobante está bloqueado. Contacta a soporte si crees que es un error.'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.payments p
        WHERE p.receipt_sha256 = p_receipt_sha256 AND p.id <> p_payment_id
    ) THEN
        RAISE EXCEPTION 'Este comprobante ya fue usado en otro pago' USING ERRCODE = '23505';
    END IF;

    SELECT * INTO v_listing FROM public.listings WHERE id = v_payment.target_id FOR UPDATE;
    IF NOT FOUND OR v_listing.owner_id <> p_user_id THEN
        RAISE EXCEPTION 'No se encontró la publicación asociada al pago' USING ERRCODE = '42501';
    END IF;
    IF v_listing.status = 'paused_payment' THEN
        RAISE EXCEPTION 'La publicación ya está en revisión de pago' USING ERRCODE = '23505';
    END IF;

    UPDATE public.payments
    SET status = 'review',
        proof_ref = p_proof_ref,
        receipt_sha256 = p_receipt_sha256,
        transfer_last4 = p_transfer_last4,
        transfer_at = p_transfer_at,
        submitted_at = now(),
        listing_status_before_review = v_listing.status,
        rejection_reason = NULL
    WHERE id = p_payment_id
    RETURNING * INTO v_payment;

    UPDATE public.listings SET status = 'paused_payment' WHERE id = v_listing.id;
    INSERT INTO public.payment_reconciliation_events(payment_id, user_id, event_type, transfer_at)
    VALUES (p_payment_id, p_user_id, 'submitted', p_transfer_at);

    RETURN NEXT v_payment;
END;
$$;

CREATE FUNCTION public.payment_review_queue()
RETURNS TABLE (
    payment_id uuid,
    user_id uuid,
    product_type public.product_type,
    target_id uuid,
    amount numeric,
    currency text,
    code text,
    status public.payment_status,
    product_code text,
    product_tier text,
    proof_ref text,
    receipt_sha256 text,
    transfer_last4 text,
    transfer_at timestamptz,
    created_at timestamptz,
    expires_at timestamptz,
    submitted_at timestamptz,
    product_title text,
    listing_title text,
    owner_name text,
    owner_phone text
)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NOT public.is_payment_reviewer() THEN
        RAISE EXCEPTION 'Solo un cobrador/admin puede abrir la cola de conciliación' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
    SELECT p.id, p.user_id, p.product_type, p.target_id, p.amount, p.currency, p.code, p.status,
           p.product_code, p.product_tier, p.proof_ref, p.receipt_sha256, p.transfer_last4,
           p.transfer_at, p.created_at, p.expires_at, p.submitted_at,
           COALESCE(mp.title, p.product_code, 'Promoción'), l.title,
           COALESCE(pr.display_name, 'Usuario'), u.phone
    FROM public.payments p
    JOIN public.listings l ON l.id = p.target_id
    JOIN public.users u ON u.id = p.user_id
    LEFT JOIN public.profiles pr ON pr.user_id = p.user_id
    LEFT JOIN public.monetization_products mp ON mp.code = p.product_code
    WHERE p.status = 'review'
    ORDER BY p.submitted_at ASC, p.created_at ASC;
END;
$$;

CREATE FUNCTION public.confirm_monetization_payment(
    p_payment_id uuid,
    p_received_amount numeric,
    p_transfer_at timestamptz,
    p_transfer_last4 text,
    p_reference text DEFAULT NULL
)
RETURNS SETOF public.payments
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_payment public.payments%ROWTYPE;
    v_product public.monetization_products%ROWTYPE;
    v_listing public.listings%ROWTYPE;
    v_reference text := NULLIF(btrim(p_reference), '');
    v_end_at timestamptz;
    v_now timestamptz := now();
BEGIN
    IF NOT public.is_payment_reviewer() THEN
        RAISE EXCEPTION 'Solo un cobrador/admin puede confirmar pagos' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO v_payment FROM public.payments WHERE id = p_payment_id FOR UPDATE;
    IF NOT FOUND OR v_payment.status <> 'review' THEN
        RAISE EXCEPTION 'El pago no está en revisión o ya fue conciliado' USING ERRCODE = '23514';
    END IF;
    IF p_received_amount IS NULL OR p_received_amount <> v_payment.amount THEN
        RAISE EXCEPTION 'El monto del extracto no coincide con el monto esperado (% CUP)'
            , v_payment.amount USING ERRCODE = '23514';
    END IF;
    IF p_transfer_at IS NULL OR p_transfer_at < v_payment.created_at - interval '5 minutes'
       OR p_transfer_at > LEAST(v_now + interval '5 minutes', COALESCE(v_payment.expires_at, v_now)) THEN
        RAISE EXCEPTION 'La fecha/hora del extracto está fuera de la ventana de pago' USING ERRCODE = '23514';
    END IF;
    IF v_payment.transfer_at IS NULL
       OR abs(extract(epoch FROM (p_transfer_at - v_payment.transfer_at))) > 900 THEN
        RAISE EXCEPTION 'El horario del extracto difiere más de 15 minutos del comprobante' USING ERRCODE = '23514';
    END IF;
    IF p_transfer_last4 IS NULL OR p_transfer_last4 !~ '^[0-9]{4}$'
       OR p_transfer_last4 <> v_payment.transfer_last4 THEN
        RAISE EXCEPTION 'Los últimos cuatro dígitos no coinciden con el comprobante' USING ERRCODE = '23514';
    END IF;
    IF v_payment.receipt_sha256 IS NULL OR EXISTS (
        SELECT 1 FROM public.payment_receipt_blacklist b WHERE b.receipt_sha256 = v_payment.receipt_sha256
    ) THEN
        RAISE EXCEPTION 'El comprobante está bloqueado o no tiene hash verificable' USING ERRCODE = '23514';
    END IF;
    IF v_reference IS NOT NULL AND char_length(v_reference) > 100 THEN
        RAISE EXCEPTION 'Referencia de transferencia demasiado larga' USING ERRCODE = '22023';
    END IF;
    IF v_reference IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.payments p
        WHERE p.reconciliation_reference = v_reference AND p.status = 'confirmed' AND p.id <> p_payment_id
    ) THEN
        RAISE EXCEPTION 'La referencia de transferencia ya fue conciliada' USING ERRCODE = '23505';
    END IF;

    SELECT * INTO v_product FROM public.monetization_products WHERE code = v_payment.product_code;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No se encontró la definición del producto comprado' USING ERRCODE = '23503';
    END IF;
    SELECT * INTO v_listing FROM public.listings WHERE id = v_payment.target_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'La publicación asociada ya no existe' USING ERRCODE = '23503';
    END IF;

    v_end_at := v_now + make_interval(hours => v_product.duration_hours);
    UPDATE public.payments
    SET status = 'confirmed',
        confirmed_by = auth.uid(),
        confirmed_at = v_now,
        reviewed_by = auth.uid(),
        reviewed_at = v_now,
        reconciliation_amount = p_received_amount,
        reconciliation_transfer_at = p_transfer_at,
        reconciliation_last4 = p_transfer_last4,
        reconciliation_reference = v_reference
    WHERE id = p_payment_id
    RETURNING * INTO v_payment;

    INSERT INTO public.boosts(listing_id, tier, starts_at, ends_at, payment_id, product_type, reach_scope)
    VALUES (v_listing.id, v_product.tier, v_now, v_end_at, v_payment.id, v_product.product_type, v_product.reach_scope);

    UPDATE public.listings
    SET boosted_until = GREATEST(COALESCE(boosted_until, v_now), v_end_at),
        status = COALESCE(v_payment.listing_status_before_review, 'active')
    WHERE id = v_listing.id;

    INSERT INTO public.payment_reconciliation_events(
        payment_id, user_id, actor_id, event_type, amount, transfer_at, reference
    ) VALUES (
        v_payment.id, v_payment.user_id, auth.uid(), 'confirmed', p_received_amount,
        p_transfer_at, v_reference
    );
    INSERT INTO public.user_notifications(user_id, type, title, body)
    VALUES (
        v_payment.user_id,
        'payment_confirmed',
        'Pago confirmado: promoción activa',
        'La publicación «' || v_listing.title || '» ya está promocionada hasta ' || v_end_at::text || '.'
    );

    RETURN NEXT v_payment;
END;
$$;

CREATE FUNCTION public.reject_monetization_payment(
    p_payment_id uuid,
    p_reason text,
    p_blacklist_receipt boolean DEFAULT false
)
RETURNS SETOF public.payments
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_payment public.payments%ROWTYPE;
    v_listing public.listings%ROWTYPE;
    v_reason text := btrim(COALESCE(p_reason, ''));
BEGIN
    IF NOT public.is_payment_reviewer() THEN
        RAISE EXCEPTION 'Solo un cobrador/admin puede rechazar pagos' USING ERRCODE = '42501';
    END IF;
    IF char_length(v_reason) NOT BETWEEN 1 AND 500 THEN
        RAISE EXCEPTION 'Explica el motivo del rechazo (máximo 500 caracteres)' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO v_payment FROM public.payments WHERE id = p_payment_id FOR UPDATE;
    IF NOT FOUND OR v_payment.status <> 'review' THEN
        RAISE EXCEPTION 'El pago no está en revisión o ya fue conciliado' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO v_listing FROM public.listings WHERE id = v_payment.target_id FOR UPDATE;

    UPDATE public.payments
    SET status = 'rejected',
        rejection_reason = v_reason,
        reviewed_by = auth.uid(),
        reviewed_at = now()
    WHERE id = p_payment_id
    RETURNING * INTO v_payment;

    IF FOUND AND v_payment.receipt_sha256 IS NOT NULL AND p_blacklist_receipt THEN
        INSERT INTO public.payment_receipt_blacklist(receipt_sha256, reason, payment_id, created_by)
        VALUES (v_payment.receipt_sha256, v_reason, v_payment.id, auth.uid())
        ON CONFLICT (receipt_sha256) DO NOTHING;
    END IF;
    IF v_listing.id IS NOT NULL THEN
        UPDATE public.listings
        SET status = COALESCE(v_payment.listing_status_before_review, 'active')
        WHERE id = v_listing.id;
    END IF;

    INSERT INTO public.payment_reconciliation_events(
        payment_id, user_id, actor_id, event_type, reason
    ) VALUES (v_payment.id, v_payment.user_id, auth.uid(), 'rejected', v_reason);
    INSERT INTO public.user_notifications(user_id, type, title, body)
    VALUES (v_payment.user_id, 'payment_rejected', 'Comprobante rechazado', v_reason || ' Puedes intentarlo otra vez.');

    RETURN NEXT v_payment;
END;
$$;

CREATE FUNCTION public.expire_pending_monetization_payments()
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_payment record;
    v_count integer := 0;
BEGIN
    -- No altera pagos en review: un comprobante recibido debe esperar a la conciliación.
    FOR v_payment IN
        UPDATE public.payments p
        SET status = 'cancelled'
        WHERE p.status = 'pending' AND p.expires_at <= now()
        RETURNING p.id, p.user_id, p.code
    LOOP
        v_count := v_count + 1;
        INSERT INTO public.payment_reconciliation_events(payment_id, user_id, event_type, reason)
        VALUES (v_payment.id, v_payment.user_id, 'cancelled', 'Venció la ventana de pago de 24 horas');
        INSERT INTO public.user_notifications(user_id, type, title, body)
        VALUES (v_payment.user_id, 'payment_cancelled', 'Pago vencido',
                'La orden ' || COALESCE(v_payment.code, '') || ' venció. Puedes crear una nueva orden.');
    END LOOP;
    RETURN v_count;
END;
$$;

CREATE FUNCTION public.update_monetization_settings(p_transfer_account text, p_account_holder text)
RETURNS SETOF public.monetization_settings
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_account text := regexp_replace(COALESCE(p_transfer_account, ''), '[^0-9]', '', 'g');
    v_holder text := NULLIF(btrim(COALESCE(p_account_holder, '')), '');
    v_settings public.monetization_settings%ROWTYPE;
BEGIN
    IF NOT public.is_payment_reviewer() THEN
        RAISE EXCEPTION 'Solo un admin/cobrador puede cambiar la cuenta destino' USING ERRCODE = '42501';
    END IF;
    IF v_account !~ '^[0-9]{8,20}$' THEN
        RAISE EXCEPTION 'La cuenta Transfermóvil debe tener entre 8 y 20 dígitos' USING ERRCODE = '22023';
    END IF;
    IF v_holder IS NOT NULL AND char_length(v_holder) > 100 THEN
        RAISE EXCEPTION 'El titular es demasiado largo' USING ERRCODE = '22023';
    END IF;
    UPDATE public.monetization_settings
    SET transfer_account = v_account, account_holder = v_holder,
        updated_at = now(), updated_by = auth.uid()
    WHERE id = 1
    RETURNING * INTO v_settings;
    RETURN NEXT v_settings;
END;
$$;

CREATE FUNCTION public.update_monetization_product_price(p_code text, p_amount numeric, p_is_active boolean)
RETURNS SETOF public.monetization_products
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_product public.monetization_products%ROWTYPE;
BEGIN
    IF NOT public.is_payment_reviewer() THEN
        RAISE EXCEPTION 'Solo un admin/cobrador puede cambiar tarifas' USING ERRCODE = '42501';
    END IF;
    IF p_amount IS NULL OR p_amount <= 0 OR p_amount >= 'Infinity'::numeric OR p_amount = 'NaN'::numeric THEN
        RAISE EXCEPTION 'La tarifa debe ser mayor que cero CUP' USING ERRCODE = '22023';
    END IF;
    UPDATE public.monetization_products
    SET amount = p_amount, is_active = COALESCE(p_is_active, is_active),
        updated_at = now(), updated_by = auth.uid()
    WHERE code = p_code
    RETURNING * INTO v_product;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Producto no encontrado' USING ERRCODE = 'P0002';
    END IF;
    RETURN NEXT v_product;
END;
$$;

-- La app solo puede caducar sus órdenes; el barrido global se reserva al servidor.
REVOKE ALL ON FUNCTION public.create_monetization_payment(uuid, text),
    public.submit_monetization_proof(uuid, uuid, text, text, text, timestamptz),
    public.payment_review_queue(),
    public.confirm_monetization_payment(uuid, numeric, timestamptz, text, text),
    public.reject_monetization_payment(uuid, text, boolean),
    public.expire_pending_monetization_payments(),
    public.expire_my_pending_monetization_payments(),
    public.update_monetization_settings(text, text),
    public.update_monetization_product_price(text, numeric, boolean)
FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_monetization_payment(uuid, text),
    public.confirm_monetization_payment(uuid, numeric, timestamptz, text, text),
    public.reject_monetization_payment(uuid, text, boolean),
    public.payment_review_queue(),
    public.expire_my_pending_monetization_payments(),
    public.update_monetization_settings(text, text),
    public.update_monetization_product_price(text, numeric, boolean)
TO authenticated;
GRANT EXECUTE ON FUNCTION public.submit_monetization_proof(uuid, uuid, text, text, text, timestamptz)
TO service_role;
GRANT EXECUTE ON FUNCTION public.expire_pending_monetization_payments()
TO service_role;

-- Se activa si pg_cron está habilitado; la RPC también se ejecuta al abrir historial/crear orden.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'cron') THEN
        EXECUTE $schedule$
            SELECT cron.schedule(
                'expire-monetization-payments',
                '*/5 * * * *',
                'SELECT public.expire_pending_monetization_payments()'
            )
        $schedule$;
    END IF;
END;
$$;

COMMIT;

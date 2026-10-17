-- Fase 1: autenticación por teléfono, verificación de identidad, privacidad y reputación.
-- Supabase/PostgreSQL 15+. Ejecutar una vez con el dueño de la migración (postgres),
-- después de 001_initial_schema.sql.
BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Estado de la verificación de identidad: pending -> approved | rejected.
--    El administrador escribe el estado; el cliente solo puede crear filas
--    'pending' (política identity_submit de 001). Se normaliza el antiguo
--    valor 'verified' a 'approved' para alinearlo con el flujo de revisión.
-- ---------------------------------------------------------------------------
ALTER TABLE public.identity_verifications
    DROP CONSTRAINT identity_verifications_status_check;
ALTER TABLE public.identity_verifications
    ADD CONSTRAINT identity_verifications_status_check
    CHECK (status IN ('pending', 'approved', 'rejected'));
UPDATE public.identity_verifications SET status = 'approved' WHERE status = 'verified';

-- Una sola solicitud pendiente por usuario; el historial se conserva.
CREATE UNIQUE INDEX identity_pending_unique
    ON public.identity_verifications(user_id) WHERE status = 'pending';

-- ---------------------------------------------------------------------------
-- 2. Privacidad del perfil: nivel por defecto (public/anon) y política de
--    revelación en el chat (never/on_deal/on_dispute/configurable).
-- ---------------------------------------------------------------------------
ALTER TABLE public.profiles
    ADD COLUMN default_reveal_policy public.reveal_policy NOT NULL DEFAULT 'never';
GRANT UPDATE (default_reveal_policy) ON public.profiles TO authenticated;

-- ---------------------------------------------------------------------------
-- 3. Alias automático: si se elige 'anon' y no se define alias, se genera uno
--    estable a partir del usuario (Anon-XXXXXX). Mismo algoritmo que el
--    AliasGenerator del cliente.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.ensure_profile_alias() RETURNS trigger
LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    IF NEW.privacy_level = 'anon' AND (NEW.alias IS NULL OR btrim(NEW.alias) = '') THEN
        NEW.alias := 'Anon-' || upper(substr(encode(
            sha256(convert_to(NEW.user_id::text, 'UTF8')), 'hex'), 1, 6));
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER profile_alias BEFORE INSERT OR UPDATE ON public.profiles
FOR EACH ROW EXECUTE FUNCTION public.ensure_profile_alias();

-- ---------------------------------------------------------------------------
-- 4. Reputación: profiles.reputation_avg y reviews_count se recalculan con un
--    trigger cuando se inserta una review (y al cambiar su estado o borrarla).
--    Solo cuentan las reviews publicadas; el cliente no puede escribirlos.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.refresh_reviewee_reputation() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    target uuid;
BEGIN
    target := CASE WHEN TG_OP = 'DELETE' THEN OLD.reviewee_id ELSE NEW.reviewee_id END;
    IF TG_OP = 'UPDATE' AND OLD.reviewee_id IS DISTINCT FROM NEW.reviewee_id THEN
        UPDATE public.profiles p SET
            reputation_avg = COALESCE((
                SELECT round(avg(r.rating)::numeric, 2) FROM public.reviews r
                WHERE r.reviewee_id = p.user_id AND r.status = 'published'), 0),
            reviews_count = (
                SELECT count(*) FROM public.reviews r
                WHERE r.reviewee_id = p.user_id AND r.status = 'published')
        WHERE p.user_id = OLD.reviewee_id;
    END IF;
    UPDATE public.profiles p SET
        reputation_avg = COALESCE((
            SELECT round(avg(r.rating)::numeric, 2) FROM public.reviews r
            WHERE r.reviewee_id = p.user_id AND r.status = 'published'), 0),
        reviews_count = (
            SELECT count(*) FROM public.reviews r
            WHERE r.reviewee_id = p.user_id AND r.status = 'published')
    WHERE p.user_id = target;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER review_reputation AFTER INSERT OR UPDATE OR DELETE ON public.reviews
FOR EACH ROW EXECUTE FUNCTION public.refresh_reviewee_reputation();

-- ---------------------------------------------------------------------------
-- 5. Estados de cuenta (server-owned):
--    - Al confirmar el teléfono en Supabase Auth: verification_level = 'phone'.
--      El usuario sigue en 'temp' hasta aprobar la identidad.
--    - Al aprobar el administrador la identidad: verification_level =
--      'identity' y status 'temp' -> 'active'. Rejected no cambia nada.
--    Los clientes no pueden escribir estas columnas (permisos de 001).
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.handle_phone_confirmed() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    UPDATE public.users
    SET verification_level = 'phone'
    WHERE id = NEW.id AND verification_level = 'none';
    RETURN NEW;
END;
$$;
CREATE TRIGGER on_auth_phone_confirmed
AFTER UPDATE OF phone_confirmed_at ON auth.users
FOR EACH ROW
WHEN (OLD.phone_confirmed_at IS NULL AND NEW.phone_confirmed_at IS NOT NULL)
EXECUTE FUNCTION public.handle_phone_confirmed();

CREATE FUNCTION public.apply_identity_review() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NEW.status = 'approved' AND OLD.status IS DISTINCT FROM NEW.status THEN
        UPDATE public.users
        SET verification_level = 'identity',
            status = CASE WHEN status = 'temp' THEN 'active' ELSE status END
        WHERE id = NEW.user_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER on_identity_reviewed
AFTER UPDATE OF status ON public.identity_verifications
FOR EACH ROW EXECUTE FUNCTION public.apply_identity_review();

-- ---------------------------------------------------------------------------
-- 6. Bucket privado para las fotos del carnet. Cada usuario solo escribe/lee
--    bajo su propia carpeta <auth.uid()>/; los administradores revisan todo.
--    (El bucket real es privado: no hay URLs públicas de documentos.)
-- ---------------------------------------------------------------------------
INSERT INTO storage.buckets (id, name, public)
VALUES ('identity-docs', 'identity-docs', false)
ON CONFLICT (id) DO NOTHING;

CREATE POLICY identity_docs_insert ON storage.objects FOR INSERT TO authenticated
WITH CHECK (bucket_id = 'identity-docs'
    AND (storage.foldername(name))[1] = (SELECT auth.uid())::text);
CREATE POLICY identity_docs_select ON storage.objects FOR SELECT TO authenticated
USING (bucket_id = 'identity-docs'
    AND ((storage.foldername(name))[1] = (SELECT auth.uid())::text OR public.is_admin()));
CREATE POLICY identity_docs_delete ON storage.objects FOR DELETE TO authenticated
USING (bucket_id = 'identity-docs'
    AND ((storage.foldername(name))[1] = (SELECT auth.uid())::text OR public.is_admin()));

COMMIT;

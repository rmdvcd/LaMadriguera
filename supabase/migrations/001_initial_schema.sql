-- Supabase/PostgreSQL 15+. Run once with the migration owner (postgres).
BEGIN;

CREATE TYPE public.verification_level AS ENUM ('none', 'phone', 'identity');
CREATE TYPE public.user_status AS ENUM ('temp', 'active', 'suspended', 'banned');
CREATE TYPE public.privacy_level AS ENUM ('public', 'anon');
CREATE TYPE public.listing_kind AS ENUM ('offer', 'request');
CREATE TYPE public.sale_mode AS ENUM ('fixed', 'auction');
CREATE TYPE public.listing_status AS ENUM ('active', 'paused_payment', 'auction_live', 'awarded', 'closed');
CREATE TYPE public.bid_status AS ENUM ('valid', 'retracted', 'winning', 'losing');
CREATE TYPE public.reveal_policy AS ENUM ('never', 'on_deal', 'on_dispute', 'configurable');
CREATE TYPE public.message_type AS ENUM ('text', 'image', 'audio', 'location');
CREATE TYPE public.product_type AS ENUM ('boost', 'feature', 'reach');
CREATE TYPE public.payment_status AS ENUM ('pending', 'review', 'confirmed', 'rejected', 'expired');
CREATE TYPE public.bet_status AS ENUM ('open', 'accepted', 'settled_creator', 'settled_acceptor', 'void', 'disputed');
CREATE TYPE public.moderation_source AS ENUM ('ai', 'user_report', 'admin', 'group_admin');

CREATE TABLE public.users (
    id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    phone text UNIQUE,
    verification_level public.verification_level NOT NULL DEFAULT 'none',
    status public.user_status NOT NULL DEFAULT 'temp',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.identity_verifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    doc_type text NOT NULL,
    doc_ref bytea NOT NULL, -- Encrypted document reference, never plaintext document bytes.
    full_name text NOT NULL,
    birthdate date NOT NULL,
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'verified', 'rejected')),
    verified_by uuid REFERENCES public.users(id),
    verified_at timestamptz,
    retention_until timestamptz NOT NULL,
    CHECK ((verified_by IS NULL) = (verified_at IS NULL))
);
CREATE TABLE public.profiles (
    user_id uuid PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    display_name text NOT NULL,
    alias text,
    avatar_url text,
    municipality_id int,
    privacy_level public.privacy_level NOT NULL DEFAULT 'public',
    bio text,
    reputation_avg numeric(3,2) NOT NULL DEFAULT 0 CHECK (reputation_avg BETWEEN 0 AND 5),
    reviews_count int NOT NULL DEFAULT 0 CHECK (reviews_count >= 0)
);
CREATE TABLE public.categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL,
    parent_id uuid REFERENCES public.categories(id),
    is_custom boolean NOT NULL DEFAULT false,
    created_by uuid REFERENCES public.users(id),
    CHECK (parent_id IS DISTINCT FROM id),
    CHECK (NOT is_custom OR created_by IS NOT NULL)
);
CREATE TABLE public.listings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES public.users(id),
    kind public.listing_kind NOT NULL DEFAULT 'offer',
    title text NOT NULL CHECK (length(trim(title)) > 0),
    description text NOT NULL,
    category_id uuid NOT NULL REFERENCES public.categories(id),
    price numeric NOT NULL CHECK (price >= 0 AND price < 'Infinity'::numeric),
    currency text NOT NULL DEFAULT 'CUP',
    price_negotiable boolean NOT NULL DEFAULT false,
    municipality_id int,
    radius_km int NOT NULL DEFAULT 10 CHECK (radius_km >= 0),
    privacy_level public.privacy_level NOT NULL DEFAULT 'public',
    sale_mode public.sale_mode NOT NULL DEFAULT 'fixed',
    auction_config jsonb,
    status public.listing_status NOT NULL DEFAULT 'active',
    boosted_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (auction_config IS NULL OR jsonb_typeof(auction_config) = 'object')
);
CREATE TABLE public.bids (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id uuid NOT NULL REFERENCES public.listings(id),
    bidder_id uuid NOT NULL REFERENCES public.users(id),
    amount numeric NOT NULL CHECK (amount > 0 AND amount < 'Infinity'::numeric),
    device_hash text,
    ip_hash text,
    status public.bid_status NOT NULL DEFAULT 'valid',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id uuid NOT NULL REFERENCES public.listings(id),
    participant_a uuid NOT NULL REFERENCES public.users(id),
    participant_b uuid NOT NULL REFERENCES public.users(id),
    reveal_policy public.reveal_policy NOT NULL DEFAULT 'never',
    session_key_wrapped bytea,
    dispute_key_split_a bytea,
    dispute_key_split_b bytea,
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed', 'disputed')),
    CHECK (participant_a <> participant_b)
);
CREATE TABLE public.messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES public.conversations(id),
    sender_id uuid NOT NULL REFERENCES public.users(id),
    type public.message_type NOT NULL DEFAULT 'text',
    content_ref text NOT NULL, -- Ciphertext/reference; encryption is performed outside this migration.
    expires_at timestamptz,
    reported boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reviewer_id uuid NOT NULL REFERENCES public.users(id),
    reviewee_id uuid NOT NULL REFERENCES public.users(id),
    conversation_id uuid NOT NULL REFERENCES public.conversations(id),
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    text text,
    status text NOT NULL DEFAULT 'published' CHECK (status IN ('published', 'hidden', 'pending')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (reviewer_id <> reviewee_id),
    UNIQUE (reviewer_id, conversation_id)
);
CREATE TABLE public.reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id uuid NOT NULL REFERENCES public.users(id),
    target_type text NOT NULL,
    target_id uuid NOT NULL,
    reason text NOT NULL,
    evidence_url text,
    status text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'review', 'resolved', 'dismissed')),
    handled_by uuid REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES public.users(id),
    product_type public.product_type NOT NULL,
    target_id uuid NOT NULL,
    amount numeric NOT NULL CHECK (amount > 0 AND amount < 'Infinity'::numeric),
    method text NOT NULL DEFAULT 'transfermovil',
    proof_ref text,
    code text,
    status public.payment_status NOT NULL DEFAULT 'pending',
    confirmed_by uuid REFERENCES public.users(id),
    confirmed_at timestamptz,
    CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL))
);
CREATE TABLE public.boosts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id uuid NOT NULL REFERENCES public.listings(id),
    tier text NOT NULL,
    starts_at timestamptz NOT NULL DEFAULT now(),
    ends_at timestamptz NOT NULL,
    payment_id uuid NOT NULL REFERENCES public.payments(id),
    CHECK (ends_at > starts_at)
);
CREATE TABLE public.betting_groups (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES public.users(id),
    name text NOT NULL,
    topic text,
    visibility text NOT NULL DEFAULT 'private' CHECK (visibility IN ('public', 'private')),
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed', 'suspended')),
    created_at timestamptz NOT NULL DEFAULT now()
);
-- Required to implement group-scoped RLS. Group ownership alone is not membership.
CREATE TABLE public.betting_group_members (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id uuid NOT NULL REFERENCES public.betting_groups(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (group_id, user_id)
);
CREATE TABLE public.bets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id uuid NOT NULL REFERENCES public.betting_groups(id),
    creator_id uuid NOT NULL REFERENCES public.users(id),
    acceptor_id uuid REFERENCES public.users(id),
    description text NOT NULL,
    stake_amount numeric NOT NULL CHECK (stake_amount > 0 AND stake_amount < 'Infinity'::numeric),
    stake_currency text NOT NULL DEFAULT 'CUP',
    odds numeric NOT NULL DEFAULT 1 CHECK (odds > 0 AND odds < 'Infinity'::numeric),
    status public.bet_status NOT NULL DEFAULT 'open',
    settled_by uuid REFERENCES public.users(id),
    settled_at timestamptz,
    evidence_url text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (acceptor_id IS DISTINCT FROM creator_id),
    CHECK ((settled_by IS NULL) = (settled_at IS NULL))
);
CREATE TABLE public.moderation_actions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type text NOT NULL,
    target_id uuid NOT NULL,
    source public.moderation_source NOT NULL,
    category text NOT NULL,
    severity smallint NOT NULL CHECK (severity BETWEEN 0 AND 5),
    action text NOT NULL,
    reason text NOT NULL,
    actor_id uuid REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX identity_user_idx ON public.identity_verifications(user_id);
CREATE INDEX identity_retention_idx ON public.identity_verifications(retention_until);
CREATE INDEX categories_parent_idx ON public.categories(parent_id);
CREATE INDEX listings_owner_idx ON public.listings(owner_id);
CREATE INDEX listings_feed_idx ON public.listings(status, created_at DESC);
CREATE INDEX listings_category_idx ON public.listings(category_id, municipality_id);
CREATE INDEX bids_listing_idx ON public.bids(listing_id, created_at DESC);
CREATE INDEX bids_bidder_idx ON public.bids(bidder_id);
CREATE INDEX conversations_a_idx ON public.conversations(participant_a);
CREATE INDEX conversations_b_idx ON public.conversations(participant_b);
CREATE INDEX conversations_listing_idx ON public.conversations(listing_id);
CREATE INDEX messages_thread_idx ON public.messages(conversation_id, created_at DESC);
CREATE INDEX messages_sender_idx ON public.messages(sender_id);
CREATE INDEX messages_expiry_idx ON public.messages(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX reviews_reviewee_idx ON public.reviews(reviewee_id);
CREATE INDEX reviews_conversation_idx ON public.reviews(conversation_id);
CREATE INDEX reports_reporter_idx ON public.reports(reporter_id);
CREATE INDEX reports_target_idx ON public.reports(target_type, target_id);
CREATE INDEX payments_user_idx ON public.payments(user_id);
CREATE INDEX boosts_listing_idx ON public.boosts(listing_id, ends_at);
CREATE INDEX boosts_payment_idx ON public.boosts(payment_id);
CREATE INDEX groups_owner_idx ON public.betting_groups(owner_id);
CREATE INDEX members_user_idx ON public.betting_group_members(user_id, group_id);
CREATE INDEX bets_group_idx ON public.bets(group_id, status);
CREATE INDEX bets_creator_idx ON public.bets(creator_id);
CREATE INDEX bets_acceptor_idx ON public.bets(acceptor_id);
CREATE INDEX moderation_target_idx ON public.moderation_actions(target_type, target_id);

-- Only app_metadata (set by a trusted backend), never user_metadata.
CREATE FUNCTION public.is_admin() RETURNS boolean
LANGUAGE sql STABLE SET search_path = '' AS $$
    SELECT COALESCE(auth.jwt() -> 'app_metadata' ->> 'role' = 'admin', false)
$$;
CREATE FUNCTION public.is_moderator() RETURNS boolean
LANGUAGE sql STABLE SET search_path = '' AS $$
    SELECT public.is_admin() OR COALESCE(auth.jwt() -> 'app_metadata' ->> 'role' = 'moderator', false)
$$;
-- SECURITY DEFINER avoids recursive policies and does not accept a user id from the caller.
CREATE FUNCTION public.is_group_member(gid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (SELECT 1 FROM public.betting_group_members
                   WHERE group_id = gid AND user_id = (SELECT auth.uid()))
$$;
CREATE FUNCTION public.owns_group(gid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (SELECT 1 FROM public.betting_groups
                   WHERE id = gid AND owner_id = (SELECT auth.uid()))
$$;
CREATE FUNCTION public.is_participant(cid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (SELECT 1 FROM public.conversations WHERE id = cid
                   AND (SELECT auth.uid()) IN (participant_a, participant_b))
$$;
CREATE FUNCTION public.can_read_conversation(cid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (SELECT 1 FROM public.conversations WHERE id = cid AND (
        (SELECT auth.uid()) IN (participant_a, participant_b)
        OR (status = 'disputed' AND public.is_moderator())))
$$;

-- New Auth users are provisioned by the server; clients cannot self-verify or self-activate.
CREATE FUNCTION public.handle_new_user() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.users(id, phone) VALUES (NEW.id, NEW.phone);
    RETURN NEW;
END;
$$;
CREATE TRIGGER on_auth_user_created AFTER INSERT ON auth.users
FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();
INSERT INTO public.users(id, phone) SELECT id, phone FROM auth.users ON CONFLICT (id) DO NOTHING;

CREATE FUNCTION public.add_group_owner() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.betting_group_members(group_id, user_id) VALUES (NEW.id, NEW.owner_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER on_group_created AFTER INSERT ON public.betting_groups
FOR EACH ROW EXECUTE FUNCTION public.add_group_owner();

-- Participants may open a dispute, but not grant themselves a moderation role.
CREATE FUNCTION public.open_conversation_dispute(conversation_id uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NOT public.is_participant(conversation_id) THEN
        RAISE EXCEPTION 'Only participants may open disputes' USING ERRCODE = '42501';
    END IF;
    UPDATE public.conversations SET status = 'disputed' WHERE id = conversation_id;
END;
$$;

ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.identity_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.listings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bids ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.boosts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.betting_groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.betting_group_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.moderation_actions ENABLE ROW LEVEL SECURITY;

CREATE POLICY users_self_read ON public.users FOR SELECT TO authenticated USING (id = (SELECT auth.uid()));
CREATE POLICY users_self_update ON public.users FOR UPDATE TO authenticated
USING (id = (SELECT auth.uid())) WITH CHECK (id = (SELECT auth.uid()));
CREATE POLICY identity_read ON public.identity_verifications FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) OR public.is_admin());
CREATE POLICY identity_submit ON public.identity_verifications FOR INSERT TO authenticated
WITH CHECK (user_id = (SELECT auth.uid()) AND status = 'pending' AND verified_by IS NULL AND verified_at IS NULL);
CREATE POLICY identity_admin_update ON public.identity_verifications FOR UPDATE TO authenticated
USING (public.is_admin()) WITH CHECK (public.is_admin());
CREATE POLICY identity_delete ON public.identity_verifications FOR DELETE TO authenticated
USING (user_id = (SELECT auth.uid()) OR public.is_admin());

CREATE POLICY profiles_read ON public.profiles FOR SELECT TO anon, authenticated
USING (privacy_level = 'public' OR user_id = (SELECT auth.uid()));
CREATE POLICY profiles_insert ON public.profiles FOR INSERT TO authenticated
WITH CHECK (user_id = (SELECT auth.uid()) AND reputation_avg = 0 AND reviews_count = 0);
CREATE POLICY profiles_update ON public.profiles FOR UPDATE TO authenticated
USING (user_id = (SELECT auth.uid())) WITH CHECK (user_id = (SELECT auth.uid()));
CREATE POLICY categories_read ON public.categories FOR SELECT TO anon, authenticated USING (true);
CREATE POLICY categories_insert ON public.categories FOR INSERT TO authenticated
WITH CHECK (is_custom AND created_by = (SELECT auth.uid()));
CREATE POLICY categories_update ON public.categories FOR UPDATE TO authenticated
USING (is_custom AND created_by = (SELECT auth.uid())) WITH CHECK (is_custom AND created_by = (SELECT auth.uid()));
CREATE POLICY categories_delete ON public.categories FOR DELETE TO authenticated
USING (is_custom AND created_by = (SELECT auth.uid()));

CREATE POLICY listings_read ON public.listings FOR SELECT TO anon, authenticated
USING (status = 'active' OR owner_id = (SELECT auth.uid()));
CREATE POLICY listings_insert ON public.listings FOR INSERT TO authenticated
WITH CHECK (owner_id = (SELECT auth.uid()) AND boosted_until IS NULL);
CREATE POLICY listings_update ON public.listings FOR UPDATE TO authenticated
USING (owner_id = (SELECT auth.uid())) WITH CHECK (owner_id = (SELECT auth.uid()));
CREATE POLICY listings_delete ON public.listings FOR DELETE TO authenticated USING (owner_id = (SELECT auth.uid()));
-- Bids contain anti-fraud hashes: not public. Winning/losing state is backend-only.
CREATE POLICY bids_read ON public.bids FOR SELECT TO authenticated USING (bidder_id = (SELECT auth.uid()));
CREATE POLICY bids_insert ON public.bids FOR INSERT TO authenticated WITH CHECK (
    bidder_id = (SELECT auth.uid()) AND status = 'valid' AND EXISTS (
        SELECT 1 FROM public.listings l WHERE l.id = listing_id AND l.owner_id <> (SELECT auth.uid())
        AND l.sale_mode = 'auction' AND l.status = 'active'));

CREATE POLICY conversations_read ON public.conversations FOR SELECT TO authenticated
USING (public.can_read_conversation(id));
CREATE POLICY conversations_insert ON public.conversations FOR INSERT TO authenticated
WITH CHECK ((SELECT auth.uid()) IN (participant_a, participant_b) AND status = 'active' AND EXISTS (
    SELECT 1 FROM public.listings l WHERE l.id = listing_id AND l.owner_id IN (participant_a, participant_b)));
CREATE POLICY conversations_update ON public.conversations FOR UPDATE TO authenticated
USING (public.is_participant(id)) WITH CHECK (public.is_participant(id));
CREATE POLICY messages_read ON public.messages FOR SELECT TO authenticated
USING (public.can_read_conversation(conversation_id));
CREATE POLICY messages_insert ON public.messages FOR INSERT TO authenticated
WITH CHECK (sender_id = (SELECT auth.uid()) AND public.is_participant(conversation_id) AND NOT reported);

CREATE POLICY reviews_read ON public.reviews FOR SELECT TO anon, authenticated
USING (status = 'published' OR reviewer_id = (SELECT auth.uid()) OR reviewee_id = (SELECT auth.uid()));
CREATE POLICY reviews_insert ON public.reviews FOR INSERT TO authenticated WITH CHECK (
    reviewer_id = (SELECT auth.uid()) AND status = 'published' AND public.is_participant(conversation_id)
    AND EXISTS (SELECT 1 FROM public.conversations c WHERE c.id = conversation_id
                AND reviewee_id IN (c.participant_a, c.participant_b)));
CREATE POLICY reports_read ON public.reports FOR SELECT TO authenticated
USING (reporter_id = (SELECT auth.uid()) OR public.is_moderator());
CREATE POLICY reports_insert ON public.reports FOR INSERT TO authenticated
WITH CHECK (reporter_id = (SELECT auth.uid()) AND status = 'open' AND handled_by IS NULL);
CREATE POLICY reports_update ON public.reports FOR UPDATE TO authenticated
USING (public.is_moderator()) WITH CHECK (public.is_moderator());
CREATE POLICY payments_read ON public.payments FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) OR public.is_admin());
CREATE POLICY payments_insert ON public.payments FOR INSERT TO authenticated
WITH CHECK (user_id = (SELECT auth.uid()) AND status = 'pending' AND confirmed_by IS NULL AND confirmed_at IS NULL);
CREATE POLICY boosts_read ON public.boosts FOR SELECT TO anon, authenticated USING (
    EXISTS (SELECT 1 FROM public.listings l WHERE l.id = listing_id));

CREATE POLICY groups_read ON public.betting_groups FOR SELECT TO authenticated
USING (visibility = 'public' OR public.is_group_member(id) OR owner_id = (SELECT auth.uid()));
CREATE POLICY groups_insert ON public.betting_groups FOR INSERT TO authenticated
WITH CHECK (owner_id = (SELECT auth.uid()) AND status = 'active');
CREATE POLICY groups_update ON public.betting_groups FOR UPDATE TO authenticated
USING (owner_id = (SELECT auth.uid())) WITH CHECK (owner_id = (SELECT auth.uid()));
CREATE POLICY members_read ON public.betting_group_members FOR SELECT TO authenticated
USING (public.is_group_member(group_id));
CREATE POLICY members_insert ON public.betting_group_members FOR INSERT TO authenticated
WITH CHECK (public.owns_group(group_id));
CREATE POLICY members_delete ON public.betting_group_members FOR DELETE TO authenticated
USING (public.owns_group(group_id));
CREATE POLICY bets_read ON public.bets FOR SELECT TO authenticated USING (public.is_group_member(group_id));
CREATE POLICY bets_insert ON public.bets FOR INSERT TO authenticated WITH CHECK (
    public.is_group_member(group_id) AND creator_id = (SELECT auth.uid()) AND acceptor_id IS NULL
    AND status = 'open' AND settled_by IS NULL AND settled_at IS NULL);
-- Acceptance/settlement must be atomic trusted server operations, not arbitrary row updates.
CREATE POLICY moderation_read ON public.moderation_actions FOR SELECT TO authenticated USING (public.is_moderator());

-- Supabase may configure broad default privileges. Revoke them explicitly before granting.
REVOKE ALL ON public.users, public.identity_verifications, public.profiles, public.categories,
    public.listings, public.bids, public.conversations, public.messages, public.reviews,
    public.reports, public.payments, public.boosts, public.betting_groups,
    public.betting_group_members, public.bets, public.moderation_actions FROM anon, authenticated;
GRANT USAGE ON SCHEMA public TO anon, authenticated;
GRANT SELECT ON public.profiles, public.categories, public.listings, public.reviews, public.boosts TO anon;
GRANT SELECT ON public.users, public.identity_verifications, public.profiles, public.categories,
    public.listings, public.bids, public.conversations, public.messages, public.reviews,
    public.reports, public.payments, public.boosts, public.betting_groups,
    public.betting_group_members, public.bets, public.moderation_actions TO authenticated;
GRANT UPDATE (phone) ON public.users TO authenticated;
GRANT INSERT, DELETE ON public.identity_verifications TO authenticated;
GRANT UPDATE (status, verified_by, verified_at, retention_until) ON public.identity_verifications TO authenticated;
GRANT INSERT ON public.profiles TO authenticated;
GRANT UPDATE (display_name, alias, avatar_url, municipality_id, privacy_level, bio) ON public.profiles TO authenticated;
GRANT INSERT, DELETE ON public.categories TO authenticated;
GRANT UPDATE (name, parent_id) ON public.categories TO authenticated;
GRANT INSERT, DELETE ON public.listings TO authenticated;
GRANT UPDATE (kind, title, description, category_id, price, currency, price_negotiable, municipality_id,
    radius_km, privacy_level, sale_mode, auction_config, status) ON public.listings TO authenticated;
GRANT INSERT ON public.bids, public.conversations, public.messages, public.reviews, public.reports,
    public.payments, public.betting_groups, public.betting_group_members, public.bets TO authenticated;
GRANT UPDATE (reveal_policy, session_key_wrapped, dispute_key_split_a, dispute_key_split_b) ON public.conversations TO authenticated;
GRANT UPDATE (status, handled_by) ON public.reports TO authenticated;
GRANT UPDATE (name, topic, visibility) ON public.betting_groups TO authenticated;
GRANT DELETE ON public.betting_group_members TO authenticated;
GRANT ALL ON public.users, public.identity_verifications, public.profiles, public.categories,
    public.listings, public.bids, public.conversations, public.messages, public.reviews,
    public.reports, public.payments, public.boosts, public.betting_groups,
    public.betting_group_members, public.bets, public.moderation_actions TO service_role;

REVOKE ALL ON FUNCTION public.is_admin(), public.is_moderator(), public.is_group_member(uuid),
    public.owns_group(uuid), public.is_participant(uuid), public.can_read_conversation(uuid),
    public.handle_new_user(), public.add_group_owner(), public.open_conversation_dispute(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_admin(), public.is_moderator(), public.is_group_member(uuid),
    public.owns_group(uuid), public.is_participant(uuid), public.can_read_conversation(uuid),
    public.open_conversation_dispute(uuid) TO authenticated;

COMMIT;

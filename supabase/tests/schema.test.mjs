import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { PGlite } from '@electric-sql/pglite';

// PostgreSQL WASM, not a SQL parser/mock. Only Supabase Auth/Storage are stubbed.
const user = '00000000-0000-0000-0000-000000000001';
const peer = '00000000-0000-0000-0000-000000000002';
const outsider = '00000000-0000-0000-0000-000000000003';
const admin = '00000000-0000-0000-0000-000000000004';
const bettor = '00000000-0000-0000-0000-000000000005';
const acceptor = '00000000-0000-0000-0000-000000000006';
const minor = '00000000-0000-0000-0000-000000000007';
const bettingGroup = '30000000-0000-0000-0000-000000000002';
const listing = '10000000-0000-0000-0000-000000000001';
const paused = '10000000-0000-0000-0000-000000000002';
const conversation = '20000000-0000-0000-0000-000000000001';
const conversation2 = '20000000-0000-0000-0000-000000000002';
const group = '30000000-0000-0000-0000-000000000001';
const category = '40000000-0000-0000-0000-000000000001';

const db = new PGlite();
async function as(id, sql, role = 'authenticated', appRole = 'user', userMetadata = {}) {
  return db.transaction(async tx => {
    await tx.exec(`SET LOCAL ROLE ${role}`);
    await tx.query("SELECT set_config('request.jwt.claims', $1, true)", [
      JSON.stringify({ sub: id, app_metadata: { role: appRole }, user_metadata: userMetadata })
    ]);
    return tx.query(sql);
  });
}
const count = result => Number(result.rows[0].count);

await db.exec(`
  CREATE ROLE anon NOLOGIN;
  CREATE ROLE authenticated NOLOGIN;
  CREATE ROLE service_role NOLOGIN BYPASSRLS;
  CREATE SCHEMA auth;
  CREATE TABLE auth.users (id uuid PRIMARY KEY, phone text, phone_confirmed_at timestamptz);
  CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb
  $$;
  CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
    SELECT (auth.jwt()->>'sub')::uuid
  $$;
  GRANT USAGE ON SCHEMA auth TO anon, authenticated, service_role;
  GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA auth TO anon, authenticated, service_role;
  -- Storage stub: buckets/objects with RLS, like the Supabase storage schema.
  CREATE SCHEMA storage;
  CREATE TABLE storage.buckets (id text PRIMARY KEY, name text, public boolean NOT NULL DEFAULT false);
  CREATE TABLE storage.objects (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket_id text REFERENCES storage.buckets(id),
    name text,
    owner uuid
  );
  ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;
  CREATE FUNCTION storage.foldername(name text) RETURNS text[] LANGUAGE sql IMMUTABLE AS $$
    SELECT (string_to_array(name, '/'))[1 : array_length(string_to_array(name, '/'), 1) - 1]
  $$;
  GRANT USAGE ON SCHEMA storage TO anon, authenticated, service_role;
  GRANT ALL ON storage.objects, storage.buckets TO anon, authenticated, service_role;
`);
await db.exec(await readFile(new URL('../migrations/001_initial_schema.sql', import.meta.url), 'utf8'));
await db.exec(await readFile(new URL('../migrations/002_auth_identity_privacy.sql', import.meta.url), 'utf8'));
await db.exec(await readFile(new URL('../migrations/003_listings_auctions.sql', import.meta.url), 'utf8'));
await db.exec(await readFile(new URL('../migrations/004_chat_encryption_disputes.sql', import.meta.url), 'utf8'));
await db.exec(await readFile(new URL('../migrations/005_peer_betting.sql', import.meta.url), 'utf8'));
await db.exec(await readFile(new URL('../migrations/006_monetization_schema.sql', import.meta.url), 'utf8'));
await db.exec(await readFile(new URL('../migrations/007_monetization_workflow.sql', import.meta.url), 'utf8'));
await db.exec(`
  INSERT INTO auth.users(id) VALUES ('${user}'), ('${peer}'), ('${outsider}'), ('${admin}'), ('${bettor}'), ('${acceptor}'), ('${minor}');
  INSERT INTO public.categories(id, name) VALUES ('${category}', 'General');
  INSERT INTO public.listings(id, owner_id, title, description, category_id, price, status) VALUES
    ('${listing}', '${user}', 'Oferta', 'Descripción', '${category}', 10, 'active'),
    ('${paused}', '${user}', 'Privada', 'Descripción', '${category}', 10, 'closed');
  INSERT INTO public.conversations(id, listing_id, participant_a, participant_b)
    VALUES ('${conversation}', '${listing}', '${user}', '${peer}');
  INSERT INTO public.messages(conversation_id, sender_id, content_ref)
    VALUES ('${conversation}', '${user}', 'ciphertext');
  INSERT INTO public.identity_verifications(user_id, doc_type, doc_ref, full_name, birthdate, retention_until)
    VALUES ('${user}', 'id', '\\x00ff', 'Nombre', '1990-01-01', now() + interval '30 days');
  INSERT INTO public.betting_groups(id, owner_id, name) VALUES ('${group}', '${user}', 'Grupo');
  INSERT INTO public.betting_group_members(group_id, user_id) VALUES ('${group}', '${peer}');
  INSERT INTO public.bets(group_id, creator_id, description, stake_amount)
    VALUES ('${group}', '${user}', 'Apuesta', 10);
`);

test('all application tables have RLS enabled', async () => {
  const result = await db.query("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND rowsecurity");
  assert.equal(count(result), 26);
});

test('Kotlin row wire names match every SQL column', async () => {
  const mappings = {
    User: 'users', IdentityVerification: 'identity_verifications', Profile: 'profiles',
    Category: 'categories', Listing: 'listings', Bid: 'bids', Conversation: 'conversations',
    Message: 'messages', Review: 'reviews', Report: 'reports', Payment: 'payments',
    MonetizationProduct: 'monetization_products', MonetizationSettings: 'monetization_settings',
    Boost: 'boosts', BettingGroup: 'betting_groups', BettingGroupMember: 'betting_group_members',
    Bet: 'bets', BetEvent: 'bet_events', BettingControls: 'betting_controls',
    ModerationAction: 'moderation_actions',
    ConversationKey: 'conversation_keys', DisputeTicket: 'dispute_tickets',
    UserNotification: 'user_notifications'
  };
  for (const [model, table] of Object.entries(mappings)) {
    const source = await readFile(new URL(`../../app/src/main/java/com/lamadriguera/app/data/remote/supabase/row/${model}Row.kt`, import.meta.url), 'utf8');
    const fields = [];
    let wireName;
    for (const line of source.split('\n')) {
      const annotation = line.match(/@SerialName\("([^"]+)"\)/);
      if (annotation) wireName = annotation[1];
      const property = line.match(/val (\w+):/);
      if (property) { fields.push(wireName ?? property[1]); wireName = undefined; }
    }
    const columns = await db.query('SELECT column_name FROM information_schema.columns WHERE table_schema = $1 AND table_name = $2', ['public', table]);
    assert.deepEqual(fields.sort(), columns.rows.map(row => row.column_name).sort(), model);
  }
});

test('Kotlin enum wire values match SQL enums', async () => {
  const mappings = {
    VerificationLevel: 'verification_level', UserStatus: 'user_status', PrivacyLevel: 'privacy_level',
    ListingKind: 'listing_kind', ListingVisibility: 'listing_visibility', SaleMode: 'sale_mode',
    ListingStatus: 'listing_status', BidStatus: 'bid_status', RevealPolicy: 'reveal_policy',
    MessageType: 'message_type', ProductType: 'product_type', PaymentStatus: 'payment_status',
    BetStatus: 'bet_status', ModerationSource: 'moderation_source'
  };
  const source = await readFile(new URL('../../app/src/main/java/com/lamadriguera/app/domain/model/Enums.kt', import.meta.url), 'utf8');
  for (const [model, sqlType] of Object.entries(mappings)) {
    const body = source.match(new RegExp(`enum class ${model} \\{([^}]+)\\}`))[1];
    const values = [...body.matchAll(/@SerialName\("([^"]+)"\)/g)].map(match => match[1]);
    const labels = await db.query('SELECT enumlabel FROM pg_enum JOIN pg_type ON pg_type.oid = enumtypid WHERE typname = $1 ORDER BY enumsortorder', [sqlType]);
    assert.deepEqual(values, labels.rows.map(row => row.enumlabel), model);
  }
});

test('users: own row only; safe update; no status/verification escalation', async () => {
  assert.equal(count(await as(user, 'SELECT count(*) FROM public.users')), 1);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.users', 'authenticated', 'admin')), 1);
  await as(user, `UPDATE public.users SET phone = '+5355555555' WHERE id = '${user}'`);
  assert.equal((await as(user, `UPDATE public.users SET phone = 'x' WHERE id = '${peer}' RETURNING id`)).rows.length, 0);
  await assert.rejects(as(user, "UPDATE public.users SET verification_level = 'identity'"), /permission denied/);
  await assert.rejects(as(user, "UPDATE public.users SET status = 'active'"), /permission denied/);
});

test('identity: self/admin only; user_metadata cannot forge admin', async () => {
  assert.equal(count(await as(user, 'SELECT count(*) FROM public.identity_verifications')), 1);
  assert.equal(count(await as(peer, 'SELECT count(*) FROM public.identity_verifications')), 0);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.identity_verifications', 'authenticated', 'admin')), 1);
  assert.equal(count(await as(peer, 'SELECT count(*) FROM public.identity_verifications', 'authenticated', 'user', { role: 'admin' })), 0);
  assert.equal((await as(user, "UPDATE public.identity_verifications SET status = 'approved' RETURNING id")).rows.length, 0);
  assert.equal((await as(admin, "UPDATE public.identity_verifications SET status = 'approved' RETURNING id", 'authenticated', 'admin')).rows.length, 1);
});

test('identity approval activates the user; rejection leaves temp/phone untouched', async () => {
  // Approving the pending row of `user` promotes the account (trigger).
  const promoted = await db.query(`SELECT verification_level, status FROM public.users WHERE id = '${user}'`);
  assert.equal(promoted.rows[0].verification_level, 'identity');
  assert.equal(promoted.rows[0].status, 'active');
  // Only one pending request per user at a time.
  await as(peer, `INSERT INTO public.identity_verifications(user_id, doc_type, doc_ref, full_name, birthdate, retention_until)
    VALUES ('${peer}', 'id', '\\x0102', 'Otro Nombre', '1992-02-02', now() + interval '30 days')`);
  await assert.rejects(as(peer, `INSERT INTO public.identity_verifications(user_id, doc_type, doc_ref, full_name, birthdate, retention_until)
    VALUES ('${peer}', 'id', '\\x0304', 'Otro Nombre', '1992-02-02', now() + interval '30 days')`), /duplicate key/);
  // A rejected request keeps the previous account state...
  await as(admin, `UPDATE public.identity_verifications SET status = 'rejected', verified_by = '${admin}', verified_at = now() WHERE user_id = '${peer}' RETURNING id`, 'authenticated', 'admin');
  const untouched = await db.query(`SELECT verification_level, status FROM public.users WHERE id = '${peer}'`);
  assert.equal(untouched.rows[0].verification_level, 'none');
  assert.equal(untouched.rows[0].status, 'temp');
  // ...and allows filing a new request afterwards.
  const retry = await as(peer, `INSERT INTO public.identity_verifications(user_id, doc_type, doc_ref, full_name, birthdate, retention_until)
    VALUES ('${peer}', 'id', '\\x0304', 'Otro Nombre', '1992-02-02', now() + interval '30 days') RETURNING id`);
  assert.equal(retry.rows.length, 1);
});

test('phone confirmation in Auth sets verification_level to phone, never active', async () => {
  await db.exec(`UPDATE auth.users SET phone_confirmed_at = now() WHERE id = '${outsider}'`);
  const row = await db.query(`SELECT verification_level, status FROM public.users WHERE id = '${outsider}'`);
  assert.equal(row.rows[0].verification_level, 'phone');
  assert.equal(row.rows[0].status, 'temp');
});

test('reputation_avg and reviews_count are computed by trigger from published reviews', async () => {
  await db.exec(`
    INSERT INTO public.profiles(user_id, display_name) VALUES ('${user}', 'Uno'), ('${peer}', 'Dos');
  `);
  await as(user, `INSERT INTO public.reviews(reviewer_id, reviewee_id, conversation_id, rating)
    VALUES ('${user}', '${peer}', '${conversation}', 4)`);
  // Moderation may hold reviews back; hidden ones must not count.
  await db.exec(`INSERT INTO public.reviews(reviewer_id, reviewee_id, conversation_id, rating, status)
    VALUES ('${peer}', '${user}', '${conversation}', 5, 'hidden')`);
  let rep = await db.query(`SELECT reputation_avg::text, reviews_count FROM public.profiles WHERE user_id = '${peer}'`);
  assert.equal(rep.rows[0].reputation_avg, '4.00');
  assert.equal(Number(rep.rows[0].reviews_count), 1);
  rep = await db.query(`SELECT reputation_avg::text, reviews_count FROM public.profiles WHERE user_id = '${user}'`);
  assert.equal(rep.rows[0].reputation_avg, '0.00');
  assert.equal(Number(rep.rows[0].reviews_count), 0);
  // Publishing a held review counts it.
  await db.exec(`UPDATE public.reviews SET status = 'published' WHERE reviewee_id = '${user}'`);
  rep = await db.query(`SELECT reputation_avg::text, reviews_count FROM public.profiles WHERE user_id = '${user}'`);
  assert.equal(rep.rows[0].reputation_avg, '5.00');
  assert.equal(Number(rep.rows[0].reviews_count), 1);
  await db.exec(`DELETE FROM public.reviews WHERE reviewee_id = '${peer}'`);
  rep = await db.query(`SELECT reputation_avg::text, reviews_count FROM public.profiles WHERE user_id = '${peer}'`);
  assert.equal(rep.rows[0].reputation_avg, '0.00');
  assert.equal(Number(rep.rows[0].reviews_count), 0);
});

test('anon profiles get an automatic alias; explicit aliases survive', async () => {
  await as(outsider, `INSERT INTO public.profiles(user_id, display_name, privacy_level)
    VALUES ('${outsider}', 'Anónimo', 'anon')`);
  const generated = await db.query(`SELECT alias FROM public.profiles WHERE user_id = '${outsider}'`);
  assert.match(generated.rows[0].alias, /^Anon-[0-9A-F]{6}$/);
  await as(admin, `INSERT INTO public.profiles(user_id, display_name, alias, privacy_level)
    VALUES ('${admin}', 'Con Alias', 'MiAlias', 'anon')`);
  const kept = await db.query(`SELECT alias FROM public.profiles WHERE user_id = '${admin}'`);
  assert.equal(kept.rows[0].alias, 'MiAlias');
});

test('privacy defaults: reveal policy per profile is editable only by its owner', async () => {
  const defaults = await db.query(`SELECT privacy_level, default_reveal_policy FROM public.profiles WHERE user_id = '${peer}'`);
  assert.equal(defaults.rows[0].privacy_level, 'public');
  assert.equal(defaults.rows[0].default_reveal_policy, 'never');
  await as(peer, `UPDATE public.profiles SET privacy_level = 'anon', default_reveal_policy = 'on_deal' WHERE user_id = '${peer}'`);
  const updated = await db.query(`SELECT privacy_level, default_reveal_policy, alias FROM public.profiles WHERE user_id = '${peer}'`);
  assert.equal(updated.rows[0].privacy_level, 'anon');
  assert.equal(updated.rows[0].default_reveal_policy, 'on_deal');
  assert.match(updated.rows[0].alias, /^Anon-[0-9A-F]{6}$/);
  assert.equal((await as(user, `UPDATE public.profiles SET privacy_level = 'public' WHERE user_id = '${peer}' RETURNING user_id`)).rows.length, 0);
});

test('identity documents bucket is private and folder-scoped per user', async () => {
  const bucket = await db.query("SELECT public FROM storage.buckets WHERE id = 'identity-docs'");
  assert.equal(bucket.rows[0].public, false);
  await as(user, `INSERT INTO storage.objects(bucket_id, name) VALUES ('identity-docs', '${user}/front.jpg')`);
  await assert.rejects(
    as(user, `INSERT INTO storage.objects(bucket_id, name) VALUES ('identity-docs', '${peer}/front.jpg')`),
    /row-level security/);
  assert.equal(count(await as(user, `SELECT count(*) FROM storage.objects WHERE name LIKE '${user}/%'`)), 1);
  assert.equal(count(await as(peer, 'SELECT count(*) FROM storage.objects WHERE bucket_id = \'identity-docs\'')), 0);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM storage.objects WHERE bucket_id = \'identity-docs\'', 'authenticated', 'admin')), 1);
  // DELETE on invisible rows removes nothing instead of erroring.
  const removed = await as(user, `DELETE FROM storage.objects WHERE name = '${user}/front.jpg' RETURNING id`);
  assert.equal(removed.rows.length, 1);
  const blocked = await as(user, `DELETE FROM storage.objects WHERE bucket_id = 'identity-docs' RETURNING id`);
  assert.equal(blocked.rows.length, 0);
});

test('listings: public active feed; only owner writes; boost cannot be forged', async () => {
  assert.equal(count(await as(null, 'SELECT count(*) FROM public.listings', 'anon')), 1);
  assert.equal(count(await as(user, 'SELECT count(*) FROM public.listings')), 2);
  assert.equal((await as(peer, `UPDATE public.listings SET title = 'hack' WHERE id = '${listing}' RETURNING id`)).rows.length, 0);
  assert.equal((await as(user, `UPDATE public.listings SET title = 'Nuevo' WHERE id = '${listing}' RETURNING id`)).rows.length, 1);
  await assert.rejects(as(user, "UPDATE public.listings SET boosted_until = now() + interval '1 year'"), /permission denied/);
  await assert.rejects(as(user, `UPDATE public.listings SET owner_id = '${peer}'`), /permission denied/);
});

test('listings: request visibility providers_only hides demand from non-providers', async () => {
  const reqId = '10000000-0000-0000-0000-000000000010';
  await as(peer, `INSERT INTO public.listings(id, owner_id, kind, title, description, category_id, price, visibility)
    VALUES ('${reqId}', '${peer}', 'request', 'Busco cemento', 'En La Habana', '${category}', 0, 'providers_only')`);
  // Offer cannot have providers_only visibility
  await assert.rejects(as(peer, `INSERT INTO public.listings(owner_id, kind, title, description, category_id, price, visibility)
    VALUES ('${peer}', 'offer', 'Oferta inválida', 'Detalle', '${category}', 10, 'providers_only')`), /check constraint/);
  // Outsider (not a provider, not verified) cannot see providers_only request
  assert.equal(count(await as(outsider, `SELECT count(*) FROM public.listings WHERE id = '${reqId}'`)), 0);
  // User (has active offer and identity verification) is a provider and can see it
  assert.equal(count(await as(user, `SELECT count(*) FROM public.listings WHERE id = '${reqId}'`)), 1);
  // Owner (peer) can see their own request
  assert.equal(count(await as(peer, `SELECT count(*) FROM public.listings WHERE id = '${reqId}'`)), 1);
  await as(peer, `DELETE FROM public.listings WHERE id = '${reqId}'`);
});

test('auctions: config validation, edit lock after bids, security rules, auto-extend and closing states', async () => {
  const auctionId = '10000000-0000-0000-0000-000000000020';
  const noReserveMetId = '10000000-0000-0000-0000-000000000021';
  const expiredId = '10000000-0000-0000-0000-000000000022';

  // Invalid config: ends_at <= starts_at or starting_price <= 0 must fail
  await assert.rejects(as(user, `INSERT INTO public.listings(owner_id, title, description, category_id, price, sale_mode, auction_config)
    VALUES ('${user}', 'Subasta inválida', 'Desc', '${category}', 100, 'auction',
    jsonb_build_object('starting_price', 0, 'min_increment', 10, 'starts_at', now(), 'ends_at', now() + interval '1 hour'))`), /starting_price/);

  await assert.rejects(as(user, `INSERT INTO public.listings(owner_id, title, description, category_id, price, sale_mode, auction_config)
    VALUES ('${user}', 'Subasta inválida 2', 'Desc', '${category}', 100, 'auction',
    jsonb_build_object('starting_price', 100, 'min_increment', 10, 'starts_at', now() + interval '2 hours', 'ends_at', now() + interval '1 hour'))`), /ends_at/);

  // Create a scheduled auction first, then owner edits it before any bids
  await as(user, `INSERT INTO public.listings(id, owner_id, title, description, category_id, price, sale_mode, auction_config)
    VALUES ('${auctionId}', '${user}', 'Laptop en subasta', 'Core i7', '${category}', 100, 'auction',
    jsonb_build_object(
      'starting_price', 100,
      'min_increment', 10,
      'reserve_price', 120,
      'starts_at', (now() + interval '10 minutes')::text,
      'ends_at', (now() + interval '1 hour')::text,
      'auto_extend_minutes', 2,
      'requires_identity', true,
      'visible_bids', 'all'
    ))`);

  let stateRow = await db.query(`SELECT status FROM public.listings WHERE id = '${auctionId}'`);
  assert.equal(stateRow.rows[0].status, 'auction_scheduled');

  // Owner can edit config while there are no bids (make it live now, ending in 1 minute to test auto-extend)
  await as(user, `UPDATE public.listings SET auction_config = jsonb_build_object(
      'starting_price', 100,
      'min_increment', 10,
      'reserve_price', 120,
      'starts_at', (now() - interval '5 minutes')::text,
      'ends_at', (now() + interval '1 minute')::text,
      'auto_extend_minutes', 2,
      'requires_identity', true,
      'visible_bids', 'all'
    ) WHERE id = '${auctionId}'`);

  stateRow = await db.query(`SELECT status, auction_config->>'ends_at' AS ends_at FROM public.listings WHERE id = '${auctionId}'`);
  assert.equal(stateRow.rows[0].status, 'auction_live');
  const initialEndsAt = new Date(stateRow.rows[0].ends_at).getTime();

  // Security 1: outsider only has verification_level = 'phone', cannot bid when requires_identity = true
  await assert.rejects(
    as(outsider, `SELECT * FROM public.place_auction_bid('${auctionId}', 100)`),
    /identidad/
  );

  // Approve peer's identity so peer has verification_level = 'identity'
  await as(admin, `UPDATE public.identity_verifications SET status = 'approved', verified_by = '${admin}', verified_at = now()
    WHERE user_id = '${peer}' AND status = 'pending'`, 'authenticated', 'admin');

  // Security 2: user with open dispute cannot bid
  await db.exec(`UPDATE public.conversations SET status = 'disputed' WHERE id = '${conversation}'`);
  await assert.rejects(
    as(peer, `SELECT * FROM public.place_auction_bid('${auctionId}', 100)`),
    /disputa abierta/
  );
  await db.exec(`UPDATE public.conversations SET status = 'active' WHERE id = '${conversation}'`);

  // Bid 1 by peer (valid, >= starting_price 100)
  await as(peer, `SELECT * FROM public.place_auction_bid('${auctionId}', 100)`);

  // Auto-extend check: since ends_at was 1 minute away (<= 2 min), ends_at must have extended!
  const afterBidRow = await db.query(`SELECT auction_config->>'ends_at' AS ends_at FROM public.listings WHERE id = '${auctionId}'`);
  const extendedEndsAt = new Date(afterBidRow.rows[0].ends_at).getTime();
  assert.ok(extendedEndsAt > initialEndsAt, 'ends_at should auto-extend when bid arrives near closing');

  // Owner CANNOT edit auction_config once a bid exists
  await assert.rejects(
    as(user, `UPDATE public.listings SET auction_config = jsonb_set(auction_config, '{min_increment}', '50') WHERE id = '${auctionId}'`),
    /no haya pujas/
  );

  // Bid below min_increment (current 100 + 10 = 110) fails
  await assert.rejects(
    as(peer, `SELECT * FROM public.place_auction_bid('${auctionId}', 105)`),
    /mínimo requerido/
  );

  // Bid 2 and Bid 3 by peer
  await as(peer, `SELECT * FROM public.place_auction_bid('${auctionId}', 115)`);
  await as(peer, `SELECT * FROM public.place_auction_bid('${auctionId}', 130)`);

  // Security 3: Maximum 3 bids per user per auction
  await assert.rejects(
    as(peer, `SELECT * FROM public.place_auction_bid('${auctionId}', 150)`),
    /Máximo 3 pujas/
  );

  // Move ends_at to the past to close the auction (reserve 120 was met by 130)
  await db.exec(`
    ALTER TABLE public.listings DISABLE TRIGGER listings_auction_guard;
    UPDATE public.listings
    SET auction_config = jsonb_set(auction_config, '{ends_at}', to_jsonb((now() - interval '1 minute')::text))
    WHERE id = '${auctionId}';
    ALTER TABLE public.listings ENABLE TRIGGER listings_auction_guard;
  `);

  const closedAwarded = await as(user, `SELECT status, price::text FROM public.close_auction('${auctionId}')`);
  assert.equal(closedAwarded.rows[0].status, 'awarded');
  assert.equal(closedAwarded.rows[0].price, '130');

  const winningBids = await db.query(`SELECT amount::text, status FROM public.bids WHERE listing_id = '${auctionId}' ORDER BY amount DESC`);
  assert.equal(winningBids.rows[0].status, 'winning');
  assert.equal(winningBids.rows[0].amount, '130');
  assert.equal(winningBids.rows[1].status, 'losing');

  // Case 2: reserve not met -> no_reserve_met
  await as(user, `INSERT INTO public.listings(id, owner_id, title, description, category_id, price, sale_mode, auction_config)
    VALUES ('${noReserveMetId}', '${user}', 'Subasta con reserva alta', 'Desc', '${category}', 100, 'auction',
    jsonb_build_object(
      'starting_price', 100,
      'min_increment', 10,
      'reserve_price', 500,
      'starts_at', (now() - interval '10 minutes')::text,
      'ends_at', (now() + interval '10 minutes')::text,
      'auto_extend_minutes', 2,
      'requires_identity', false,
      'visible_bids', 'owner'
    ))`);
  await as(outsider, `SELECT * FROM public.place_auction_bid('${noReserveMetId}', 120)`);
  // With visible_bids = 'owner', peer (neither bidder nor owner) cannot read bids of noReserveMetId
  assert.equal(count(await as(peer, `SELECT count(*) FROM public.bids WHERE listing_id = '${noReserveMetId}'`)), 0);
  // Owner can read them
  assert.equal(count(await as(user, `SELECT count(*) FROM public.bids WHERE listing_id = '${noReserveMetId}'`)), 1);

  await db.exec(`
    ALTER TABLE public.listings DISABLE TRIGGER listings_auction_guard;
    UPDATE public.listings
    SET auction_config = jsonb_set(auction_config, '{ends_at}', to_jsonb((now() - interval '1 minute')::text))
    WHERE id = '${noReserveMetId}';
    ALTER TABLE public.listings ENABLE TRIGGER listings_auction_guard;
  `);
  const closedNoReserve = await as(user, `SELECT status FROM public.close_auction('${noReserveMetId}')`);
  assert.equal(closedNoReserve.rows[0].status, 'no_reserve_met');

  // Case 3: no bids -> expired (via close_due_auctions job)
  await as(user, `INSERT INTO public.listings(id, owner_id, title, description, category_id, price, sale_mode, auction_config)
    VALUES ('${expiredId}', '${user}', 'Subasta desierta', 'Desc', '${category}', 100, 'auction',
    jsonb_build_object(
      'starting_price', 100,
      'min_increment', 10,
      'starts_at', (now() - interval '20 minutes')::text,
      'ends_at', (now() - interval '1 minute')::text,
      'auto_extend_minutes', 2,
      'requires_identity', true,
      'visible_bids', 'all'
    ))`);
  await as(user, 'SELECT * FROM public.close_due_auctions()');
  const expiredState = await db.query(`SELECT status FROM public.listings WHERE id = '${expiredId}'`);
  assert.equal(expiredState.rows[0].status, 'expired');

  // Clean up test auctions so subsequent tests see the base state
  await db.exec(`
    DELETE FROM public.bids WHERE listing_id IN ('${auctionId}', '${noReserveMetId}', '${expiredId}');
    DELETE FROM public.listings WHERE id IN ('${auctionId}', '${noReserveMetId}', '${expiredId}');
  `);
});

test('chat: participants only, moderators need an open dispute; no participant hijacking', async () => {
  assert.equal(count(await as(peer, 'SELECT count(*) FROM public.messages')), 1);
  assert.equal(count(await as(outsider, 'SELECT count(*) FROM public.conversations')), 0);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.messages', 'authenticated', 'moderator')), 0);
  await assert.rejects(as(outsider, `SELECT public.open_conversation_dispute('${conversation}')`), /Only participants/);
  await assert.rejects(as(user, `UPDATE public.conversations SET participant_b = '${outsider}'`), /permission denied/);
  await assert.rejects(as(user, "UPDATE public.conversations SET status = 'disputed'"), /permission denied/);
  await assert.rejects(as(outsider, `INSERT INTO public.messages(conversation_id, sender_id, content_ref) VALUES ('${conversation}', '${outsider}', 'x')`), /row-level security/);
  await as(user, `SELECT public.open_conversation_dispute('${conversation}')`);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.messages', 'authenticated', 'moderator')), 1);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.conversations', 'authenticated', 'admin')), 1);
  assert.equal(count(await as(outsider, 'SELECT count(*) FROM public.messages')), 0);
  await assert.rejects(as(admin, `INSERT INTO public.messages(conversation_id, sender_id, content_ref) VALUES ('${conversation}', '${admin}', 'x')`, 'authenticated', 'moderator'), /row-level security/);
  await db.exec(`UPDATE public.conversations SET status = 'closed' WHERE id = '${conversation}'`);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.messages', 'authenticated', 'moderator')), 0);
});

test('bets: membership, read-only table writes and server-mediated membership changes', async () => {
  assert.equal(count(await as(user, 'SELECT count(*) FROM public.bets')), 1);
  assert.equal(count(await as(peer, 'SELECT count(*) FROM public.bets')), 1);
  assert.equal(count(await as(outsider, 'SELECT count(*) FROM public.bets')), 0);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.bets', 'authenticated', 'admin')), 0);
  await assert.rejects(as(outsider, `INSERT INTO public.betting_group_members(group_id, user_id) VALUES ('${group}', '${outsider}')`), /permission denied/);
  await assert.rejects(as(outsider, `SELECT * FROM public.join_public_betting_group('${group}')`), /disponible|private|public/i);
  await assert.rejects(as(outsider, `INSERT INTO public.bets(group_id, creator_id, description, stake_amount) VALUES ('${group}', '${outsider}', 'Apuesta', 1)`), /permission denied/);
  await assert.rejects(as(peer, "UPDATE public.bets SET status = 'settled_acceptor'"), /permission denied/);
  await as(user, `SELECT public.remove_betting_group_member('${group}', '${peer}')`);
  assert.equal(count(await as(peer, 'SELECT count(*) FROM public.bets')), 0);
});

test('P2P betting: identity + adult carnet, limits, self-exclusion, evidence, dispute decision and audit history', async () => {
  await db.exec(`
    UPDATE public.users SET verification_level = 'identity', status = 'active'
      WHERE id IN ('${bettor}', '${acceptor}', '${minor}');
    INSERT INTO public.identity_verifications(user_id, doc_type, doc_ref, full_name, birthdate, status, verified_by, verified_at, retention_until)
    VALUES
      ('${bettor}', 'carnet', '\\x01', 'Apostador', '1990-01-01', 'approved', '${admin}', now(), now() + interval '2 years'),
      ('${acceptor}', 'carnet', '\\x02', 'Aceptante', '1992-01-01', 'approved', '${admin}', now(), now() + interval '2 years'),
      ('${minor}', 'carnet', '\\x03', 'Menor', current_date - interval '17 years', 'approved', '${admin}', now(), now() + interval '2 years');
    UPDATE public.users SET phone = '+5350000099' WHERE id = '${acceptor}';
    UPDATE auth.users SET phone = '+5350000006', phone_confirmed_at = now() WHERE id = '${acceptor}';
    UPDATE public.users SET phone = '+5350000008' WHERE id = '${outsider}';
    UPDATE auth.users SET phone = '+5350000008', phone_confirmed_at = NULL WHERE id = '${outsider}';
    INSERT INTO public.betting_groups(id, owner_id, name, visibility)
      VALUES ('${bettingGroup}', '${bettor}', 'Prueba P2P', 'private');
    INSERT INTO public.betting_group_members(group_id, user_id)
      VALUES ('${bettingGroup}', '${acceptor}'), ('${bettingGroup}', '${minor}'), ('${bettingGroup}', '${outsider}');
    UPDATE public.users SET status = 'active', verification_level = 'phone' WHERE id = '${outsider}';
    INSERT INTO public.identity_verifications(user_id, doc_type, doc_ref, full_name, birthdate, status, verified_by, verified_at, retention_until)
      VALUES ('${outsider}', 'id', '\\x04', 'Solo identidad no carnet', '1990-01-01', 'approved', '${admin}', now(), now() + interval '2 years');
  `);

  const defaults = await as(bettor, `SELECT daily_limit_cup::text FROM public.betting_controls WHERE user_id = '${bettor}'`);
  assert.equal(defaults.rows[0].daily_limit_cup, '1000');
  const invited = await as(bettor,
    `SELECT (public.invite_betting_group_member('${bettingGroup}', '+53 50000006')).user_id AS id`
  );
  assert.equal(invited.rows[0].id, acceptor);
  await assert.rejects(
    as(bettor, `SELECT public.invite_betting_group_member('${bettingGroup}', '+53 50000008')`),
    /No se pudo invitar/i
  );
  const oddsDefault = await db.query(`SELECT column_default FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'bets' AND column_name = 'odds'`);
  assert.equal(oddsDefault.rows[0].column_default, null);

  await assert.rejects(
    as(outsider, `SELECT public.create_bet('${bettingGroup}', 'Solo teléfono', 10)`),
    /identidad|carnet/i
  );
  await db.exec(`UPDATE public.users SET verification_level = 'identity' WHERE id = '${outsider}'`);
  await assert.rejects(
    as(outsider, `SELECT public.create_bet('${bettingGroup}', 'No tiene carnet', 10)`),
    /carnet/i
  );

  await assert.rejects(
    as(minor, `SELECT public.create_bet('${bettingGroup}', 'Apuesta menor de edad', 10)`),
    /18 años/
  );
  await assert.rejects(
    as(outsider, `SELECT public.create_bet('${bettingGroup}', 'Usuario sin identidad', 10)`),
    /miembro|identidad|apostar/i
  );

  await as(bettor, `SELECT public.update_betting_daily_limit(100)`);
  await as(acceptor, `SELECT public.update_betting_daily_limit(100)`);
  const createdId = (await as(bettor,
    `SELECT (public.create_bet('${bettingGroup}', 'Resultado del partido', 60, NULL)).id AS id`
  )).rows[0].id;
  const created = await db.query(`SELECT status, odds, stake_currency FROM public.bets WHERE id = '${createdId}'`);
  assert.equal(created.rows[0].status, 'open');
  assert.equal(created.rows[0].odds, null);
  assert.equal(created.rows[0].stake_currency, 'CUP');

  await assert.rejects(
    as(minor, `SELECT (public.accept_bet('${createdId}')).id`),
    /18 años/
  );
  await as(acceptor, `SELECT (public.accept_bet('${createdId}')).id`);
  await assert.rejects(
    as(bettor, `SELECT public.create_bet('${bettingGroup}', 'Supera el límite', 41)`),
    /límite diario/i
  );
  await assert.rejects(
    as(bettor, `SELECT (public.submit_bet_result('${createdId}', 'creator', NULL, NULL)).id`),
    /evidencia/i
  );

  const imageRef = `${bettor}/${bettingGroup}/${createdId}/proof.jpg`;
  await as(bettor, `INSERT INTO storage.objects(bucket_id, name) VALUES ('bet-evidence', '${imageRef}')`);
  await as(bettor, `SELECT (public.submit_bet_result('${createdId}', 'creator', NULL, '${imageRef}')).id`);
  const disputed = (await as(acceptor,
    `SELECT (public.submit_bet_result('${createdId}', 'acceptor', 'Mi evidencia de desacuerdo', NULL)).status AS status`
  )).rows[0].status;
  assert.equal(disputed, 'disputed');
  await as(bettor, `SELECT (public.resolve_bet_dispute('${createdId}', 'acceptor', 'El grupo revisó la evidencia')).id`);
  const finalBet = await db.query(`SELECT status, decision_by, decision_note FROM public.bets WHERE id = '${createdId}'`);
  assert.equal(finalBet.rows[0].status, 'settled_acceptor');
  assert.equal(finalBet.rows[0].decision_by, bettor);
  assert.equal(finalBet.rows[0].decision_note, 'El grupo revisó la evidencia');
  await assert.rejects(
    as(acceptor, `SELECT (public.submit_bet_result('${createdId}', 'acceptor', 'Nueva declaración', NULL)).id`),
    /decidido de forma definitiva/i
  );
  await assert.rejects(
    as(acceptor, `SELECT public.open_bet_dispute('${createdId}', 'Nueva disputa', NULL)`),
    /decidido de forma definitiva/i
  );
  assert.equal(count(await as(acceptor, `SELECT count(*) FROM public.bet_events WHERE bet_id = '${createdId}'`)), 5);
  assert.equal(count(await as(acceptor, `SELECT count(*) FROM storage.objects WHERE bucket_id = 'bet-evidence' AND name = '${imageRef}'`)), 1);
  await as(bettor, `DELETE FROM storage.objects WHERE bucket_id = 'bet-evidence' AND name = '${imageRef}'`);
  assert.equal(count(await db.query(`SELECT count(*) FROM storage.objects WHERE bucket_id = 'bet-evidence' AND name = '${imageRef}'`)), 1);
  await assert.rejects(as(acceptor, `UPDATE public.bet_events SET evidence_text = 'editada' WHERE bet_id = '${createdId}'`), /permission denied/);

  const openId = (await as(bettor,
    `SELECT (public.create_bet('${bettingGroup}', 'Segundo resultado', 40)).id AS id`
  )).rows[0].id;
  const exclusion = (await as(acceptor, `SELECT (public.self_exclude_betting()).self_excluded_until AS until`)).rows[0].until;
  assert.ok(new Date(exclusion).getTime() > Date.now() + 29 * 24 * 60 * 60 * 1000);
  assert.ok(new Date(exclusion).getTime() < Date.now() + 31 * 24 * 60 * 60 * 1000);
  await assert.rejects(as(acceptor, `SELECT (public.accept_bet('${openId}')).id`), /autoexclusión/i);
  await assert.rejects(as(acceptor, 'SELECT public.self_exclude_betting()'), /ya está activa/i);

  // The group owner can remove a member, but bet participants retain their own full history.
  await as(bettor, `SELECT public.remove_betting_group_member('${bettingGroup}', '${acceptor}')`);
  assert.equal(count(await as(acceptor, `SELECT count(*) FROM public.bets WHERE id = '${createdId}'`)), 1);
  await assert.rejects(as(bettor, `SELECT public.remove_betting_group_member('${bettingGroup}', '${bettor}')`), /no puede expulsarse/i);
});

test('payment/reputation/moderation server-owned fields cannot be forged', async () => {
  await assert.rejects(as(user, `INSERT INTO public.payments(user_id, product_type, target_id, amount, status) VALUES ('${user}', 'boost', '${listing}', 1, 'confirmed')`), /permission denied/);
  await assert.rejects(as(user, 'UPDATE public.profiles SET reputation_avg = 5'), /permission denied/);
  await assert.rejects(as(user, `INSERT INTO public.moderation_actions(target_type, target_id, source, category, severity, action, reason) VALUES ('user', '${peer}', 'admin', 'x', 1, 'ban', 'x')`), /permission denied/);
});

test('monetization: order → server-hashed proof → review → confirm/reject, blacklist and notifications', async () => {
  await db.exec(`UPDATE public.listings SET municipality_id = 101, status = 'active' WHERE id = '${listing}'`);
  assert.equal(count(await as(user, 'SELECT count(*) FROM public.monetization_products')), 0);
  await assert.rejects(
    as(user, `SELECT * FROM public.create_monetization_payment('${listing}', 'boost_24h')`),
    /producto de promoción ya no está disponible/
  );
  await as(peer, `SELECT * FROM public.update_monetization_product_price('boost_24h', 100, true)`, 'authenticated', 'cashier');
  await assert.rejects(
    as(user, `SELECT * FROM public.create_monetization_payment('${listing}', 'boost_24h')`),
    /cuenta Transfermóvil.*no está configurada/
  );
  await as(peer, `SELECT * FROM public.update_monetization_settings('12345678', 'La Madriguera')`, 'authenticated', 'cashier');
  assert.equal((await as(peer, 'SELECT public.is_payment_reviewer()', 'authenticated', 'cashier')).rows[0].is_payment_reviewer, true);
  assert.equal(count(await as(peer, `SELECT count(*) FROM storage.objects WHERE bucket_id = 'payment-proofs'`, 'authenticated', 'cashier')), 0);
  await assert.rejects(
    as(outsider, 'SELECT * FROM public.payment_review_queue()', 'authenticated', 'user', { role: 'cashier' }),
    /Solo un cobrador\/admin/
  );

  const order1 = (await as(user, `SELECT * FROM public.create_monetization_payment('${listing}', 'boost_24h')`)).rows[0];
  assert.match(order1.code, /^LM-[0-9A-F]{4}$/);
  assert.equal(order1.status, 'pending');
  assert.equal(order1.amount, '100');
  await db.exec(`UPDATE public.payments SET created_at = now() - interval '2 hours', expires_at = now() + interval '22 hours' WHERE id = '${order1.id}'`);
  const proof1 = `${user}/${order1.id}/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.jpg`;
  await db.exec(`INSERT INTO storage.objects(bucket_id, name, owner) VALUES ('payment-proofs', '${proof1}', '${user}')`);
  const hash1 = 'a'.repeat(64);
  const submitted = (await as(admin, `SELECT * FROM public.submit_monetization_proof(
    '${user}', '${order1.id}', '${proof1}', '${hash1}', '4321', now() - interval '20 minutes'
  )`, 'service_role', 'admin')).rows[0];
  assert.equal(submitted.status, 'review');
  assert.equal((await db.query(`SELECT status FROM public.listings WHERE id = '${listing}'`)).rows[0].status, 'paused_payment');
  assert.equal((await as(peer, 'SELECT * FROM public.payment_review_queue()', 'authenticated', 'cashier')).rows.length, 1);
  assert.equal(count(await as(peer, `SELECT count(*) FROM storage.objects WHERE bucket_id = 'payment-proofs'`, 'authenticated', 'cashier')), 1);
  await assert.rejects(as(user, 'SELECT * FROM public.payment_review_queue()'), /Solo un cobrador\/admin/);
  await assert.rejects(as(user, `SELECT * FROM public.submit_monetization_proof(
    '${user}', '${order1.id}', '${proof1}', '${hash1}', '4321', now()
  )`), /permission denied/);

  await assert.rejects(as(peer, `SELECT * FROM public.confirm_monetization_payment(
    '${order1.id}', 100, now(), '4321', 'TM-TIME-MISMATCH'
  )`, 'authenticated', 'cashier'), /horario del extracto difiere más de 15 minutos/);
  const confirmed = (await as(peer, `SELECT * FROM public.confirm_monetization_payment(
    '${order1.id}', 100, now() - interval '20 minutes', '4321', 'TM-0001'
  )`, 'authenticated', 'cashier')).rows[0];
  assert.equal(confirmed.status, 'confirmed');
  const activated = await db.query(`SELECT status, boosted_until FROM public.listings WHERE id = '${listing}'`);
  assert.equal(activated.rows[0].status, 'active');
  assert.ok(activated.rows[0].boosted_until);
  assert.equal((await db.query(`SELECT product_type, tier FROM public.boosts WHERE payment_id = '${order1.id}'`)).rows[0].product_type, 'boost');

  const order2 = (await as(user, `SELECT * FROM public.create_monetization_payment('${listing}', 'boost_24h')`)).rows[0];
  const proof2 = `${user}/${order2.id}/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.png`;
  await db.exec(`INSERT INTO storage.objects(bucket_id, name, owner) VALUES ('payment-proofs', '${proof2}', '${user}')`);
  const hash2 = 'b'.repeat(64);
  await as(admin, `SELECT * FROM public.submit_monetization_proof(
    '${user}', '${order2.id}', '${proof2}', '${hash2}', '9876', now()
  )`, 'service_role', 'admin');
  const rejected = (await as(peer, `SELECT * FROM public.reject_monetization_payment(
    '${order2.id}', 'El monto no coincide con el extracto', true
  )`, 'authenticated', 'cashier')).rows[0];
  assert.equal(rejected.status, 'rejected');
  assert.equal(rejected.rejection_reason, 'El monto no coincide con el extracto');
  assert.equal((await db.query(`SELECT status FROM public.listings WHERE id = '${listing}'`)).rows[0].status, 'active');
  assert.equal(count(await as(peer, `SELECT count(*) FROM public.payment_receipt_blacklist WHERE receipt_sha256 = '${hash2}'`, 'authenticated', 'cashier')), 1);

  const order3 = (await as(user, `SELECT * FROM public.create_monetization_payment('${listing}', 'boost_24h')`)).rows[0];
  const proof3 = `${user}/${order3.id}/cccccccc-cccc-4ccc-8ccc-cccccccccccc.jpg`;
  await db.exec(`INSERT INTO storage.objects(bucket_id, name, owner) VALUES ('payment-proofs', '${proof3}', '${user}')`);
  await assert.rejects(as(admin, `SELECT * FROM public.submit_monetization_proof(
    '${user}', '${order3.id}', '${proof3}', '${hash2}', '9876', now()
  )`, 'service_role', 'admin'), /comprobante está bloqueado/);
  assert.ok(count(await db.query(`SELECT count(*) FROM public.user_notifications WHERE user_id = '${user}' AND type IN ('payment_confirmed', 'payment_rejected')`)) >= 2);
});

test('numeric precision, bytea and constraints are native PostgreSQL types', async () => {
  await db.exec(`UPDATE public.listings SET price = 12345678901234567890.123456789 WHERE id = '${listing}'`);
  const result = await db.query(`SELECT price::text AS price FROM public.listings WHERE id = '${listing}'`);
  assert.equal(result.rows[0].price, '12345678901234567890.123456789');
  await assert.rejects(db.exec(`UPDATE public.listings SET price = -1 WHERE id = '${listing}'`), /check constraint/);
  await assert.rejects(db.exec(`UPDATE public.listings SET price = 'NaN' WHERE id = '${listing}'`), /check constraint/);
  await assert.rejects(db.exec(`UPDATE public.listings SET price = 'Infinity' WHERE id = '${listing}'`), /check constraint/);
  await assert.rejects(db.exec(`INSERT INTO public.reviews(reviewer_id, reviewee_id, conversation_id, rating) VALUES ('${user}', '${peer}', '${conversation}', 6)`), /check constraint/);
  assert.equal((await db.query("SELECT encode(doc_ref, 'hex') AS hex FROM public.identity_verifications WHERE user_id = '" + user + "'")).rows[0].hex, '00ff');
});

test.after(async () => { await db.close(); });

let approvedTicketId;

test('chat crypto: chat key publish, conversation_keys participant-scoped', async () => {
  await as(user, `UPDATE public.profiles SET chat_public_key = '\\x110022' WHERE user_id = '${user}'`);
  const published = await as(peer, `SELECT chat_public_key::text AS key FROM public.profiles WHERE user_id = '${user}'`);
  assert.equal(published.rows[0].key, '\\x110022');
  const forged = await as(outsider, `UPDATE public.profiles SET chat_public_key = '\\x00' WHERE user_id = '${user}' RETURNING user_id`);
  assert.equal(forged.rows.length, 0);

  // Los sobres de sesión solo son legibles por participantes y solo se
  // depositan para miembros del hilo; no se pueden editar ni borrar.
  await as(user, `INSERT INTO public.conversation_keys(conversation_id, user_id, wrapped_key) VALUES ('${conversation}', '${user}', '\\x0a0b')`);
  await as(user, `INSERT INTO public.conversation_keys(conversation_id, user_id, wrapped_key) VALUES ('${conversation}', '${peer}', '\\x0a0b')`);
  assert.equal(count(await as(peer, 'SELECT count(*) FROM public.conversation_keys')), 2);
  assert.equal(count(await as(outsider, 'SELECT count(*) FROM public.conversation_keys')), 0);
  await assert.rejects(as(outsider, `INSERT INTO public.conversation_keys(conversation_id, user_id, wrapped_key) VALUES ('${conversation}', '${outsider}', '\\x0a0b')`), /row-level security/);
  await assert.rejects(as(user, `INSERT INTO public.conversation_keys(conversation_id, user_id, wrapped_key) VALUES ('${conversation}', '${outsider}', '\\x0a0b')`), /row-level security/);
  await assert.rejects(as(user, `UPDATE public.conversation_keys SET wrapped_key = '\\x00'`), /permission denied/);
  await assert.rejects(as(user, 'DELETE FROM public.conversation_keys'), /permission denied/);
});

test('dispute tickets: report opens them once; only moderators resolve', async () => {
  await db.exec(`INSERT INTO public.conversations(id, listing_id, participant_a, participant_b)
    VALUES ('${conversation2}', '${listing}', '${user}', '${peer}')`);
  await assert.rejects(as(outsider, `SELECT * FROM public.report_conversation('${conversation2}', 'estafa')`), /Only participants/);
  await assert.rejects(as(user, `SELECT * FROM public.report_conversation('${conversation2}', '')`), /Invalid dispute reason/);

  const first = await as(user, `SELECT * FROM public.report_conversation('${conversation2}', 'estafa')`);
  assert.equal(first.rows[0].status, 'open');
  assert.equal(first.rows[0].opened_by, user);
  // Idempotente: un solo ticket vivo por conversación.
  const second = await as(peer, `SELECT * FROM public.report_conversation('${conversation2}', 'abuso')`);
  assert.equal(second.rows[0].id, first.rows[0].id);
  approvedTicketId = first.rows[0].id;

  const conv = await db.query(`SELECT status FROM public.conversations WHERE id = '${conversation2}'`);
  assert.equal(conv.rows[0].status, 'disputed');
  assert.equal(count(await as(user, `SELECT count(*) FROM public.reports WHERE target_id = '${conversation2}'`)), 1);

  // Visibilidad: ambas partes y moderación, nunca terceros.
  assert.equal(count(await as(outsider, 'SELECT count(*) FROM public.dispute_tickets')), 0);
  assert.equal(count(await as(peer, `SELECT count(*) FROM public.dispute_tickets WHERE conversation_id = '${conversation2}'`)), 1);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.dispute_tickets', 'authenticated', 'moderator')), 1);

  await assert.rejects(as(user, `SELECT * FROM public.resolve_dispute_ticket('${approvedTicketId}', true)`), /Only moderators/);
  const resolved = await as(admin, `SELECT * FROM public.resolve_dispute_ticket('${approvedTicketId}', true)`, 'authenticated', 'moderator');
  assert.equal(resolved.rows[0].status, 'approved');
  assert.equal(resolved.rows[0].approved_by, admin);
  await assert.rejects(as(admin, `SELECT * FROM public.resolve_dispute_ticket('${approvedTicketId}', true)`, 'authenticated', 'moderator'), /Ticket not found or already resolved/);
});

test('expired ciphertext is purged server-side, only by the service role', async () => {
  await db.exec(`
    INSERT INTO public.messages(conversation_id, sender_id, content_ref, expires_at) VALUES
      ('${conversation}', '${user}', 'expired-ciphertext', now() - interval '1 hour'),
      ('${conversation}', '${user}', 'future-ciphertext', now() + interval '1 hour');
  `);
  await assert.rejects(as(user, 'SELECT public.purge_expired_messages()'), /permission denied/);
  await assert.rejects(as(user, `DELETE FROM public.messages WHERE content_ref = 'expired-ciphertext'`), /permission denied/);
  const result = await db.query('SELECT public.purge_expired_messages() AS purged');
  assert.equal(Number(result.rows[0].purged), 1);
  const remaining = await db.query(`SELECT content_ref FROM public.messages WHERE expires_at IS NOT NULL`);
  assert.deepEqual(remaining.rows.map(row => row.content_ref), ['future-ciphertext']);
});

test('moderation_actions is append-only: immutable decrypt logs (who, when, ticket, reason)', async () => {
  await db.exec(`INSERT INTO public.moderation_actions(target_type, target_id, source, category, severity, action, reason, actor_id)
    VALUES ('conversation', '${conversation2}', 'admin', 'decryption', 3, 'decrypt_conversation', 'Ticket ${approvedTicketId ?? 'unknown'}: estafa', '${admin}')`);
  await assert.rejects(db.query(`UPDATE public.moderation_actions SET reason = 'x'`), /append-only/);
  await assert.rejects(db.query(`DELETE FROM public.moderation_actions WHERE target_id = '${conversation2}'`), /append-only/);
  await assert.rejects(as(admin, `UPDATE public.moderation_actions SET reason = 'x'`, 'service_role'), /append-only|permission denied/);
  // El log sigue disponible para moderación.
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.moderation_actions', 'authenticated', 'moderator')), 1);
});

test('thread decryption notifies both participants; only the service can create them', async () => {
  await assert.rejects(as(user, `INSERT INTO public.user_notifications(user_id, type, title, body) VALUES ('${peer}', 'thread_decrypted', 't', 'b')`), /row-level security|permission denied/);
  await assert.rejects(as(user, `SELECT public.notify_thread_decrypted('${conversation2}', 'x')`), /permission denied/);

  await db.query(`SELECT public.notify_thread_decrypted('${conversation2}', 'estafa')`);
  const mine = await as(user, `SELECT title, body FROM public.user_notifications WHERE type = 'thread_decrypted'`);
  assert.equal(mine.rows.length, 1);
  assert.equal(mine.rows[0].title, 'Tu hilo fue descifrado por un moderador');
  assert.match(mine.rows[0].body, /^Motivo: estafa/);
  assert.equal(count(await as(outsider, 'SELECT count(*) FROM public.user_notifications')), 0);

  // Solo puedo marcar como leídas las mías y únicamente el campo read_at.
  const read = await as(peer, `UPDATE public.user_notifications SET read_at = now() WHERE user_id = '${peer}' RETURNING id`);
  assert.equal(read.rows.length, 1);
  await assert.rejects(as(peer, `UPDATE public.user_notifications SET body = 'x'`), /permission denied/);
});

test('dispute transcripts are written by the service and are moderator-only', async () => {
  await db.exec(`INSERT INTO public.dispute_transcripts(ticket_id, conversation_id, actor_id, reason, transcript)
    VALUES ('${approvedTicketId}', '${conversation2}', '${admin}', 'estafa', '[]'::jsonb)`);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.dispute_transcripts', 'authenticated', 'moderator')), 1);
  assert.equal(count(await as(user, 'SELECT count(*) FROM public.dispute_transcripts')), 0);
  assert.equal(count(await as(outsider, 'SELECT count(*) FROM public.dispute_transcripts')), 0);
  await assert.rejects(as(user, `INSERT INTO public.dispute_transcripts(ticket_id, conversation_id, reason, transcript) VALUES ('${approvedTicketId}', '${conversation2}', 'x', '[]'::jsonb)`), /row-level security|permission denied/);
});

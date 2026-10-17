import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { PGlite } from '@electric-sql/pglite';

// PostgreSQL WASM, not a SQL parser/mock. Only Supabase Auth is stubbed.
const user = '00000000-0000-0000-0000-000000000001';
const peer = '00000000-0000-0000-0000-000000000002';
const outsider = '00000000-0000-0000-0000-000000000003';
const admin = '00000000-0000-0000-0000-000000000004';
const listing = '10000000-0000-0000-0000-000000000001';
const paused = '10000000-0000-0000-0000-000000000002';
const conversation = '20000000-0000-0000-0000-000000000001';
const group = '30000000-0000-0000-0000-000000000001';

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
  CREATE TABLE auth.users (id uuid PRIMARY KEY, phone text);
  CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb
  $$;
  CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
    SELECT (auth.jwt()->>'sub')::uuid
  $$;
  GRANT USAGE ON SCHEMA auth TO anon, authenticated, service_role;
  GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA auth TO anon, authenticated, service_role;
`);
await db.exec(await readFile(new URL('../migrations/001_initial_schema.sql', import.meta.url), 'utf8'));
await db.exec(`
  INSERT INTO auth.users(id) VALUES ('${user}'), ('${peer}'), ('${outsider}'), ('${admin}');
  INSERT INTO public.categories(id, name) VALUES ('40000000-0000-0000-0000-000000000001', 'General');
  INSERT INTO public.listings(id, owner_id, title, description, category_id, price, status) VALUES
    ('${listing}', '${user}', 'Oferta', 'Descripción', '40000000-0000-0000-0000-000000000001', 10, 'active'),
    ('${paused}', '${user}', 'Privada', 'Descripción', '40000000-0000-0000-0000-000000000001', 10, 'closed');
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

test('all 16 application tables have RLS enabled', async () => {
  const result = await db.query("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND rowsecurity");
  assert.equal(count(result), 16);
});

test('Kotlin row wire names match every SQL column', async () => {
  const mappings = {
    User: 'users', IdentityVerification: 'identity_verifications', Profile: 'profiles',
    Category: 'categories', Listing: 'listings', Bid: 'bids', Conversation: 'conversations',
    Message: 'messages', Review: 'reviews', Report: 'reports', Payment: 'payments',
    Boost: 'boosts', BettingGroup: 'betting_groups', BettingGroupMember: 'betting_group_members',
    Bet: 'bets', ModerationAction: 'moderation_actions'
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
    ListingKind: 'listing_kind', SaleMode: 'sale_mode', ListingStatus: 'listing_status',
    BidStatus: 'bid_status', RevealPolicy: 'reveal_policy', MessageType: 'message_type',
    ProductType: 'product_type', PaymentStatus: 'payment_status', BetStatus: 'bet_status',
    ModerationSource: 'moderation_source'
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
  assert.equal((await as(user, "UPDATE public.identity_verifications SET status = 'verified' RETURNING id")).rows.length, 0);
  assert.equal((await as(admin, "UPDATE public.identity_verifications SET status = 'verified' RETURNING id", 'authenticated', 'admin')).rows.length, 1);
});

test('listings: public active feed; only owner writes; boost cannot be forged', async () => {
  assert.equal(count(await as(null, 'SELECT count(*) FROM public.listings', 'anon')), 1);
  assert.equal(count(await as(user, 'SELECT count(*) FROM public.listings')), 2);
  assert.equal((await as(peer, `UPDATE public.listings SET title = 'hack' WHERE id = '${listing}' RETURNING id`)).rows.length, 0);
  assert.equal((await as(user, `UPDATE public.listings SET title = 'Nuevo' WHERE id = '${listing}' RETURNING id`)).rows.length, 1);
  await assert.rejects(as(user, "UPDATE public.listings SET boosted_until = now() + interval '1 year'"), /permission denied/);
  await assert.rejects(as(user, `UPDATE public.listings SET owner_id = '${peer}'`), /permission denied/);
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

test('bets: group membership required for reading/writing, even for admin; no self-enrolment', async () => {
  assert.equal(count(await as(user, 'SELECT count(*) FROM public.bets')), 1);
  assert.equal(count(await as(peer, 'SELECT count(*) FROM public.bets')), 1);
  assert.equal(count(await as(outsider, 'SELECT count(*) FROM public.bets')), 0);
  assert.equal(count(await as(admin, 'SELECT count(*) FROM public.bets', 'authenticated', 'admin')), 0);
  await assert.rejects(as(outsider, `INSERT INTO public.betting_group_members(group_id, user_id) VALUES ('${group}', '${outsider}')`), /row-level security/);
  await assert.rejects(as(outsider, `INSERT INTO public.bets(group_id, creator_id, description, stake_amount) VALUES ('${group}', '${outsider}', 'x', 1)`), /row-level security/);
  await assert.rejects(as(peer, "UPDATE public.bets SET status = 'settled_acceptor'"), /permission denied/);
  await as(user, `DELETE FROM public.betting_group_members WHERE group_id = '${group}' AND user_id = '${peer}'`);
  assert.equal(count(await as(peer, 'SELECT count(*) FROM public.bets')), 0);
});

test('payment/reputation/moderation server-owned fields cannot be forged', async () => {
  await assert.rejects(as(user, `INSERT INTO public.payments(user_id, product_type, target_id, amount, status) VALUES ('${user}', 'boost', '${listing}', 1, 'confirmed')`), /row-level security/);
  await assert.rejects(as(user, 'UPDATE public.profiles SET reputation_avg = 5'), /permission denied/);
  await assert.rejects(as(user, `INSERT INTO public.moderation_actions(target_type, target_id, source, category, severity, action, reason) VALUES ('user', '${peer}', 'admin', 'x', 1, 'ban', 'x')`), /permission denied/);
});

test('numeric precision, bytea and constraints are native PostgreSQL types', async () => {
  await db.exec(`UPDATE public.listings SET price = 12345678901234567890.123456789 WHERE id = '${listing}'`);
  const result = await db.query(`SELECT price::text AS price FROM public.listings WHERE id = '${listing}'`);
  assert.equal(result.rows[0].price, '12345678901234567890.123456789');
  await assert.rejects(db.exec(`UPDATE public.listings SET price = -1 WHERE id = '${listing}'`), /check constraint/);
  await assert.rejects(db.exec(`UPDATE public.listings SET price = 'NaN' WHERE id = '${listing}'`), /check constraint/);
  await assert.rejects(db.exec(`UPDATE public.listings SET price = 'Infinity' WHERE id = '${listing}'`), /check constraint/);
  await assert.rejects(db.exec(`INSERT INTO public.reviews(reviewer_id, reviewee_id, conversation_id, rating) VALUES ('${user}', '${peer}', '${conversation}', 6)`), /check constraint/);
  assert.equal((await db.query("SELECT encode(doc_ref, 'hex') AS hex FROM public.identity_verifications")).rows[0].hex, '00ff');
});

test.after(async () => { await db.close(); });

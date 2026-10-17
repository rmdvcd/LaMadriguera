/**
 * Edge Function `dispute-decrypt` — proceso backend de disputa (diseño C).
 *
 * Es el ÚNICO componente que recombina la clave privada de disputa:
 *   1. Lee tickets `approved` pendientes de descifrar (quórum humano previo:
 *      un moderador aprobó el ticket; las privadas de custodia viven como
 *      secretos separados de esta función).
 *   2. Para cada ticket (y SOLO esa conversación):
 *      - desenvuelve `dispute_key_split_a` con la privada del admin general y
 *        `dispute_key_split_b` con la privada del moderador senior;
 *      - XOR → privada de disputa del hilo → desenvuelve `session_key_wrapped`;
 *      - descifra los mensajes vigentes y exporta la transcripción a
 *        `dispute_transcripts` (solo lectura de moderación);
 *      - escribe el LOG INMUTABLE en `moderation_actions` (quién aprobó,
 *        cuándo, ticket y motivo);
 *      - avisa a AMBAS partes vía `notify_thread_decrypted`;
 *      - marca el ticket como `decrypted`.
 *
 * Despliegue (Supabase CLI):
 *   supabase functions deploy dispute-decrypt --no-verify-jwt
 *   supabase secrets set \
 *     DISPUTE_ADMIN_PRIVATE_KEY_B64=... DISPUTE_MODERATOR_PRIVATE_KEY_B64=...
 * Las dos privadas deben gestionarse como custodios independientes (nunca en
 * un mismo gestor de secretos con mismo control de acceso).
 *
 * Invocación: programada (pg_cron - supabase schedule) o manual service_role:
 *   POST /functions/v1/dispute-decrypt  Authorization: Bearer <service_role>
 */
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';
import {
  base64ToBytes,
  combineHalfKeys,
  decryptContentRef,
  hexToBytes,
  messageAad,
  unwrapSecret,
  utf8,
  wrapAad,
  PURPOSE_CUSTODIAN_WRAP,
  PURPOSE_DISPUTE_WRAP,
} from './crypto.mjs';

const LOG_ACTION = 'decrypt_conversation';
const LOG_CATEGORY = 'decryption';

interface DisputeTicket {
  id: string;
  conversation_id: string;
  opened_by: string;
  reason: string;
  status: string;
  approved_by: string | null;
}

interface ConversationCryptoRow {
  id: string;
  session_key_wrapped: string | null;
  dispute_key_split_a: string | null;
  dispute_key_split_b: string | null;
}

interface MessageCipherRow {
  id: string;
  sender_id: string;
  type: 'text' | 'image' | 'audio' | 'location';
  content_ref: string;
  expires_at: string | null;
  created_at: string;
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Falta el secreto ${name}`);
  return value;
}

async function decryptTicket(
  client: ReturnType<typeof createClient>,
  ticket: DisputeTicket,
  adminPrivate: Uint8Array,
  moderatorPrivate: Uint8Array,
): Promise<{ messages: number }> {
  // 1. Material del hilo (y nada más).
  const { data: conversation, error: conversationError } = await client
    .from('conversations')
    .select('id, session_key_wrapped, dispute_key_split_a, dispute_key_split_b')
    .eq('id', ticket.conversation_id)
    .single();
  if (conversationError) throw conversationError;
  const row = conversation as ConversationCryptoRow;
  if (!row.session_key_wrapped || !row.dispute_key_split_a || !row.dispute_key_split_b) {
    throw new Error('El hilo no tiene material criptográfico completo');
  }

  // 2. Quórum 2-de-2: cada mitad se desenvuelve con su custodio.
  const aad = wrapAad(row.id);
  const splitA = await unwrapSecret(
    hexToBytes(row.dispute_key_split_a), adminPrivate, aad, PURPOSE_CUSTODIAN_WRAP,
  );
  const splitB = await unwrapSecret(
    hexToBytes(row.dispute_key_split_b), moderatorPrivate, aad, PURPOSE_CUSTODIAN_WRAP,
  );
  const disputePrivate = combineHalfKeys(splitA, splitB);
  const sessionKey = await unwrapSecret(
    hexToBytes(row.session_key_wrapped), disputePrivate, aad, PURPOSE_DISPUTE_WRAP,
  );

  // 3. Descifra SOLO los mensajes de esta conversación (los ya expirados los
  //    retiró purge_expired_messages y no se recuperan).
  const { data: messages, error: messagesError } = await client
    .from('messages')
    .select('id, sender_id, type, content_ref, expires_at, created_at')
    .eq('conversation_id', row.id)
    .order('created_at', { ascending: true });
  if (messagesError) throw messagesError;

  const transcript = [];
  let undecryptable = 0;
  for (const message of (messages as MessageCipherRow[]) ?? []) {
    try {
      const plaintext = await decryptContentRef(
        sessionKey, message.content_ref, messageAad(row.id, message.type),
      );
      transcript.push({
        id: message.id,
        sender_id: message.sender_id,
        type: message.type,
        created_at: message.created_at,
        payload: JSON.parse(new TextDecoder().decode(plaintext)),
      });
    } catch {
      undecryptable += 1;
      transcript.push({
        id: message.id,
        sender_id: message.sender_id,
        type: message.type,
        created_at: message.created_at,
        undecryptable: true,
      });
    }
  }

  const actor = ticket.approved_by ?? ticket.opened_by;
  const reason = `Ticket ${ticket.id}: ${ticket.reason}`;

  // 4. Transcripción para moderación + LOG INMUTABLE + aviso a ambas partes.
  const { error: transcriptError } = await client.from('dispute_transcripts').insert({
    ticket_id: ticket.id,
    conversation_id: row.id,
    actor_id: actor,
    reason,
    transcript,
  });
  if (transcriptError) throw transcriptError;

  const { error: logError } = await client.from('moderation_actions').insert({
    target_type: 'conversation',
    target_id: row.id,
    source: 'admin',
    category: LOG_CATEGORY,
    severity: 3,
    action: LOG_ACTION,
    reason,
    actor_id: actor,
  });
  if (logError) throw logError;

  const { error: notifyError } = await client.rpc('notify_thread_decrypted', {
    p_conversation_id: row.id,
    p_reason: reason,
  });
  if (notifyError) throw notifyError;

  const { error: closeError } = await client
    .from('dispute_tickets')
    .update({ status: 'decrypted', decrypted_at: new Date().toISOString() })
    .eq('id', ticket.id);
  if (closeError) throw closeError;

  return { messages: transcript.length - undecryptable };
}

Deno.serve(async (req: Request) => {
  if (req.method !== 'POST') {
    return new Response('Method Not Allowed', { status: 405 });
  }
  try {
    const client = createClient(
      requiredEnv('SUPABASE_URL'),
      requiredEnv('SUPABASE_SERVICE_ROLE_KEY'),
      { auth: { persistSession: false } },
    );
    const adminPrivate = base64ToBytes(requiredEnv('DISPUTE_ADMIN_PRIVATE_KEY_B64'));
    const moderatorPrivate = base64ToBytes(requiredEnv('DISPUTE_MODERATOR_PRIVATE_KEY_B64'));

    const { data: tickets, error } = await client
      .from('dispute_tickets')
      .select('id, conversation_id, opened_by, reason, status, approved_by')
      .eq('status', 'approved')
      .limit(20);
    if (error) throw error;

    const results: Record<string, unknown>[] = [];
    for (const ticket of (tickets as DisputeTicket[]) ?? []) {
      try {
        const summary = await decryptTicket(client, ticket, adminPrivate, moderatorPrivate);
        results.push({ ticket: ticket.id, ok: true, ...summary });
      } catch (ticketError) {
        // Un ticket defectuoso no detiene el lote; queda para reintento.
        results.push({ ticket: ticket.id, ok: false, error: String(ticketError) });
      }
    }
    return Response.json({ processed: results.length, results });
  } catch (error) {
    return Response.json({ error: String((error as Error)?.message ?? error) }, { status: 500 });
  }
});

// Referencia para el editor: `utf8` forma parte del formato AAD compartido.
void utf8;

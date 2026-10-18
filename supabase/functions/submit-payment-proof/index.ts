/**
 * submit-payment-proof — hashing confiable del comprobante de Transfermóvil.
 *
 * El cliente sube la imagen al bucket privado y envía solamente la ruta, los
 * últimos cuatro dígitos y la fecha/hora declarada. Esta función vuelve a leer
 * los bytes con service_role, calcula SHA-256 en servidor y llama a la RPC que
 * cambia payment a review y pausa la publicación de forma atómica.
 *
 * Despliegue: supabase functions deploy submit-payment-proof
 * Secretos del proyecto: SUPABASE_URL, SUPABASE_ANON_KEY y
 * SUPABASE_SERVICE_ROLE_KEY (la clave de servicio nunca se distribuye a Android).
 */
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

const BUCKET = 'payment-proofs';
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Falta la configuración del servidor: ${name}`);
  return value;
}

function isSupportedImage(bytes: Uint8Array): boolean {
  const jpeg = bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  const png = bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 &&
    bytes[2] === 0x4e && bytes[3] === 0x47 && bytes[4] === 0x0d &&
    bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a;
  return jpeg || png;
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function jsonError(message: string, status: number): Response {
  return Response.json({ error: message }, { status });
}

Deno.serve(async (request: Request) => {
  if (request.method === 'OPTIONS') {
    return new Response('ok', {
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'authorization, apikey, content-type',
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
      },
    });
  }
  if (request.method !== 'POST') return jsonError('Método no permitido', 405);

  try {
    const authorization = request.headers.get('Authorization');
    if (!authorization?.startsWith('Bearer ')) return jsonError('Sesión no válida', 401);
    const accessToken = authorization.slice('Bearer '.length).trim();
    if (!accessToken) return jsonError('Sesión no válida', 401);

    const url = requiredEnv('SUPABASE_URL');
    const anonKey = requiredEnv('SUPABASE_ANON_KEY');
    const serviceKey = requiredEnv('SUPABASE_SERVICE_ROLE_KEY');
    const userClient = createClient(url, anonKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: userData, error: authError } = await userClient.auth.getUser(accessToken);
    if (authError || !userData.user) return jsonError('Sesión no válida o vencida', 401);

    const payload = await request.json() as {
      payment_id?: string;
      proof_ref?: string;
      transfer_last4?: string;
      transfer_at?: string;
    };
    const paymentId = payload.payment_id?.trim() ?? '';
    const proofRef = payload.proof_ref?.trim() ?? '';
    const last4 = payload.transfer_last4?.trim() ?? '';
    const transferAt = payload.transfer_at?.trim() ?? '';
    if (!/^[0-9a-f-]{36}$/i.test(paymentId) ||
        !/^[0-9]{4}$/.test(last4) || Number.isNaN(Date.parse(transferAt))) {
      return jsonError('Datos del comprobante no válidos', 400);
    }
    const expectedPrefix = `${userData.user.id}/${paymentId}/`;
    if (!proofRef.startsWith(expectedPrefix) || !/\.(jpg|png)$/.test(proofRef)) {
      return jsonError('Ruta de captura no válida para esta cuenta', 403);
    }

    const serviceClient = createClient(url, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: file, error: downloadError } = await serviceClient.storage
      .from(BUCKET)
      .download(proofRef);
    if (downloadError || !file) return jsonError('No se pudo leer la captura subida', 400);

    const buffer = await file.arrayBuffer();
    if (buffer.byteLength === 0 || buffer.byteLength > MAX_IMAGE_BYTES) {
      return jsonError('La captura debe pesar como máximo 5 MB', 413);
    }
    const bytes = new Uint8Array(buffer);
    if (!isSupportedImage(bytes)) return jsonError('La captura debe ser JPEG o PNG', 415);

    const digest = await crypto.subtle.digest('SHA-256', buffer);
    const receiptHash = hex(new Uint8Array(digest));
    const { data: payment, error: submitError } = await serviceClient.rpc(
      'submit_monetization_proof',
      {
        p_user_id: userData.user.id,
        p_payment_id: paymentId,
        p_proof_ref: proofRef,
        p_receipt_sha256: receiptHash,
        p_transfer_last4: last4,
        p_transfer_at: new Date(transferAt).toISOString(),
      },
    );
    if (submitError) {
      const status = submitError.code === '42501' ? 403 : submitError.code === '23505' ? 409 : 400;
      return jsonError(submitError.message, status);
    }

    const row = Array.isArray(payment) ? payment[0] : payment;
    return Response.json(
      { payment: row, receipt_sha256: receiptHash },
      { headers: { 'Access-Control-Allow-Origin': '*' } },
    );
  } catch (error) {
    console.error('submit-payment-proof failed', error);
    return jsonError('No se pudo enviar el comprobante. Inténtalo de nuevo.', 500);
  }
});

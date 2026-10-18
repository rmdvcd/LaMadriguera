/**
 * push-dispatch — Repartidor de notificaciones para la distribución fuera de
 * Google Play.
 *
 * Lo invoca la BD (webhook AFTER INSERT sobre `user_notifications`, ver
 * migración 009) con la fila recién creada. Por cada aviso:
 *
 *   1. Push FCM  → HTTP v1 a todos los tokens de `user_push_tokens`
 *                  (OAuth2 con la cuenta de servicio de Firebase; nunca se
 *                  envían claves de servidor a Android).
 *   2. Fallback Telegram → SOLO si el usuario activó el bot y compartió su
 *      chat_id, y además el push no era posible o falló para todos los
 *      dispositivos.
 *
 * La bandeja in-app no vive aquí: `user_notifications` + Realtime ya la
 * alimentan aunque esta función no exista (dispositivos sin Play Services).
 *
 * Despliegue: supabase functions deploy push-dispatch --no-verify-jwt
 * Secretos del proyecto (supabase secrets set ...):
 *   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY   (lectura/limpieza de tablas)
 *   PUSH_DISPATCH_SECRET                      (header x-push-secret del webhook)
 *   FIREBASE_PROJECT_ID                       (id del proyecto Firebase)
 *   FIREBASE_SERVICE_ACCOUNT_JSON             (JSON de la cuenta de servicio:
 *                                              client_email + private_key)
 *   TELEGRAM_BOT_TOKEN                        (opcional: bot de avisos; sin él,
 *                                              no hay fallback de Telegram)
 *
 * Privacidad: el aviso push NO contiene contenido de chat (E2E); solo el
 * título y cuerpo genéricos que la propia BD escribió en user_notifications.
 */
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

const PUSH_TYPES_CHANNEL: Record<string, string> = {
  oferta_recibida: 'madriguera_ofertas',
  mensaje_nuevo: 'madriguera_mensajes',
  subasta_ganada: 'madriguera_subastas',
  payment_confirmed: 'madriguera_pagos',
  payment_rejected: 'madriguera_pagos',
  payment_cancelled: 'madriguera_pagos',
};

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Falta la configuración del servidor: ${name}`);
  return value;
}

function jsonError(message: string, status: number): Response {
  return Response.json({ error: message }, { status });
}

interface NotificationRow {
  id: string;
  user_id: string;
  type: string;
  title: string;
  body: string;
}

interface Settings {
  push_enabled: boolean;
  notify_offers: boolean;
  notify_messages: boolean;
  notify_payments: boolean;
  notify_auctions: boolean;
  telegram_enabled: boolean;
  telegram_chat_id: string | null;
}

function typeAllowed(settings: Settings, type: string): boolean {
  if (!settings.push_enabled) return false;
  switch (type) {
    case 'oferta_recibida': return settings.notify_offers;
    case 'mensaje_nuevo': return settings.notify_messages;
    case 'subasta_ganada': return settings.notify_auctions;
    case 'payment_confirmed':
    case 'payment_rejected':
    case 'payment_cancelled': return settings.notify_payments;
    default: return true; // avisos de seguridad/moderación: siempre que el maestro esté ON
  }
}

// ---------------------------------------------------------------------------
// OAuth2 (JWT RS256 con la clave de la cuenta de servicio de Firebase)
// ---------------------------------------------------------------------------

let cachedAccessToken: { token: string; expiresAt: number } | null = null;

function base64Url(bytes: Uint8Array | string): string {
  const raw = typeof bytes === 'string' ? new TextEncoder().encode(bytes) : bytes;
  let binary = '';
  raw.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}

async function signRs256(signingInput: string, privateKeyPem: string): Promise<string> {
  const pemBody = privateKeyPem
    .replace(/-----BEGIN PRIVATE KEY-----/, '')
    .replace(/-----END PRIVATE KEY-----/, '')
    .replace(/\s/g, '');
  const der = Uint8Array.from(atob(pemBody), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    'pkcs8',
    der,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(signingInput));
  return base64Url(new Uint8Array(signature));
}

async function googleAccessToken(serviceAccount: { client_email: string; private_key: string }): Promise<string> {
  if (cachedAccessToken && Date.now() < cachedAccessToken.expiresAt - 60_000) {
    return cachedAccessToken.token;
  }
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: serviceAccount.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    exp: now + 3600,
    iat: now,
  };
  const assertion = `${base64Url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.` +
    `${base64Url(JSON.stringify(claims))}.${await signRs256(
      `${base64Url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${base64Url(JSON.stringify(claims))}`,
      serviceAccount.private_key,
    )}`;
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  });
  if (!response.ok) throw new Error(`OAuth2 falló (${response.status}): ${await response.text()}`);
  const payload = await response.json() as { access_token: string; expires_in: number };
  cachedAccessToken = { token: payload.access_token, expiresAt: Date.now() + payload.expires_in * 1000 };
  return cachedAccessToken.token;
}

// ---------------------------------------------------------------------------
// Envíos
// ---------------------------------------------------------------------------

/** true si FCM respondió OK; 'stale' si el token ya no es válido; false si falló. */
async function sendFcm(
  accessToken: string,
  projectId: string,
  notification: NotificationRow,
  token: string,
): Promise<'ok' | 'stale' | 'error'> {
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: {
        token,
        android: {
          priority: 'HIGH',
          notification: {
            title: notification.title,
            body: notification.body,
            channel_id: PUSH_TYPES_CHANNEL[notification.type] ?? 'madriguera_general',
          },
          data: {
            notification_id: notification.id,
            type: notification.type,
            title: notification.title,
            body: notification.body,
          },
        },
      },
    }),
  });
  if (response.ok) return 'ok';
  if (response.status === 403 || response.status === 404) return 'stale';
  return 'error';
}

async function sendTelegram(botToken: string, chatId: string, notification: NotificationRow): Promise<boolean> {
  const text = `🐾 <b>${escapeHtml(notification.title)}</b>\n${escapeHtml(notification.body)}`;
  const response = await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      chat_id: chatId,
      text,
      parse_mode: 'HTML',
      disable_web_page_preview: true,
    }),
  });
  return response.ok;
}

function escapeHtml(value: string): string {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
}

Deno.serve(async (request: Request) => {
  if (request.method !== 'POST') return jsonError('Método no permitido', 405);

  const secret = Deno.env.get('PUSH_DISPATCH_SECRET') ?? '';
  const provided = request.headers.get('x-push-secret') ?? '';
  if (!secret || provided !== secret) return jsonError('No autorizado', 401);

  let notification: NotificationRow | null = null;
  try {
    const payload = await request.json() as { record?: NotificationRow };
    if (payload.record?.id && payload.record.user_id) notification = payload.record;
  } catch {
    return jsonError('Payload inválido', 400);
  }
  if (!notification) return Response.json({ ignored: true }); // UPDATE/DELETE u otro evento

  const supabase = createClient(
    requiredEnv('SUPABASE_URL'),
    requiredEnv('SUPABASE_SERVICE_ROLE_KEY'),
    { auth: { persistSession: false, autoRefreshToken: false } },
  );

  const [{ data: settingsRow }, { data: deviceRows }] = await Promise.all([
    supabase.from('user_notification_settings').select('*').eq('user_id', notification.user_id).maybeSingle(),
    supabase.from('user_push_tokens').select('id, token').eq('user_id', notification.user_id),
  ]);

  const settings: Settings = {
    push_enabled: settingsRow?.push_enabled ?? true,
    notify_offers: settingsRow?.notify_offers ?? true,
    notify_messages: settingsRow?.notify_messages ?? true,
    notify_payments: settingsRow?.notify_payments ?? true,
    notify_auctions: settingsRow?.notify_auctions ?? true,
    telegram_enabled: settingsRow?.telegram_enabled ?? false,
    telegram_chat_id: settingsRow?.telegram_chat_id ?? null,
  };

  const delivered: string[] = [];
  const staleTokenIds: string[] = [];
  let pushAttempted = false;

  if (typeAllowed(settings, notification.type)) {
    const projectId = Deno.env.get('FIREBASE_PROJECT_ID');
    const accountJson = Deno.env.get('FIREBASE_SERVICE_ACCOUNT_JSON');
    if (projectId && accountJson && (deviceRows?.length ?? 0) > 0) {
      pushAttempted = true;
      try {
        const serviceAccount = JSON.parse(accountJson) as { client_email: string; private_key: string };
        const accessToken = await googleAccessToken(serviceAccount);
        for (const device of deviceRows ?? []) {
          const outcome = await sendFcm(accessToken, projectId, notification, device.token);
          if (outcome === 'ok') delivered.push(`fcm:${device.id}`);
          if (outcome === 'stale') staleTokenIds.push(device.id);
        }
      } catch (error) {
        console.error('push-dispatch: fallo de FCM', error);
      }
    }
  }

  // Fallback Telegram: solo si el usuario lo activó con chat_id propio y el
  // push no llegó (sin Firebase, sin dispositivos registrados o todo falló).
  let telegramSent = false;
  const botToken = Deno.env.get('TELEGRAM_BOT_TOKEN');
  if (
    botToken && settings.telegram_enabled && settings.telegram_chat_id &&
    delivered.length === 0 && typeAllowed({ ...settings, push_enabled: true }, notification.type)
  ) {
    telegramSent = await sendTelegram(botToken, settings.telegram_chat_id, notification);
    if (telegramSent) delivered.push('telegram');
  }

  // Higiene: tokens que FCM ya no reconoce se retiran.
  if (staleTokenIds.length > 0) {
    await supabase.from('user_push_tokens').delete().in('id', staleTokenIds);
  }

  return Response.json({
    notification_id: notification.id,
    push_attempted: pushAttempted,
    delivered,
    telegram_fallback_sent: telegramSent,
    stale_tokens_removed: staleTokenIds.length,
  });
});

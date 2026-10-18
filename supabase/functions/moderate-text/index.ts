/**
 * moderate-text — llamada a la API de moderación de texto configurable por
 * variables de entorno.
 *
 * El cliente envía el texto ANTES de publicar (listings, mensajes de grupos
 * públicos, apuestas, perfiles). Esta función normaliza la respuesta del
 * proveedor; la decisión (bloquear / dudoso / permitir) la toma el caso de uso
 * ModerateContentUseCase en Android. La clave del proveedor nunca viaja al APK.
 *
 * Variables de entorno del proyecto (supabase secrets):
 *   MODERATION_PROVIDER   'openai' | 'llamaguard' | 'none'   (default: none)
 *   MODERATION_API_URL    URL del endpoint del proveedor (opcional)
 *   MODERATION_API_KEY    clave del proveedor (opcional si provider = none)
 *
 * Despliegue: supabase functions deploy moderate-text
 * Secretos del proyecto: SUPABASE_URL, SUPABASE_ANON_KEY (verificación de sesión).
 */
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';
import {
  buildLlamaGuardPrompt,
  normalizeLlamaGuard,
  normalizeOpenAiModeration,
} from './mapping.mjs';

const DEFAULT_ENDPOINTS = {
  openai: 'https://api.openai.com/v1/moderations',
  llamaguard: 'https://api.groq.com/openai/v1/chat/completions',
};

function env(name) {
  return Deno.env.get(name)?.trim() ?? '';
}

function jsonError(message, status) {
  return Response.json({ error: message }, { status });
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
  };
}

async function callOpenAiModeration(text) {
  const url = env('MODERATION_API_URL') || DEFAULT_ENDPOINTS.openai;
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${env('MODERATION_API_KEY')}`,
    },
    body: JSON.stringify({ model: 'omni-moderation-latest', input: text }),
  });
  if (!response.ok) {
    throw new Error(`El proveedor de moderación respondió ${response.status}`);
  }
  return normalizeOpenAiModeration(await response.json());
}

async function callLlamaGuard(text) {
  const url = env('MODERATION_API_URL') || DEFAULT_ENDPOINTS.llamaguard;
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${env('MODERATION_API_KEY')}`,
    },
    body: JSON.stringify({
      model: env('MODERATION_MODEL') || 'llama-guard-3-8b',
      messages: buildLlamaGuardPrompt(text),
      temperature: 0,
      max_tokens: 64,
    }),
  });
  if (!response.ok) {
    throw new Error(`El proveedor de moderación respondió ${response.status}`);
  }
  const payload = await response.json();
  const outputText = payload?.choices?.[0]?.message?.content ?? '';
  return normalizeLlamaGuard(outputText);
}

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders() });
  }
  if (request.method !== 'POST') return jsonError('Método no permitido', 405);

  try {
    const authorization = request.headers.get('Authorization');
    if (!authorization?.startsWith('Bearer ')) return jsonError('Sesión no válida', 401);
    const accessToken = authorization.slice('Bearer '.length).trim();
    if (!accessToken) return jsonError('Sesión no válida', 401);

    const url = env('SUPABASE_URL');
    const anonKey = env('SUPABASE_ANON_KEY');
    if (!url || !anonKey) return jsonError('Falta la configuración del servidor', 500);
    const userClient = createClient(url, anonKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: userData, error: authError } = await userClient.auth.getUser(accessToken);
    if (authError || !userData.user) return jsonError('Sesión no válida o vencida', 401);

    const payload = await request.json();
    const text = String(payload?.text ?? '').trim();
    if (!text) return jsonError('Envía el texto a moderar', 400);
    if (text.length > 8000) return jsonError('El texto supera el máximo moderable', 400);

    const provider = (env('MODERATION_PROVIDER') || 'none').toLowerCase();
    if (provider === 'none') {
      return Response.json(
        { provider: 'none', flagged: false, categories: {}, scores: {} },
        { headers: corsHeaders() },
      );
    }
    if (!env('MODERATION_API_KEY')) {
      return jsonError('El proveedor de moderación no está configurado en el servidor', 503);
    }

    const verdict = provider === 'openai'
      ? await callOpenAiModeration(text)
      : provider === 'llamaguard'
        ? await callLlamaGuard(text)
        : null;
    if (!verdict) return jsonError(`Proveedor de moderación desconocido: ${provider}`, 500);

    return Response.json(verdict, { headers: corsHeaders() });
  } catch (error) {
    console.error('moderate-text:', error?.message ?? error);
    return jsonError('No se pudo completar la moderación del texto', 502);
  }
});

// Mapeo puro de respuestas de proveedores de moderación al formato canónico
// de La Madriguera. Sin dependencias de Deno: se prueba con node --test.
//
// Categorías canónicas:
//   violence, harassment, sexual, illegal_gambling   → GRAVES (bloquean)
//   self_harm, hate, spam, other                      → dudosas (cola humana)

export const SEVERE_CATEGORIES = ['violence', 'harassment', 'sexual', 'illegal_gambling'];

// OpenAI Moderation (omni-moderation) → canónico. 'illicit' cubre actividades
// ilegales; el caso relevante en este producto son apuestas/loterías clandestinas.
const OPENAI_CATEGORY_MAP = {
  'violence': 'violence',
  'violence/graphic': 'violence',
  'harassment': 'harassment',
  'harassment/threatening': 'harassment',
  'hate': 'harassment',
  'hate/threatening': 'harassment',
  'sexual': 'sexual',
  'sexual/minors': 'sexual',
  'illicit': 'illegal_gambling',
  'illicit/violent': 'illegal_gambling',
  'self-harm': 'self_harm',
  'self-harm/intent': 'self_harm',
  'self-harm/instructions': 'self_harm',
};

// Llama Guard 3 (códigos S1..S13) → canónico.
const LLAMA_GUARD_CODE_MAP = {
  S1: 'violence',            // Violent Crimes
  S2: 'illegal_gambling',    // Non-Violent Crimes (fraude, juego clandestino…)
  S3: 'sexual',              // Sex-Related Crimes
  S4: 'sexual',              // Child Sexual Exploitation
  S5: 'harassment',          // Defamation
  S6: 'other',               // Specialized Advice
  S7: 'other',               // Privacy
  S8: 'other',               // Intellectual Property
  S9: 'violence',            // Indiscriminate Weapons
  S10: 'harassment',         // Hate
  S11: 'self_harm',          // Suicide & Self-Harm
  S12: 'sexual',             // Sexual Content
  S13: 'other',              // Elections
};

function foldCategory(target, canonical, score) {
  if (!canonical) return;
  const previous = target[canonical] ?? 0;
  target[canonical] = Math.max(previous, score);
}

/** Normaliza la respuesta de POST /v1/moderations de OpenAI. */
export function normalizeOpenAiModeration(json) {
  const result = json?.results?.[0];
  if (!result) throw new Error('Respuesta inesperada del proveedor de moderación');
  const categories = {};
  const scores = {};
  const rawScores = result.category_scores ?? {};
  for (const [rawName, rawScore] of Object.entries(rawScores)) {
    scores[rawName] = Number(rawScore) || 0;
  }
  // `categories` solo recoge categorías MARCADAS (flagged); si el proveedor no
  // dio score para una marcada, se considera certeza total (1).
  for (const [rawName, flagged] of Object.entries(result.categories ?? {})) {
    if (!flagged) continue;
    const score = Number(rawScores[rawName] ?? 1) || 1;
    foldCategory(categories, OPENAI_CATEGORY_MAP[rawName] ?? 'other', score);
  }
  const flagged = Boolean(result.flagged) || Object.keys(categories).length > 0;
  return { provider: 'openai', flagged, categories, scores };
}

/**
 * Normaliza la salida de Llama Guard ("safe" o "unsafe\\nS2\\nS13" y variantes
 * con nombres de categoría). `outputText` es el texto de la respuesta.
 */
export function normalizeLlamaGuard(outputText) {
  const text = String(outputText ?? '').trim();
  const unsafe = /^unsafe/i.test(text);
  const categories = {};
  const scores = {};
  if (unsafe) {
    const codes = [...text.matchAll(/\bS(\d{1,2})\b/gi)].map((match) => `S${Number(match[1])}`);
    const seen = new Set(codes.length > 0 ? codes : ['S2']);
    for (const code of seen) {
      const canonical = LLAMA_GUARD_CODE_MAP[code] ?? 'other';
      scores[code] = 1;
      foldCategory(categories, canonical, 1);
    }
  }
  return { provider: 'llamaguard', flagged: unsafe, categories, scores };
}

/**
 * Prompt en el formato de conversación de Llama Guard 3. El modelo responde
 * "safe" o "unsafe" seguido de los códigos de categoría (S1..S13).
 */
export function buildLlamaGuardPrompt(text) {
  return [
    {
      role: 'system',
      content:
        'You are Llama Guard, a safety classifier. Reply with "safe" if the content is safe, ' +
        'or "unsafe" followed by the violated category codes (S1-S13), one per line: ' +
        'S1 Violent Crimes, S2 Non-Violent Crimes (fraud, theft, arson, hacking, illegal gambling), ' +
        'S3 Sex-Related Crimes, S4 Child Sexual Exploitation, S5 Defamation, S6 Specialized Advice, ' +
        'S7 Privacy, S8 Intellectual Property, S9 Indiscriminate Weapons, S10 Hate, ' +
        'S11 Suicide & Self-Harm, S12 Sexual Content, S13 Elections.',
    },
    { role: 'user', content: String(text ?? '') },
  ];
}

/** Mezcla resultados de varios proveedores (el peor caso gana). */
export function mergeVerdicts(verdicts) {
  const merged = { flagged: false, categories: {}, scores: {}, providers: [] };
  for (const verdict of verdicts) {
    merged.flagged = merged.flagged || Boolean(verdict.flagged);
    merged.providers.push(verdict.provider);
    for (const [name, score] of Object.entries(verdict.categories ?? {})) {
      foldCategory(merged.categories, name, score);
    }
    Object.assign(merged.scores, verdict.scores ?? {});
  }
  return merged;
}

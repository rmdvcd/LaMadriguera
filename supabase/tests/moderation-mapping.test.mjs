import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  SEVERE_CATEGORIES,
  buildLlamaGuardPrompt,
  mergeVerdicts,
  normalizeLlamaGuard,
  normalizeOpenAiModeration,
} from '../functions/moderate-text/mapping.mjs';

test('OpenAI moderation maps provider categories to the canonical severe set', () => {
  const verdict = normalizeOpenAiModeration({
    results: [{
      flagged: true,
      categories: { 'violence': true, 'illicit': true, 'harassment': false },
      category_scores: {
        'violence': 0.92,
        'violence/graphic': 0.41,
        'illicit': 0.88,
        'harassment': 0.02,
        'sexual': 0.0,
        'self-harm': 0.0,
      },
    }],
  });
  assert.equal(verdict.provider, 'openai');
  assert.equal(verdict.flagged, true);
  assert.equal(verdict.categories.violence, 0.92);
  assert.equal(verdict.categories.illegal_gambling, 0.88);
  assert.ok(SEVERE_CATEGORIES.includes('violence'));
  assert.ok(SEVERE_CATEGORIES.includes('illegal_gambling'));
  assert.equal(verdict.categories.harassment, undefined);
});

test('OpenAI sexual content becomes a severe sexual category', () => {
  const verdict = normalizeOpenAiModeration({
    results: [{
      flagged: true,
      categories: { 'sexual': true },
      category_scores: { 'sexual': 0.97, 'sexual/minors': 0.01 },
    }],
  });
  assert.equal(verdict.categories.sexual, 0.97);
  assert.ok(SEVERE_CATEGORIES.includes('sexual'));
});

test('Llama Guard safe output flags nothing', () => {
  const verdict = normalizeLlamaGuard('safe');
  assert.equal(verdict.flagged, false);
  assert.deepEqual(verdict.categories, {});
});

test('Llama Guard unsafe codes map to canonical categories', () => {
  const verdict = normalizeLlamaGuard('unsafe\nS1\nS2\nS12');
  assert.equal(verdict.flagged, true);
  assert.equal(verdict.categories.violence, 1);
  assert.equal(verdict.categories.illegal_gambling, 1);
  assert.equal(verdict.categories.sexual, 1);
});

test('Llama Guard named-category output still parses', () => {
  const verdict = normalizeLlamaGuard('unsafe\nS3 Sex-Related Crimes');
  assert.equal(verdict.categories.sexual, 1);
});

test('Llama Guard prompt wraps content for the guard model', () => {
  const messages = buildLlamaGuardPrompt('hola');
  assert.equal(messages.at(-1).content, 'hola');
  assert.match(messages[0].content, /S1/);
});

test('mergeVerdicts keeps the worst score per canonical category', () => {
  const merged = mergeVerdicts([
    { provider: 'openai', flagged: true, categories: { sexual: 0.9 }, scores: { sexual: 0.9 } },
    { provider: 'llamaguard', flagged: true, categories: { violence: 1 }, scores: { S1: 1 } },
  ]);
  assert.equal(merged.flagged, true);
  assert.equal(merged.categories.sexual, 0.9);
  assert.equal(merged.categories.violence, 1);
  assert.deepEqual(merged.providers, ['openai', 'llamaguard']);
});

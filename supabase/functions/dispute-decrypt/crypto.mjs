/**
 * Criptografía del proceso backend de disputa (diseño C).
 *
 * Espejo 1:1 de la capa `core/security/chat` del cliente Android:
 *  - X25519 (RFC 7748) con BigInt, con rechazo de orden pequeño.
 *  - HKDF-SHA256 (RFC 5869) y AES-256-GCM vía WebCrypto.
 *  - Sobres ECIES: 0x01 || ephPub(32) || iv(12) || ciphertext || tag(16).
 *  - Mitades XOR de la clave privada de disputa (quórum 2-de-2).
 *  - content_ref: "v1." + base64(iv(12) || ciphertext || tag(16)) con AAD
 *    `msg:<conversationId>:<tipo>` — exactamente el formato del cliente.
 *
 * Módulo ESM portable: lo importan la Edge Function (Deno) y los tests (Node).
 */
const P = (1n << 255n) - 19n;
const P_MINUS_2 = P - 2n;
const A24 = 121665n;
const KEY_BYTES = 32;
const IV_BYTES = 12;
const BLOB_VERSION = 0x01;
const BASE_POINT = new Uint8Array(32);
BASE_POINT[0] = 9;

export const PURPOSE_DISPUTE_WRAP = 'lmchat/wrap/dispute/v1';
export const PURPOSE_SESSION_WRAP = 'lmchat/wrap/session/v1';
export const PURPOSE_CUSTODIAN_WRAP = 'lmchat/wrap/custodian/v1';

// --- Utilidades de bytes ---------------------------------------------------

export function concat(...arrays) {
  const out = new Uint8Array(arrays.reduce((n, a) => n + a.length, 0));
  let offset = 0;
  for (const a of arrays) { out.set(a, offset); offset += a.length; }
  return out;
}

export function utf8(text) {
  return new TextEncoder().encode(text);
}

export function base64ToBytes(value) {
  const bin = atob(value);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function bytesToBase64(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

/** Formato bytea de PostgREST: "\\x" + hex minúsculas. */
export function hexToBytes(hex) {
  const clean = hex.startsWith('\\x') ? hex.slice(2) : hex;
  if (clean.length % 2 !== 0) throw new Error('bytea hex inválido');
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

/** Fuente de aleatoriedad inyectable (tests deterministas). */
let randomSource = (bytes) => crypto.getRandomValues(bytes);
export function setRandomSource(fn) {
  randomSource = fn;
}

// --- X25519 (RFC 7748) ------------------------------------------------------

function mod(value) {
  const r = value % P;
  return r >= 0n ? r : r + P;
}

function leBytesToBigInt(bytes) {
  let x = 0n;
  for (let i = bytes.length - 1; i >= 0; i--) {
    x = (x << 8n) | BigInt(bytes[i]);
  }
  return x;
}

function bigIntToLeBytes(value, length = KEY_BYTES) {
  const out = new Uint8Array(length);
  let x = value;
  for (let i = 0; i < length; i++) {
    out[i] = Number(x & 0xffn);
    x >>= 8n;
  }
  return out;
}

function decodeScalar(scalar) {
  const k = scalar.slice(0, KEY_BYTES);
  k[0] &= 248;
  k[KEY_BYTES - 1] &= 127;
  k[KEY_BYTES - 1] |= 64;
  return leBytesToBigInt(k);
}

function decodeU(u) {
  const v = u.slice(0, KEY_BYTES);
  v[KEY_BYTES - 1] &= 127;
  return mod(leBytesToBigInt(v));
}

function modPow(base, exp) {
  let result = 1n;
  let b = mod(base);
  let e = exp;
  while (e > 0n) {
    if (e & 1n) result = mod(result * b);
    b = mod(b * b);
    e >>= 1n;
  }
  return result;
}

function scalarMult(scalar, u) {
  const k = decodeScalar(scalar);
  const x1 = decodeU(u);
  let x2 = 1n, z2 = 0n, x3 = x1, z3 = 1n, swap = 0;
  for (let t = 254; t >= 0; t--) {
    const kt = Number((k >> BigInt(t)) & 1n);
    swap ^= kt;
    if (swap === 1) { [x2, x3] = [x3, x2]; [z2, z3] = [z3, z2]; }
    swap = kt;
    const a = x2 + z2;
    const aa = a * a;
    const b = x2 - z2;
    const bb = b * b;
    const e = aa - bb;
    const c = x3 + z3;
    const d = x3 - z3;
    const da = d * a;
    const cb = c * b;
    const daPlusCb = mod(da + cb);
    const daMinusCb = da - cb;
    x3 = mod(daPlusCb * daPlusCb);
    z3 = mod(x1 * mod(daMinusCb * daMinusCb));
    x2 = mod(aa * bb);
    z2 = mod(e * (aa + A24 * e));
  }
  if (swap === 1) { [x2, x3] = [x3, x2]; [z2, z3] = [z3, z2]; }
  return bigIntToLeBytes(mod(x2 * modPow(z2, P_MINUS_2)));
}

export function x25519PublicKey(privateKey) {
  if (privateKey.length !== KEY_BYTES) throw new Error('La privada X25519 debe tener 32 bytes');
  return scalarMult(privateKey, BASE_POINT);
}

export function x25519SharedSecret(privateKey, peerPublicKey) {
  if (privateKey.length !== KEY_BYTES || peerPublicKey.length !== KEY_BYTES) {
    throw new Error('Las claves X25519 deben tener 32 bytes');
  }
  if (peerPublicKey.every((b) => b === 0)) throw new Error('Clave pública de orden pequeño');
  const shared = scalarMult(privateKey, peerPublicKey);
  if (shared.every((b) => b === 0)) throw new Error('Secreto compartido de orden pequeño');
  return shared;
}

// --- HKDF-SHA256 (WebCrypto) -------------------------------------------------

async function hkdf(ikm, salt, info, length) {
  const key = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt: salt ?? new Uint8Array(32), info },
    key,
    length * 8
  );
  return new Uint8Array(bits);
}

// --- AES-256-GCM (WebCrypto) --------------------------------------------------

async function aesGcmEncryptRaw(key, plaintext, aad) {
  const iv = randomSource(new Uint8Array(IV_BYTES));
  const k = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']);
  const ct = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 }, k, plaintext
  );
  return concat(iv, new Uint8Array(ct));
}

async function aesGcmDecryptRaw(key, raw, aad) {
  if (raw.length <= IV_BYTES + 16) throw new Error('Ciphertext demasiado corto');
  const iv = raw.slice(0, IV_BYTES);
  const ct = raw.slice(IV_BYTES);
  const k = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['decrypt']);
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 }, k, ct
  );
  return new Uint8Array(plain);
}

// --- Sobres ECIES del diseño C -------------------------------------------------

export function generateKeyPair() {
  const privateKey = randomSource(new Uint8Array(KEY_BYTES));
  return { privateKey, publicKey: x25519PublicKey(privateKey) };
}

export async function wrapSecret(secret, recipientPublicKey, aad, purpose) {
  const ephemeral = generateKeyPair();
  const shared = x25519SharedSecret(ephemeral.privateKey, recipientPublicKey);
  const key = await hkdf(shared, ephemeral.publicKey, utf8(purpose), KEY_BYTES);
  const encrypted = await aesGcmEncryptRaw(key, secret, aad);
  return concat(new Uint8Array([BLOB_VERSION]), ephemeral.publicKey, encrypted);
}

export async function unwrapSecret(blob, recipientPrivateKey, aad, purpose) {
  if (blob.length <= 1 + KEY_BYTES) throw new Error('Sobre demasiado corto');
  if (blob[0] !== BLOB_VERSION) throw new Error('Versión de sobre no soportada');
  const ephemeralPublic = blob.slice(1, 1 + KEY_BYTES);
  const encrypted = blob.slice(1 + KEY_BYTES);
  const shared = x25519SharedSecret(recipientPrivateKey, ephemeralPublic);
  const key = await hkdf(shared, ephemeralPublic, utf8(purpose), KEY_BYTES);
  return aesGcmDecryptRaw(key, encrypted, aad);
}

// --- Mitades XOR de custodia (2 de 2) ------------------------------------------

export function splitPrivateKey(privateKey) {
  const splitA = randomSource(new Uint8Array(KEY_BYTES));
  const splitB = new Uint8Array(KEY_BYTES);
  for (let i = 0; i < KEY_BYTES; i++) splitB[i] = privateKey[i] ^ splitA[i];
  return [splitA, splitB];
}

export function combineHalfKeys(splitA, splitB) {
  if (splitA.length !== splitB.length) throw new Error('Las mitades deben tener el mismo tamaño');
  const out = new Uint8Array(splitA.length);
  for (let i = 0; i < out.length; i++) out[i] = splitA[i] ^ splitB[i];
  return out;
}

// --- Mensajes del chat (content_ref) -------------------------------------------

export function messageAad(conversationId, type) {
  return utf8(`msg:${conversationId}:${type}`);
}

export function wrapAad(conversationId) {
  return utf8(`wrap:${conversationId}`);
}

/** Descifra un content_ref del cliente: "v1." + base64(iv || ct || tag). */
export async function decryptContentRef(sessionKey, contentRef, aad) {
  if (!contentRef.startsWith('v1.')) throw new Error('Formato de content_ref no soportado');
  const raw = base64ToBytes(contentRef.slice(3));
  return aesGcmDecryptRaw(sessionKey, raw, aad);
}

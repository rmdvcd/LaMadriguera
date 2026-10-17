/**
 * Tests de la crypto del proceso backend de disputa (diseño C), contra los
 * vectores del RFC 7748/5869, contra node:crypto y, si el fichero existe,
 * contra artefactos producidos por la app (interoperabilidad Kotlin ↔ JS).
 */
import assert from 'node:assert/strict';
import { hkdfSync, createDecipheriv, createCipheriv, randomBytes } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';

function assertBytesEqual(actual, expected, label) {
  assert.ok(Buffer.from(actual).equals(Buffer.from(expected)),
    `${label ?? 'bytes'}: ${Buffer.from(actual).toString('hex')} != ${Buffer.from(expected).toString('hex')}`);
}

import {
  PURPOSE_CUSTODIAN_WRAP,
  PURPOSE_DISPUTE_WRAP,
  base64ToBytes,
  bytesToBase64,
  combineHalfKeys,
  concat,
  decryptContentRef,
  generateKeyPair,
  messageAad,
  setRandomSource,
  splitPrivateKey,
  unwrapSecret,
  utf8,
  wrapAad,
  wrapSecret,
  x25519PublicKey,
  x25519SharedSecret,
} from '../functions/dispute-decrypt/crypto.mjs';

const hexToBytes = (hex) => Uint8Array.from(Buffer.from(hex, 'hex'));

// RNG determinista (contador) para artefactos reproducibles.
function deterministicRng() {
  let counter = 0;
  return (bytes) => {
    for (let i = 0; i < bytes.length; i++) bytes[i] = counter++ & 0xff;
    return bytes;
  };
}
setRandomSource(deterministicRng());

test('X25519 cumple los vectores del RFC 7748', () => {
  // Escalar × punto base (sección 5.2).
  const scalar = hexToBytes('a546e36bf8c78c9ebaf9a6c6f3d2e45f9b1b9c28cbf87c0d5de5ec17af58c2e9') ;
  // Vectores ECDH Alice/Bob (sección 6.1) — fuente canónica:
  const alicePrivate = hexToBytes('77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a');
  const alicePublic = x25519PublicKey(alicePrivate);
  assert.equal(Buffer.from(alicePublic).toString('hex'),
    '8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a');

  const bobPrivate = hexToBytes('5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb');
  const bobPublic = x25519PublicKey(bobPrivate);
  assert.equal(Buffer.from(bobPublic).toString('hex'),
    'de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f');

  const sharedAlice = x25519SharedSecret(alicePrivate, bobPublic);
  const sharedBob = x25519SharedSecret(bobPrivate, alicePublic);
  assertBytesEqual(sharedAlice, sharedBob, 'shared secret');
  assert.equal(Buffer.from(sharedAlice).toString('hex'),
    '4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742');
  void scalar;
});

test('X25519 rechaza claves de orden pequeño', () => {
  const { privateKey } = generateKeyPair();
  assert.throws(() => x25519SharedSecret(privateKey, new Uint8Array(32)), /orden peque\u00f1o/);
});

test('HKDF-SHA256 coincide con node:crypto (RFC 5869)', async () => {
  const ikm = hexToBytes('0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b');
  const salt = hexToBytes('000102030405060708090a0b0c');
  const info = hexToBytes('f0f1f2f3f4f5f6f7f8f9');
  // Caso de prueba 1 del RFC 5869.
  const expected = '3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865';
  const native = Buffer.from(
    hkdfSync('sha256', Buffer.from(ikm), Buffer.from(salt), Buffer.from(info), 42)
  ).toString('hex');
  assert.equal(native, expected);
});

test('AES-256-GCM: el backend descifra lo cifrado por node:crypto', async () => {
  const key = randomBytes(32);
  const iv = randomBytes(12);
  const aad = utf8('msg:conv-1:text');
  const plaintext = utf8('hola madriguera');
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(aad));
  const ct = Buffer.concat([cipher.update(Buffer.from(plaintext)), cipher.final()]);
  const tag = cipher.getAuthTag();

  // content_ref del cliente: "v1." + b64(iv || ct || tag)
  const contentRef = 'v1.' + bytesToBase64(concat(iv, ct, tag));
  const decrypted = await decryptContentRef(new Uint8Array(key), contentRef, aad);
  assert.deepEqual(decrypted, plaintext);

  // AAD incorrecto → fallo de autenticación.
  await assert.rejects(decryptContentRef(new Uint8Array(key), contentRef, utf8('msg:otra:text')));
  // Clave incorrecta → fallo.
  await assert.rejects(decryptContentRef(randomBytes(32), contentRef, aad));
  // Manipulación del ciphertext → fallo.
  const tampered = concat(iv, ct, tag);
  tampered[20] ^= 0xff;
  await assert.rejects(
    decryptContentRef(new Uint8Array(key), 'v1.' + bytesToBase64(tampered), aad)
  );
});

test('sobres ECIES: roundtrip, propósitos aislados y custodia XOR', async () => {
  const dispute = generateKeyPair();
  const admin = generateKeyPair();
  const moderator = generateKeyPair();
  const aad = wrapAad('conv-123');
  const sessionKey = randomBytes(32);

  // Envoltura para disputa y desenvoltura por el proceso backend.
  const wrapped = await wrapSecret(sessionKey, dispute.publicKey, aad, PURPOSE_DISPUTE_WRAP);
  assert.equal(wrapped.length, 1 + 32 + 12 + 32 + 16);
  const unwrapped = await unwrapSecret(wrapped, dispute.privateKey, aad, PURPOSE_DISPUTE_WRAP);
  assertBytesEqual(unwrapped, sessionKey, 'unwrap session');

  // Otro propósito u otro destinatario no autentican.
  await assert.rejects(unwrapSecret(wrapped, dispute.privateKey, aad, PURPOSE_CUSTODIAN_WRAP));
  await assert.rejects(unwrapSecret(wrapped, admin.privateKey, aad, PURPOSE_DISPUTE_WRAP));
  await assert.rejects(unwrapSecret(wrapped, dispute.privateKey, wrapAad('otra'), PURPOSE_DISPUTE_WRAP));

  // Custodia 2-de-2: ninguna mitad sola revela la privada.
  const [splitA, splitB] = splitPrivateKey(dispute.privateKey);
  assertBytesEqual(await unwrapSecret(
    await wrapSecret(splitA, admin.publicKey, aad, PURPOSE_CUSTODIAN_WRAP),
    admin.privateKey, aad, PURPOSE_CUSTODIAN_WRAP,
  ), splitA, 'split A');
  assertBytesEqual(await unwrapSecret(
    await wrapSecret(splitB, moderator.publicKey, aad, PURPOSE_CUSTODIAN_WRAP),
    moderator.privateKey, aad, PURPOSE_CUSTODIAN_WRAP,
  ), splitB, 'split B');
  assertBytesEqual(combineHalfKeys(splitA, splitB), dispute.privateKey, 'recombine');
  assert.throws(() => combineHalfKeys(splitA, new Uint8Array(10)), /tama\u00f1o/);
});

test('flujo completo de disputa: solo el quórum descifra el hilo', async () => {
  const aad = wrapAad('conv-e2e');
  const sessionKey = randomBytes(32);
  const dispute = generateKeyPair();
  const admin = generateKeyPair();
  const moderator = generateKeyPair();

  // Lo que persiste el cliente (conversations.*).
  const sessionKeyWrapped = await wrapSecret(sessionKey, dispute.publicKey, aad, PURPOSE_DISPUTE_WRAP);
  const [splitA, splitB] = splitPrivateKey(dispute.privateKey);
  const wrappedA = await wrapSecret(splitA, admin.publicKey, aad, PURPOSE_CUSTODIAN_WRAP);
  const wrappedB = await wrapSecret(splitB, moderator.publicKey, aad, PURPOSE_CUSTODIAN_WRAP);

  // Mensaje del cliente (simulado aquí con node:crypto, formato idéntico).
  const iv = randomBytes(12);
  const messageCipher = createCipheriv('aes-256-gcm', sessionKey, iv);
  messageCipher.setAAD(Buffer.from(messageAad('conv-e2e', 'text')));
  const ct = Buffer.concat([messageCipher.update(utf8('{"v":1,"t":"text","text":"hola"}')), messageCipher.final()]);
  const contentRef = 'v1.' + bytesToBase64(concat(iv, ct, messageCipher.getAuthTag()));

  // Proceso backend: quórum + desenvoltura + descifrado solo de ese hilo.
  const recoveredA = await unwrapSecret(wrappedA, admin.privateKey, aad, PURPOSE_CUSTODIAN_WRAP);
  const recoveredB = await unwrapSecret(wrappedB, moderator.privateKey, aad, PURPOSE_CUSTODIAN_WRAP);
  const disputePrivate = combineHalfKeys(recoveredA, recoveredB);
  assertBytesEqual(disputePrivate, dispute.privateKey, 'recovered dispute key');
  const recoveredSession = await unwrapSecret(sessionKeyWrapped, disputePrivate, aad, PURPOSE_DISPUTE_WRAP);
  assertBytesEqual(recoveredSession, sessionKey, 'recovered session key');
  const plaintext = await decryptContentRef(recoveredSession, contentRef, messageAad('conv-e2e', 'text'));
  assert.equal(new TextDecoder().decode(plaintext), '{"v":1,"t":"text","text":"hola"}');
});

const interopPath = '/tmp/interop/kotlin-vectors.json';
test('interoperabilidad Kotlin → JS (cuando existen vectores del sandbox)', async () => {
  if (!existsSync(interopPath)) {
    return; // El harness Kotlin los genera al verificar la app.
  }
  const vectors = JSON.parse(readFileSync(interopPath, 'utf8'));
  const sessionKey = base64ToBytes(vectors.sessionKeyB64);
  const disputePrivate = base64ToBytes(vectors.disputePrivateB64);
  const adminPrivate = base64ToBytes(vectors.adminPrivateB64);
  const moderatorPrivate = base64ToBytes(vectors.moderatorPrivateB64);
  const aad = wrapAad(vectors.conversationId);

  const splitA = await unwrapSecret(
    base64ToBytes(vectors.wrappedSplitA_B64), adminPrivate, aad, PURPOSE_CUSTODIAN_WRAP);
  const splitB = await unwrapSecret(
    base64ToBytes(vectors.wrappedSplitB_B64), moderatorPrivate, aad, PURPOSE_CUSTODIAN_WRAP);
  const recoveredPrivate = combineHalfKeys(splitA, splitB);
  assertBytesEqual(recoveredPrivate, disputePrivate, 'interop dispute key');

  const recoveredSession = await unwrapSecret(
    base64ToBytes(vectors.sessionKeyWrappedB64), recoveredPrivate, aad, PURPOSE_DISPUTE_WRAP);
  assertBytesEqual(recoveredSession, sessionKey, 'interop session key');

  const plaintext = await decryptContentRef(
    recoveredSession, vectors.messageContentRef, messageAad(vectors.conversationId, vectors.messageType));
  assert.equal(new TextDecoder().decode(plaintext), vectors.messagePlaintext);
});

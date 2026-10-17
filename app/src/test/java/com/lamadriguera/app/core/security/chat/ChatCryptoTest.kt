package com.lamadriguera.app.core.security.chat

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

/**
 * Pruebas de la capa criptográfica del chat (diseño C):
 * X25519 contra RFC 7748, HKDF contra RFC 5869, AES-256-GCM roundtrip y
 * quórum de custodia 2-de-2 del flujo de disputa, todo en JVM pura.
 */
class ChatCryptoTest {

    private val random = SecureRandom()
    private val cipher = AesGcmCipher(random)
    private val envelope = DisputeEnvelope(cipher, random)

    // ------------------------------------------------------------------
    // X25519 — vectores del RFC 7748
    // ------------------------------------------------------------------

    @Test
    fun x25519MatchesRfc7748Vectors() {
        // Escalares × puntos arbitrarios (§5.2, ambos vectores; verificados
        // también contra libsodium en el sandbox).
        assertArrayEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552".hex(),
            X25519.sharedSecret(
                "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4".hex(),
                "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c".hex()
            )
        )
        assertArrayEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957".hex(),
            X25519.sharedSecret(
                "4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d".hex(),
                "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493".hex()
            )
        )

        // Alice/Bob (§6.1).
        val alicePrivate = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hex()
        assertArrayEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".hex(),
            X25519.publicKey(alicePrivate)
        )
        val bobPrivate = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hex()
        assertArrayEquals(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hex(),
            X25519.publicKey(bobPrivate)
        )
        val expectedShared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".hex()
        assertArrayEquals(expectedShared, X25519.sharedSecret(alicePrivate, X25519.publicKey(bobPrivate)))
        assertArrayEquals(expectedShared, X25519.sharedSecret(bobPrivate, X25519.publicKey(alicePrivate)))
    }

    @Test
    fun x25519RejectsSmallOrderInputs() {
        val pair = envelope.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            X25519.sharedSecret(pair.privateKey, ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            X25519.sharedSecret(pair.privateKey, ByteArray(31))
        }
    }

    // ------------------------------------------------------------------
    // HKDF-SHA256 — caso de prueba 1 del RFC 5869
    // ------------------------------------------------------------------

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = "000102030405060708090a0b0c".hex()
        val info = "f0f1f2f3f4f5f6f7f8f9".hex()
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.hex()
        )
    }

    @Test
    fun hkdfNullSaltEqualsZeroSalt() {
        val ikm = randomBytes(32)
        assertArrayEquals(
            Hkdf.derive(ikm, null, byteArrayOf(1), 32),
            Hkdf.derive(ikm, ByteArray(32), byteArrayOf(1), 32)
        )
    }

    // ------------------------------------------------------------------
    // AES-256-GCM
    // ------------------------------------------------------------------

    @Test
    fun aesGcmRoundTripAndAadBinding() {
        val key = cipher.generateSessionKey()
        val plaintext = "Hola, madriguera 🐹".toByteArray()
        val aad = "msg:conv-1:text".toByteArray()

        val encoded = cipher.encrypt(key, plaintext, aad)
        assertTrue(encoded.startsWith("v1."))
        assertArrayEquals(plaintext, cipher.decrypt(key, encoded, aad))

        // AAD distinto (otro hilo/tipo) → el tag no autentica.
        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(key, encoded, "msg:otra-conv:text".toByteArray())
        }
        // Clave distinta → no descifra.
        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(cipher.generateSessionKey(), encoded, aad)
        }
        // Formato desconocido → rechazo explícito.
        assertThrows(IllegalArgumentException::class.java) {
            cipher.decrypt(key, "v0." + encoded.substring(3), aad)
        }
        // IV únicos por mensaje con la misma clave.
        assertNotEquals(encoded, cipher.encrypt(key, plaintext, aad))
    }

    @Test
    fun aesGcmDetectsTampering() {
        val key = cipher.generateSessionKey()
        val aad = byteArrayOf(1, 2, 3)
        val raw = cipher.encryptRaw(key, "payload".toByteArray(), aad)
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x40).toByte()
        assertThrows(AEADBadTagException::class.java) {
            cipher.decryptRaw(key, raw, aad)
        }
    }

    // ------------------------------------------------------------------
    // Sobres ECIES y custodia de la privada de disputa
    // ------------------------------------------------------------------

    @Test
    fun envelopeRoundTripAndPurposeIsolation() {
        val recipient = envelope.generateKeyPair()
        val other = envelope.generateKeyPair()
        val secret = randomBytes(32)
        val aad = "wrap:conv-9".toByteArray()

        val blob = envelope.wrapSecret(secret, recipient.publicKey, aad, DisputeEnvelope.PURPOSE_DISPUTE_WRAP)
        assertEquals(DisputeEnvelope.BLOB_VERSION, blob[0])
        assertEquals(1 + 32 + 12 + 32 + 16, blob.size)
        assertArrayEquals(
            secret,
            envelope.unwrapSecret(blob, recipient.privateKey, aad, DisputeEnvelope.PURPOSE_DISPUTE_WRAP)
        )

        // Otro propósito, otro destinatario u otro hilo no desenvuelven.
        assertThrows(AEADBadTagException::class.java) {
            envelope.unwrapSecret(blob, recipient.privateKey, aad, DisputeEnvelope.PURPOSE_SESSION_WRAP)
        }
        assertThrows(AEADBadTagException::class.java) {
            envelope.unwrapSecret(blob, other.privateKey, aad, DisputeEnvelope.PURPOSE_DISPUTE_WRAP)
        }
        assertThrows(AEADBadTagException::class.java) {
            envelope.unwrapSecret(blob, recipient.privateKey, "wrap:otro".toByteArray(), DisputeEnvelope.PURPOSE_DISPUTE_WRAP)
        }
        blob[0] = 0x02
        assertThrows(IllegalArgumentException::class.java) {
            envelope.unwrapSecret(blob, recipient.privateKey, aad, DisputeEnvelope.PURPOSE_DISPUTE_WRAP)
        }
    }

    @Test
    fun splitAndCombineAreExactInverses() {
        val privateKey = randomBytes(32)
        val (splitA, splitB) = envelope.splitPrivateKey(privateKey)
        assertFalse(splitA.contentEquals(splitB))
        assertArrayEquals(privateKey, envelope.combineHalfKeys(splitA, splitB))
        assertThrows(IllegalArgumentException::class.java) {
            envelope.combineHalfKeys(splitA, ByteArray(10))
        }
    }

    // ------------------------------------------------------------------
    // Flujo de disputa de extremo a extremo (quórum 2-de-2)
    // ------------------------------------------------------------------

    @Test
    fun disputeEscrowFullFlow() {
        val conversationId = "conv-e2e"
        val aad = "wrap:$conversationId".toByteArray()
        val messageAad = "msg:$conversationId:text".toByteArray()

        // --- Lo que hace el cliente al crear el hilo ---
        val sessionKey = cipher.generateSessionKey()
        val dispute = envelope.generateKeyPair()
        val admin = envelope.generateKeyPair()       // custodio 1: admin general
        val moderator = envelope.generateKeyPair()   // custodio 2: moderador senior

        val sessionKeyWrapped = envelope.wrapSecret(
            sessionKey, dispute.publicKey, aad, DisputeEnvelope.PURPOSE_DISPUTE_WRAP
        )
        val (splitA, splitB) = envelope.splitPrivateKey(dispute.privateKey)
        val wrappedSplitA = envelope.wrapSecret(
            splitA, admin.publicKey, aad, DisputeEnvelope.PURPOSE_CUSTODIAN_WRAP
        )
        val wrappedSplitB = envelope.wrapSecret(
            splitB, moderator.publicKey, aad, DisputeEnvelope.PURPOSE_CUSTODIAN_WRAP
        )
        val contentRef = cipher.encrypt(
            sessionKey, "{\"v\":1,\"t\":\"text\",\"text\":\"hola\"}".toByteArray(), messageAad
        )

        // Ninguna parte por separado desenvuelve lo del otro.
        assertThrows(AEADBadTagException::class.java) {
            envelope.unwrapSecret(wrappedSplitB, admin.privateKey, aad, DisputeEnvelope.PURPOSE_CUSTODIAN_WRAP)
        }

        // --- Lo que hace el proceso backend con ambas mitades ---
        val recoveredA = envelope.unwrapSecret(
            wrappedSplitA, admin.privateKey, aad, DisputeEnvelope.PURPOSE_CUSTODIAN_WRAP
        )
        val recoveredB = envelope.unwrapSecret(
            wrappedSplitB, moderator.privateKey, aad, DisputeEnvelope.PURPOSE_CUSTODIAN_WRAP
        )
        val disputePrivate = envelope.combineHalfKeys(recoveredA, recoveredB)
        val recoveredSession = envelope.unwrapSecret(
            sessionKeyWrapped, disputePrivate, aad, DisputeEnvelope.PURPOSE_DISPUTE_WRAP
        )
        assertArrayEquals(sessionKey, recoveredSession)
        val plaintext = cipher.decrypt(recoveredSession, contentRef, messageAad)
        assertEquals("{\"v\":1,\"t\":\"text\",\"text\":\"hola\"}", String(plaintext))

        // El ciphertext de otro hilo no se "trasplanta" (AAD).
        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(recoveredSession, contentRef, "msg:otro-hilo:text".toByteArray())
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun randomBytes(count: Int) = ByteArray(count).also { random.nextBytes(it) }

    private fun String.hex(): ByteArray =
        ByteArray(length / 2) { i ->
            ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

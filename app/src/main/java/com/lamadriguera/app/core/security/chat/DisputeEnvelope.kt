package com.lamadriguera.app.core.security.chat

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sobres cifrados del diseño C (ECIES sobre X25519 + HKDF-SHA256 + AES-256-GCM).
 *
 * Se usa en tres lugares:
 * 1. Envolver `session_key` hacia la clave pública de disputa de la conversación
 *    → `conversations.session_key_wrapped`.
 * 2. Envolver `session_key` hacia la clave pública de chat de cada participante
 *    → `conversation_keys.wrapped_key` (cifrado extremo a extremo del hilo).
 * 3. Envolver cada mitad de la clave privada de disputa hacia su custodio
 *    → `conversations.dispute_key_split_a` (admin general) y
 *    `conversations.dispute_key_split_b` (moderador senior).
 *
 * También contiene la mitad de "recuperación" usada por el proceso backend de
 * disputa ([unwrapSecret] + [combineHalfKeys]), probada aquí de extremo a
 * extremo para garantizar paridad con la Edge Function.
 *
 * Formato del sobre (binario, se persiste como bytea hex):
 * `0x01 || clave_pub_efímera(32) || iv(12) || ciphertext || tag(16)`
 */
@Singleton
class DisputeEnvelope @Inject constructor(
    private val cipher: AesGcmCipher,
    private val random: SecureRandom
) {

    /** Par de claves X25519 generado en cliente. */
    class X25519KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    ) {
        init {
            require(privateKey.size == X25519.KEY_BYTES && publicKey.size == X25519.KEY_BYTES) {
                "Las claves X25519 deben tener 32 bytes"
            }
        }

        /** Borra la clave privada cuando ya no se necesita. */
        fun wipe() {
            privateKey.wipe()
        }
    }

    fun generateKeyPair(): X25519KeyPair {
        val privateKey = ByteArray(X25519.KEY_BYTES).also { random.nextBytes(it) }
        return X25519KeyPair(privateKey, X25519.publicKey(privateKey))
    }

    /**
     * Envuelve [secret] para el titular de la clave privada de [recipientPublicKey].
     * El AAD ata el sobre al contexto (p. ej. la conversación) y [purpose]
     * separa los dominios de derivación (disputa vs. participante vs. custodio).
     */
    fun wrapSecret(
        secret: ByteArray,
        recipientPublicKey: ByteArray,
        aad: ByteArray,
        purpose: String
    ): ByteArray {
        val ephemeral = generateKeyPair()
        return try {
            val shared = X25519.sharedSecret(ephemeral.privateKey, recipientPublicKey)
            val key = Hkdf.derive(
                ikm = shared,
                salt = ephemeral.publicKey,
                info = purpose.toByteArray(Charsets.UTF_8),
                length = AesGcmCipher.KEY_BYTES
            )
            shared.wipe()
            val encrypted = cipher.encryptRaw(key, secret, aad)
            key.wipe()
            byteArrayOf(BLOB_VERSION) + ephemeral.publicKey + encrypted
        } finally {
            ephemeral.wipe()
        }
    }

    /** Inversa de [wrapSecret]. Lanza si el sobre no autentica o es de orden pequeño. */
    fun unwrapSecret(
        blob: ByteArray,
        recipientPrivateKey: ByteArray,
        aad: ByteArray,
        purpose: String
    ): ByteArray {
        require(blob.size > 1 + X25519.KEY_BYTES) { "Sobre demasiado corto" }
        require(blob[0] == BLOB_VERSION) { "Versión de sobre no soportada" }
        val ephemeralPublic = blob.copyOfRange(1, 1 + X25519.KEY_BYTES)
        val encrypted = blob.copyOfRange(1 + X25519.KEY_BYTES, blob.size)
        val shared = X25519.sharedSecret(recipientPrivateKey, ephemeralPublic)
        val key = Hkdf.derive(
            ikm = shared,
            salt = ephemeralPublic,
            info = purpose.toByteArray(Charsets.UTF_8),
            length = AesGcmCipher.KEY_BYTES
        )
        shared.wipe()
        try {
            return cipher.decryptRaw(key, encrypted, aad)
        } finally {
            key.wipe()
        }
    }

    /**
     * Divide un secreto en dos mitades XOR: `a` aleatoria, `b = secret XOR a`.
     * Ninguna mitad por sí sola revela nada del secreto (one-time pad).
     */
    fun splitPrivateKey(privateKey: ByteArray): Pair<ByteArray, ByteArray> {
        require(privateKey.size == X25519.KEY_BYTES) { "La clave privada debe tener 32 bytes" }
        val splitA = ByteArray(X25519.KEY_BYTES).also { random.nextBytes(it) }
        val splitB = ByteArray(X25519.KEY_BYTES) { i -> (privateKey[i].toInt() xor splitA[i].toInt()).toByte() }
        return splitA to splitB
    }

    /** Recombina las mitades XOR. Inversa de [splitPrivateKey]. */
    fun combineHalfKeys(splitA: ByteArray, splitB: ByteArray): ByteArray {
        require(splitA.size == splitB.size) { "Las mitades deben tener el mismo tamaño" }
        return ByteArray(splitA.size) { i -> (splitA[i].toInt() xor splitB[i].toInt()).toByte() }
    }

    companion object {
        /** La `session_key` envuelta para la clave de disputa de la conversación. */
        const val PURPOSE_DISPUTE_WRAP = "lmchat/wrap/dispute/v1"

        /** La `session_key` envuelta para un participante del hilo. */
        const val PURPOSE_SESSION_WRAP = "lmchat/wrap/session/v1"

        /** Una mitad de la privada de disputa envuelta para su custodio. */
        const val PURPOSE_CUSTODIAN_WRAP = "lmchat/wrap/custodian/v1"

        const val BLOB_VERSION: Byte = 1
    }
}

package com.lamadriguera.app.core.security.chat

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cifrado autenticado AES-256-GCM (diseño C del chat).
 *
 * - Claves simétricas de 256 bits generadas en cliente con [SecureRandom].
 * - IV aleatorio de 96 bits por mensaje (jamás se reutiliza con la misma clave).
 * - AAD obligatorio en el chat: vincula el ciphertext a la conversación y al
 *   tipo de mensaje, de modo que un ciphertext no se pueda trasplantar de hilo.
 *
 * Formatos:
 * - Texto (para `messages.content_ref`): `"v1." + base64(iv || ciphertext || tag)`.
 * - Binario (para envolturas de clave): `iv || ciphertext || tag`.
 *
 * [decrypt] lanza si la clave no es la correcta o el ciphertext fue alterado
 * (AEADBadTagException): nunca devuelve plaintext no autenticado.
 */
@Singleton
class AesGcmCipher @Inject constructor(
    private val random: SecureRandom
) {

    /** Clave de sesión nueva de 256 bits (una por conversación). */
    fun generateSessionKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    /** Cifra y devuelve el formato texto versionado para persistir en `content_ref`. */
    fun encrypt(sessionKey: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): String {
        val raw = encryptRaw(sessionKey, plaintext, aad)
        return TEXT_PREFIX + Base64.getEncoder().encodeToString(raw)
    }

    /** Descifra el formato texto versionado. Lanza si el formato es desconocido o no autentica. */
    fun decrypt(sessionKey: ByteArray, encoded: String, aad: ByteArray? = null): ByteArray {
        require(encoded.startsWith(TEXT_PREFIX)) { "Formato de ciphertext no soportado" }
        val raw = Base64.getDecoder().decode(encoded.substring(TEXT_PREFIX.length))
        return decryptRaw(sessionKey, raw, aad)
    }

    /** Cifrado binario (sobres de clave): devuelve `iv || ciphertext || tag`. */
    fun encryptRaw(sessionKey: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(sessionKey.size == KEY_BYTES) { "La clave de sesión debe ser de 256 bits" }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, KEY_ALGORITHM),
            GCMParameterSpec(TAG_BITS, iv)
        )
        if (aad != null) cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        return iv + encrypted
    }

    /** Descifra el formato binario `iv || ciphertext || tag`. */
    fun decryptRaw(sessionKey: ByteArray, raw: ByteArray, aad: ByteArray? = null): ByteArray {
        require(sessionKey.size == KEY_BYTES) { "La clave de sesión debe ser de 256 bits" }
        require(raw.size > IV_BYTES + TAG_BYTES) { "Ciphertext demasiado corto" }
        val iv = raw.copyOfRange(0, IV_BYTES)
        val encrypted = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKey, KEY_ALGORITHM),
            GCMParameterSpec(TAG_BITS, iv)
        )
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }

    companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val TEXT_PREFIX = "v1."
        private const val KEY_ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Borra el contenido sensible de un buffer (best-effort en JVM/Android). */
fun ByteArray.wipe() {
    fill(0)
}

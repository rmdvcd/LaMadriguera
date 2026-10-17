package com.lamadriguera.app.core.security.chat

import android.content.SharedPreferences
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cofre local de secretos del chat, sobre EncryptedSharedPreferences.
 *
 * Guarda en el dispositivo (y NUNCA en el servidor):
 * - La `session_key` plana de cada conversación del usuario, para poder
 *   descifrar sus propios hilos sin depender del sobre de disputa.
 * - El par X25519 de identidad de chat del dispositivo, al que otros
 *   participantes envuelven copias de la `session_key` (`conversation_keys`).
 * - La marca de lectura por conversación, base del badge de no leídos.
 *
 * Mensajes autodestruidos: la app no persiste ciphertext fuera del servidor;
 * el plaintext solo vive en memoria. [wipeConversationSecrets] borra la clave
 * de sesión local de un hilo (p. ej. al abandonarlo), tras lo cual su
 * contenido deja de ser descifrable en este dispositivo.
 */
@Singleton
class SessionKeyStore @Inject constructor(
    private val prefs: SharedPreferences
) {

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    // --- Claves de sesión por conversación ---

    fun sessionKey(conversationId: String): ByteArray? =
        prefs.getString(sessionKeyPref(conversationId), null)?.let { encoded ->
            runCatching { decoder.decode(encoded) }.getOrNull()
                ?.takeIf { it.size == AesGcmCipher.KEY_BYTES }
        }

    fun putSessionKey(conversationId: String, key: ByteArray) {
        require(key.size == AesGcmCipher.KEY_BYTES) { "La clave de sesión debe ser de 256 bits" }
        prefs.edit().putString(sessionKeyPref(conversationId), encoder.encodeToString(key)).apply()
    }

    // --- Identidad X25519 del dispositivo (para conversation_keys) ---

    fun identityPrivateKey(): ByteArray? =
        prefs.getString(KEY_IDENTITY_PRIVATE, null)?.let { encoded ->
            runCatching { decoder.decode(encoded) }.getOrNull()
                ?.takeIf { it.size == X25519.KEY_BYTES }
        }

    fun identityPublicKey(): ByteArray? =
        prefs.getString(KEY_IDENTITY_PUBLIC, null)?.let { encoded ->
            runCatching { decoder.decode(encoded) }.getOrNull()
                ?.takeIf { it.size == X25519.KEY_BYTES }
        }

    fun putIdentityKeyPair(privateKey: ByteArray, publicKey: ByteArray) {
        require(privateKey.size == X25519.KEY_BYTES && publicKey.size == X25519.KEY_BYTES) {
            "Las claves X25519 deben tener 32 bytes"
        }
        prefs.edit()
            .putString(KEY_IDENTITY_PRIVATE, encoder.encodeToString(privateKey))
            .putString(KEY_IDENTITY_PUBLIC, encoder.encodeToString(publicKey))
            .apply()
    }

    // --- Marcas de lectura (badge de no leídos) ---

    fun lastReadAt(conversationId: String): Instant? =
        prefs.getString(lastReadPref(conversationId), null)?.let { encoded ->
            runCatching { Instant.parse(encoded) }.getOrNull()
        }

    fun markConversationRead(conversationId: String, at: Instant = Instant.now()) {
        prefs.edit().putString(lastReadPref(conversationId), at.toString()).apply()
    }

    // --- Autodestrucción local ---

    /** Borra los secretos locales de un hilo: su contenido deja de ser legible aquí. */
    fun wipeConversationSecrets(conversationId: String) {
        prefs.edit()
            .remove(sessionKeyPref(conversationId))
            .remove(lastReadPref(conversationId))
            .apply()
    }

    /** Borra TODO el material del chat del dispositivo (cierre de sesión definitivo). */
    fun wipeAll() {
        prefs.edit().clear().apply()
    }

    private fun sessionKeyPref(conversationId: String) = "chat_sk_$conversationId"
    private fun lastReadPref(conversationId: String) = "chat_lr_$conversationId"

    private companion object {
        const val KEY_IDENTITY_PRIVATE = "chat_identity_private"
        const val KEY_IDENTITY_PUBLIC = "chat_identity_public"
    }
}

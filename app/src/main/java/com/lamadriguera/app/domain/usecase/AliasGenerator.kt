package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.domain.model.PrivacyLevel
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Alias automático cuando se elige privacidad 'anon' sin definir alias.
 *
 * El algoritmo coincide con el trigger `ensure_profile_alias` de Supabase
 * (Anon-XXXXXX a partir del SHA-256 del user id), de modo que el alias es
 * estable tanto si lo genera el cliente como el servidor.
 */
class AliasGenerator @Inject constructor() {

    /**
     * Resuelve el alias final del perfil:
     * - anon sin alias -> alias generado automáticamente;
     * - en otro caso, el alias definido (recortado) o null.
     */
    operator fun invoke(userId: String, privacyLevel: PrivacyLevel, alias: String?): String? = when {
        privacyLevel == PrivacyLevel.ANON && alias.isNullOrBlank() -> generate(userId)
        else -> alias?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Alias estable para [userId]: `Anon-` + 6 hex del SHA-256. */
    fun generate(userId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        return PREFIX + hex.take(HEX_LENGTH).uppercase()
    }

    companion object {
        const val PREFIX = "Anon-"
        private const val HEX_LENGTH = 6
    }
}

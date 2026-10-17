package com.lamadriguera.app.core.crypto

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utilidades criptográficas locales.
 * Se ampliará en fase 2 para firmar/verificar apuestas y cifrar metadatos sensibles.
 */
@Singleton
class CryptoManager @Inject constructor() {

    /** SHA-256 en hexadecimal minúsculas. Útil para huellas de integridad. */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** Comparación en tiempo constante para evitar timing attacks. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

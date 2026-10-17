package com.lamadriguera.app.core.security.chat

import java.util.Base64

/**
 * Claves públicas de custodia del diseño C.
 *
 * La clave privada de disputa de cada conversación se divide XOR en dos mitades;
 * cada mitad se envuelve para un custodio:
 * - [adminCustodianPublicKey]: clave pública del **admin general**.
 * - [seniorModeratorCustodianPublicKey]: clave pública del **moderador senior**.
 *
 * Las privadas de custodia NO viven en la app: solo en el proceso backend de
 * disputa (secretos de la Edge Function). Con una sola mitad no se puede
 * descifrar nada; hacen falta ambas (quórum 2-de-2).
 *
 * Las claves de desarrollo ([DEV_*]) son públicas por diseño y solo sirven
 * para entornos locales; en producción se sustituyen vía local.properties /
 * `-PDISPUTE_ADMIN_PUB=...` (ver app/build.gradle.kts).
 */
class DisputeKeyConfig(
    val adminCustodianPublicKey: ByteArray,
    val seniorModeratorCustodianPublicKey: ByteArray,
    val isDevelopmentKeys: Boolean
) {
    init {
        require(adminCustodianPublicKey.size == X25519.KEY_BYTES) { "Clave de custodia inválida" }
        require(seniorModeratorCustodianPublicKey.size == X25519.KEY_BYTES) { "Clave de custodia inválida" }
    }

    companion object {
        /** DEV-ONLY: nunca usar fuera de desarrollo local. */
        const val DEV_ADMIN_PUB_B64 = "NyWqv4Kc7xGBkl4d5qBsLBE6yG5R5dKJ6V2nlzf1qGM="

        /** DEV-ONLY: nunca usar fuera de desarrollo local. */
        const val DEV_MODERATOR_PUB_B64 = "x8RkSA4UPaZAWJA7jkQe/e2PZ7xeGXkALIoimj6REzM="

        /**
         * Construye la configuración desde base64. Si falta alguna clave
         * (placeholders de build), cae al par de desarrollo y lo marca.
         */
        fun fromBase64(adminPubB64: String?, moderatorPubB64: String?): DisputeKeyConfig {
            val admin = adminPubB64?.decodeOrNull()
            val moderator = moderatorPubB64?.decodeOrNull()
            if (admin != null && moderator != null) {
                return DisputeKeyConfig(admin, moderator, isDevelopmentKeys = false)
            }
            return DisputeKeyConfig(
                adminCustodianPublicKey = Base64.getDecoder().decode(DEV_ADMIN_PUB_B64),
                seniorModeratorCustodianPublicKey = Base64.getDecoder().decode(DEV_MODERATOR_PUB_B64),
                isDevelopmentKeys = true
            )
        }

        private fun String.decodeOrNull(): ByteArray? = runCatching {
            Base64.getDecoder().decode(this).takeIf { it.size == X25519.KEY_BYTES }
        }.getOrNull()
    }
}

package com.lamadriguera.app.core.security.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detector de datos personales en el texto del chat.
 *
 * Política (diseño C): compartir un teléfono está PERMITIDO, pero antes de
 * enviar el mensaje la app muestra la advertencia "estás compartiendo un dato
 * personal" y el usuario decide. Nada se bloquea; solo se avisa.
 */
@Singleton
class PersonalDataGuard @Inject constructor() {

    enum class PersonalDataKind { PHONE, EMAIL }

    /** Tipos de dato personal detectados en [text]; vacío si no hay ninguno. */
    fun detect(text: String): Set<PersonalDataKind> {
        if (text.isBlank()) return emptySet()
        val found = mutableSetOf<PersonalDataKind>()
        if (containsPhone(text)) found += PersonalDataKind.PHONE
        if (containsEmail(text)) found += PersonalDataKind.EMAIL
        return found
    }

    /**
     * true si el texto parece contener un número de teléfono.
     * Acepta formatos cubanos (`53512345`, `+53 51 23 45 67`, `01 234567`)
     * e internacionales con separadores (espacios, guiones, puntos, paréntesis);
     * exige al menos [MIN_DIGITS] dígitos para evitar falsos positivos con
     * precios o años cortos.
     */
    fun containsPhone(text: String): Boolean {
        for (match in PHONE_CANDIDATE.findAll(text)) {
            val digits = match.value.count { it.isDigit() }
            if (digits in MIN_DIGITS..MAX_DIGITS) return true
        }
        return false
    }

    fun containsEmail(text: String): Boolean = EMAIL_CANDIDATE.containsMatchIn(text)

    companion object {
        const val MIN_DIGITS = 8
        const val MAX_DIGITS = 15

        // Secuencia con aspecto de teléfono: opcional '+', dígitos y separadores
        // habituales, sin letras pegadas. Los anclajes evitan capturar dígitos
        // dentro de palabras más largas; el núcleo es codicioso para tomar el
        // número completo (después se cuenta el total de dígitos).
        private val PHONE_CANDIDATE = Regex(
            """(?<![\p{L}\d])\+?\d[\d .\-()]{0,18}\d(?![\p{L}\d])"""
        )

        private val EMAIL_CANDIDATE = Regex(
            """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""
        )
    }
}

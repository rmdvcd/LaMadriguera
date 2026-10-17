package com.lamadriguera.app.domain.usecase

import java.time.LocalDate
import java.time.Period

/**
 * Validadores puros del flujo de autenticación y verificación de identidad.
 * Los mensajes son los que muestra la UI ante un dato inválido.
 */
object PhoneNumber {

    private val E164 = Regex("^\\+[1-9]\\d{6,14}$")

    /**
     * Normaliza un teléfono cubano o internacional a formato E.164 (+53…).
     * Acepta espacios/guiones, prefijo internacional 00 y números nacionales.
     * Devuelve null si no es un número válido.
     */
    fun normalize(input: String): String? {
        val cleaned = input.trim().replace(Regex("[\\s\\-().]"), "")
        val international = if (cleaned.startsWith("00")) "+" + cleaned.drop(2) else cleaned
        val candidate = when {
            international.startsWith("+") -> international
            international.startsWith("53") && international.length >= 11 -> "+$international"
            international.all { it.isDigit() } && international.length in 6..10 -> "+53$international"
            else -> return null
        }
        return candidate.takeIf { E164.matches(it) }
    }

    fun isValid(input: String): Boolean = normalize(input) != null

    /** Mensaje de error para un teléfono inválido. */
    const val INVALID_MESSAGE = "Número de teléfono no válido"
}

object OtpCode {

    private val SIX_DIGITS = Regex("^\\d{6}$")

    fun isValid(code: String): Boolean = SIX_DIGITS.matches(code.trim())

    const val LENGTH = 6

    const val INVALID_MESSAGE = "El código debe tener 6 dígitos"
}

/** Validación del formulario de verificación de identidad. */
object IdentityVerificationForm {

    const val MIN_AGE_YEARS = 14
    private const val MAX_NAME_LENGTH = 120

    fun validateFullName(fullName: String): String? = when {
        fullName.trim().length < 3 -> "Introduce tu nombre completo"
        fullName.trim().length > MAX_NAME_LENGTH -> "El nombre es demasiado largo"
        else -> null
    }

    fun validateBirthdate(birthdate: LocalDate?, today: LocalDate = LocalDate.now()): String? = when {
        birthdate == null -> "Selecciona tu fecha de nacimiento"
        birthdate.isAfter(today) -> "La fecha de nacimiento no puede ser futura"
        Period.between(birthdate, today).years < MIN_AGE_YEARS -> "Debes tener al menos $MIN_AGE_YEARS años"
        else -> null
    }

    const val TERMS_MESSAGE = "Debes aceptar los términos para continuar"
    const val FRONT_IMAGE_MESSAGE = "Falta la foto del frente del carnet"
    const val BACK_IMAGE_MESSAGE = "Falta la foto del reverso del carnet"
}

/** Nombre visible inicial de un perfil sin nombre definido. */
object ProfileDefaults {

    fun displayNameFor(phone: String?): String {
        val digits = phone?.filter { it.isDigit() }.orEmpty()
        return if (digits.length >= 4) "Usuario ${digits.takeLast(4)}" else "Usuario"
    }
}

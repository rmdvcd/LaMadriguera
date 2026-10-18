package com.lamadriguera.app.core.security.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * Detector de datos personales: teléfonos y correos disparan la advertencia
 * "estás compartiendo un dato personal" antes de enviar (nunca bloquean).
 */
class PersonalDataGuardTest {

    private val guard = PersonalDataGuard()

    @Test
    fun detectsCubanAndInternationalPhones() {
        listOf(
            "Mi número es 53512345",
            "Llámame al +5351234567",
            "Escríbeme al +53 51 23 45 67 cuando puedas",
            "53 51 23 45 67",
            "012345678",
            "el teléfono 5 3 5 6 7 8 1 2 es mío",
            "móvil: 555-666-7788"
        ).forEach { text ->
            assertTrue(text, guard.containsPhone(text))
            assertTrue(text, guard.detect(text).contains(PersonalDataGuard.PersonalDataKind.PHONE))
        }
    }

    @Test
    fun ignoresPricesYearsAndShortIds() {
        listOf(
            "Precio: 2500 CUP",
            "Lo compré en 2023",
            "Cuesta 15000 y es nuevo",
            "El id es 1234567",          // 7 dígitos: demasiado corto
            "tarjeta 4242424242424242",   // 16 dígitos: no es teléfono
            "Nos vemos a las 7 y media",
            "Tengo 2 coches y 3 bicis"
        ).forEach { text ->
            assertFalse(text, guard.containsPhone(text))
        }
    }

    @Test
    fun detectsEmails() {
        assertTrue(guard.containsEmail("Escríbeme a fulano@gmail.com cuando puedas"))
        assertTrue(guard.containsEmail("mi-correo.es+alt@nauta.cu"))
        assertFalse(guard.containsEmail("sin arrobas por aquí"))
        assertTrue(guard.detect("mi correo es a@b.co").contains(PersonalDataGuard.PersonalDataKind.EMAIL))
    }

    @Test
    fun blankOrCleanTextDetectsNothing() {
        assertTrue(guard.detect("").isEmpty())
        assertTrue(guard.detect("   ").isEmpty())
        assertTrue(guard.detect("Hola, ¿sigue disponible?").isEmpty())
    }
}

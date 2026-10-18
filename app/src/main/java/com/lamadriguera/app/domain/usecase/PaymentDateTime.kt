package com.lamadriguera.app.domain.usecase

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Normaliza la hora local del comprobante a Cuba, independientemente de la zona del teléfono. */
object PaymentDateTime {
    const val INPUT_PATTERN = "uuuu-MM-dd HH:mm"
    private val transferZone: ZoneId = ZoneId.of("America/Havana")
    private val inputFormatter = DateTimeFormatter.ofPattern(INPUT_PATTERN)
        .withResolverStyle(ResolverStyle.STRICT)
    private val displayFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")

    fun parseLocalInput(value: String, zone: ZoneId = transferZone): Instant? =
        runCatching { LocalDateTime.parse(value.trim(), inputFormatter).atZone(zone).toInstant() }
            .getOrNull()

    fun formatInput(instant: Instant, zone: ZoneId = transferZone): String =
        LocalDateTime.ofInstant(instant, zone).format(inputFormatter)

    fun formatDisplay(instant: Instant, zone: ZoneId = transferZone): String =
        LocalDateTime.ofInstant(instant, zone).format(displayFormatter)
}

package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.domain.model.ModeratedContent
import com.lamadriguera.app.domain.model.ModerationCategory
import com.lamadriguera.app.domain.model.ModerationTargetType

/**
 * Reglas de dominio de moderación: deterministas, sin IA y sin red.
 *
 *  - Teléfonos en campos prohibidos (títulos, nombres, alias, descripciones
 *    públicas): se rechazan antes de publicar.
 *  - Enlaces sospechosos (acortadores, ofuscados, esquemas peligrosos, enlaces
 *    de mensajería para evadir la plataforma, exceso de enlaces).
 *  - Patrones de spam (repetición, mayúsculas, señuelos de estafa).
 *  - Apuestas/loterías ilegales (bolita, casino clandestino…): categoría grave.
 */
object ModerationRules {

    enum class FindingKind { PHONE_IN_FORBIDDEN_FIELD, SUSPICIOUS_LINK, SPAM_PATTERN, ILLEGAL_GAMBLING }

    data class Finding(
        val kind: FindingKind,
        val category: ModerationCategory,
        val severity: Int,
        val message: String
    )

    const val MAX_LINKS = 2
    const val MAX_EMOJI = 12

    /** Campos donde un teléfono siempre está prohibido, por tipo de contenido. */
    fun phoneForbiddenFields(target: ModerationTargetType, content: ModeratedContent): List<Pair<String, String>> =
        when (target) {
            ModerationTargetType.LISTING -> listOfNotNull(
                "título" to content.title.orEmpty(),
                "descripción" to content.body.orEmpty()
            )
            ModerationTargetType.BETTING_GROUP -> listOfNotNull(
                "nombre" to content.title.orEmpty(),
                "tema" to content.body.orEmpty()
            )
            ModerationTargetType.BET -> listOf("descripción" to content.body.orEmpty())
            ModerationTargetType.PROFILE -> listOfNotNull(
                "nombre" to content.title.orEmpty(),
                "alias" to content.body.orEmpty()
            )
            // Conversación de grupo y chat privado: compartir un teléfono está
            // permitido (con aviso); la plataforma no modera contenido privado.
            ModerationTargetType.GROUP_MESSAGE, ModerationTargetType.MESSAGE -> emptyList()
        }

    fun check(content: ModeratedContent): List<Finding> {
        val findings = mutableListOf<Finding>()
        val combined = content.combinedText()
        if (combined.isBlank()) return findings

        for ((field, value) in phoneForbiddenFields(content.targetType, content)) {
            if (value.isNotBlank() && containsPhone(value)) {
                findings += Finding(
                    kind = FindingKind.PHONE_IN_FORBIDDEN_FIELD,
                    category = ModerationCategory.PHONE_LEAK,
                    severity = 3,
                    message = "No incluyas teléfonos en el $field. Comparte el contacto por el chat privado."
                )
            }
        }

        if (containsIllegalGambling(combined)) {
            findings += Finding(
                kind = FindingKind.ILLEGAL_GAMBLING,
                category = ModerationCategory.ILLEGAL_GAMBLING,
                severity = 5,
                message = "El contenido promueve apuestas o loterías ilegales y no puede publicarse."
            )
        }

        findSuspiciousLinks(combined)?.let { message ->
            findings += Finding(
                kind = FindingKind.SUSPICIOUS_LINK,
                category = ModerationCategory.SUSPICIOUS_LINK,
                severity = 3,
                message = message
            )
        }

        findSpamPattern(combined)?.let { message ->
            findings += Finding(
                kind = FindingKind.SPAM_PATTERN,
                category = ModerationCategory.SPAM,
                severity = 2,
                message = message
            )
        }

        return findings
    }

    // --- Teléfonos -----------------------------------------------------------

    /**
     * Acepta formatos cubanos (`53512345`, `+53 51 23 45 67`) e internacionales
     * con separadores; exige 8..15 dígitos (misma política que PersonalDataGuard).
     */
    fun containsPhone(text: String): Boolean =
        PHONE_CANDIDATE.findAll(text).any { match ->
            match.value.count { it.isDigit() } in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS
        }

    // --- Apuestas ilegales ----------------------------------------------------

    fun containsIllegalGambling(text: String): Boolean {
        val normalized = normalize(text)
        return ILLEGAL_GAMBLING_PATTERNS.any { normalized.contains(it) }
    }

    // --- Enlaces sospechosos ---------------------------------------------------

    fun findSuspiciousLinks(text: String): String? {
        val links = URL_CANDIDATE.findAll(text).map { it.value.lowercase() }.toList()
        if (links.isEmpty()) return null

        if (links.any { link -> SHORTENERS.any { link.contains(it) } }) {
            return "Retira los enlaces acortados; usa el enlace completo y verificable."
        }
        if (links.any { DANGEROUS_SCHEME.containsMatchIn(it) || OBFUSCATED.containsMatchIn(it) }) {
            return "El contenido contiene enlaces no seguros y no puede publicarse."
        }
        if (links.any { link -> MESSAGING_LINKS.any { link.contains(it) } }) {
            return "No incluyas enlaces de mensajería externa; la coordinación ocurre en el chat de la app."
        }
        if (links.any { IP_HOST.containsMatchIn(it) || it.contains("xn--") }) {
            return "El contenido contiene enlaces sospechosos y no puede publicarse."
        }
        if (links.size > MAX_LINKS) {
            return "Demasiados enlaces en el texto; reduce a $MAX_LINKS o menos."
        }
        return null
    }

    // --- Spam -------------------------------------------------------------------

    fun findSpamPattern(text: String): String? {
        val normalized = normalize(text)
        if (SPAM_BAIT.any { normalized.contains(it) }) {
            return "El texto parece spam o una oferta engañosa. Reescribe el contenido con información concreta."
        }
        if (REPEATED_CHAR.containsMatchIn(text)) {
            return "Evita repetir caracteres (aaaa, !!!!); parece spam."
        }
        if (REPEATED_WORD.containsMatchIn(text)) {
            return "Evita repetir la misma palabra muchas veces; parece spam."
        }
        val letters = text.count { it.isLetter() }
        val capitals = text.count { it.isUpperCase() }
        if (letters >= 20 && capitals.toDouble() / letters > 0.7) {
            return "Escribe sin tantas mayúsculas; el texto gritado se trata como spam."
        }
        if (EMOJI.count(text) > MAX_EMOJI) {
            return "Demasiados emojis en el texto; reduce la cantidad."
        }
        return null
    }

    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{InCombiningDiacriticalMarks}"""), "")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[.,;:!¡?¿]"""), " ")

    private val PHONE_CANDIDATE = Regex("""(?<![\p{L}\d])\+?\d[\d .\-()]{0,18}\d(?![\p{L}\d])""")
    private val URL_CANDIDATE = Regex(
        """(?i)\b((?:https?://|www\.)[^\s<>"']+|[a-z0-9][a-z0-9\-]*(?:\.[a-z][a-z0-9\-]*)+(?:/[^\s<>"']*)?)"""
    )
    private val DANGEROUS_SCHEME = Regex("""(?i)^(javascript|data|intent|vbscript|file):""")
    private val OBFUSCATED = Regex("""(?i)^(hxxp|h\*\*p|htp)s?://|\[[.\]]|\(dot\)|\[at\]""")
    private val IP_HOST = Regex("""(?i)https?://\d{1,3}(\.\d{1,3}){3}""")
    private val REPEATED_CHAR = Regex("""(.)\1{4,}""")
    private val REPEATED_WORD = Regex("""(?i)\b(\w+)(\s+\1){3,}\b""")
    private val EMOJI = Regex("""[\p{So}\p{Sk}\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}]""")

    private val SHORTENERS = listOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "cutt.ly",
        "rb.gy", "shorturl.at", "s.id", "tiny.cc", "buff.ly", "rebrand.ly"
    )
    private val MESSAGING_LINKS = listOf(
        "wa.me", "wa.link", "chat.whatsapp.com", "t.me", "telegram.me", "m.me"
    )
    private val SPAM_BAIT = listOf(
        "gana dinero", "dinero facil", "hazte rico", "ingreso garantizado",
        "inversion garantizada", "sin riesgo", "haz clic aqui", "click aqui",
        "comparte y gana", "premio", "sorteo", "ganador", "oferta limitada",
        "solo hoy", "ultimo disponible", "pagos por resultados", "trabajo desde casa",
        "100% gratis", "verificado urgente", "transferencia inmediata"
    )
    private val ILLEGAL_GAMBLING_PATTERNS = listOf(
        "bolita", "loteria clandestina", "loteria ilegal", "loteria por fuera",
        "casino ilegal", "casino clandestino", "apuesta clandestina",
        "apuestas fuera de la ley", "juego clandestino", "charada"
    )

    const val MIN_PHONE_DIGITS = 8
    const val MAX_PHONE_DIGITS = 15
}

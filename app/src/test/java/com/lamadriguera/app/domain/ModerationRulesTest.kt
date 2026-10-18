package com.lamadriguera.app.domain

import com.lamadriguera.app.domain.model.ModeratedContent
import com.lamadriguera.app.domain.model.ModerationCategory
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.usecase.ModerationRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationRulesTest {

    @Test
    fun phonesAreDetectedInForbiddenListingFields() {
        val findings = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.LISTING,
                title = "Vendo bicicleta, llámame al 53512345",
                description = "Bicicleta en buen estado"
            )
        )
        assertTrue(findings.any { it.kind == ModerationRules.FindingKind.PHONE_IN_FORBIDDEN_FIELD })
        assertEquals(ModerationCategory.PHONE_LEAK, findings.first().category)
    }

    @Test
    fun phonesAreAllowedInPrivateConversationButForbiddenInProfileAlias() {
        val chat = ModerationRules.check(
            ModeratedContent(targetType = ModerationTargetType.MESSAGE, body = "Mi número es +53 51 23 45 67")
        )
        assertTrue(chat.none { it.kind == ModerationRules.FindingKind.PHONE_IN_FORBIDDEN_FIELD })

        val profile = ModerationRules.check(
            ModeratedContent(targetType = ModerationTargetType.PROFILE, title = "Pedro", body = "53512345")
        )
        assertTrue(profile.any { it.kind == ModerationRules.FindingKind.PHONE_IN_FORBIDDEN_FIELD })
    }

    @Test
    fun shortDigitSequencesAreNotPhones() {
        assertTrue(ModerationRules.check(
            ModeratedContent(targetType = ModerationTargetType.LISTING, title = "Mesa por 25000", body = "Mesa de madera")
        ).isEmpty())
    }

    @Test
    fun suspiciousLinksAreRejected() {
        val shortened = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.LISTING,
                title = "Oferta",
                description = "Mira aquí https://bit.ly/abc123"
            )
        )
        assertTrue(shortened.any { it.kind == ModerationRules.FindingKind.SUSPICIOUS_LINK })

        val messaging = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.GROUP_MESSAGE,
                body = "Escríbeme por https://wa.me/53512345"
            )
        )
        assertTrue(messaging.any { it.kind == ModerationRules.FindingKind.SUSPICIOUS_LINK })

        val dangerous = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.LISTING,
                title = "javascript:alert(1) mira",
                description = "Contenido"
            )
        )
        assertTrue(dangerous.any { it.kind == ModerationRules.FindingKind.SUSPICIOUS_LINK })
    }

    @Test
    fun plainDomainInTextIsSuspiciousOnlyWhenExtraLinksAbound() {
        val oneLink = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.LISTING,
                title = "Mesa",
                description = "Info en ejemplo.com/producto"
            )
        )
        assertTrue(oneLink.none { it.kind == ModerationRules.FindingKind.SUSPICIOUS_LINK })

        val manyLinks = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.LISTING,
                title = "Mira",
                description = "uno.com dos.com tres.com cuatro.com"
            )
        )
        assertTrue(manyLinks.any { it.kind == ModerationRules.FindingKind.SUSPICIOUS_LINK })
    }

    @Test
    fun spamPatternsAreRejected() {
        val bait = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.LISTING,
                title = "Gana dinero fácil desde casa",
                description = "Inversión garantizada sin riesgo"
            )
        )
        assertTrue(bait.any { it.kind == ModerationRules.FindingKind.SPAM_PATTERN })

        val repeated = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.LISTING,
                title = "Oferta aaaaaaa",
                description = "Producto excelente"
            )
        )
        assertTrue(repeated.any { it.kind == ModerationRules.FindingKind.SPAM_PATTERN })

        val shouting = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.LISTING,
                title = "MIRA ESTO AHORA MISMO",
                description = "LA MEJOR OFERTA DEL MERCADO CUBANO SIN COMPETENCIA"
            )
        )
        assertTrue(shouting.any { it.kind == ModerationRules.FindingKind.SPAM_PATTERN })
    }

    @Test
    fun illegalGamblingIsSevere() {
        val findings = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.GROUP_MESSAGE,
                body = "Juega la bolita aquí, lotería clandestina segura"
            )
        )
        val finding = findings.first { it.kind == ModerationRules.FindingKind.ILLEGAL_GAMBLING }
        assertEquals(ModerationCategory.ILLEGAL_GAMBLING, finding.category)
        assertTrue(finding.category.isSevere)
    }

    @Test
    fun cleanContentHasNoFindings() {
        val findings = ModerationRules.check(
            ModeratedContent(
                targetType = ModerationTargetType.LISTING,
                title = "Vendo refrigerador",
                description = "Refrigerador en buen estado, precio negociable, entrega en La Habana."
            )
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun normalWordsAreNotIllegalGambling() {
        assertFalse(ModerationRules.containsIllegalGambling("Vendo montura de bicicleta nueva"))
    }
}

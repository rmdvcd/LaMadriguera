package com.lamadriguera.app.domain

import com.lamadriguera.app.domain.model.Boost
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.ListingCandidate
import com.lamadriguera.app.domain.model.ListingSearchFilter
import com.lamadriguera.app.domain.model.ProductType
import com.lamadriguera.app.domain.model.PromotionScope
import com.lamadriguera.app.domain.usecase.ListingRanker
import com.lamadriguera.app.domain.usecase.PaymentDateTime
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MonetizationRulesTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val ranker = ListingRanker()

    private fun listing(
        id: String,
        municipalityId: Int = 101,
        categoryId: String = "cat",
        promoted: Boolean = false
    ) = Listing(
        id = id,
        ownerId = "owner-$id",
        title = "Anuncio $id",
        description = "Descripción de prueba",
        categoryId = categoryId,
        price = BigDecimal("100"),
        municipalityId = municipalityId,
        createdAt = now.minusSeconds(3600),
        boostedUntil = if (promoted) now.plusSeconds(3600) else null
    )

    private fun promotion(id: String, scope: PromotionScope, type: ProductType = ProductType.REACH) = Boost(
        id = "promotion-$id",
        listingId = id,
        tier = scope.name.lowercase(),
        startsAt = now.minusSeconds(60),
        endsAt = now.plusSeconds(3600),
        paymentId = "payment-$id",
        productType = type,
        reachScope = scope.takeIf { type == ProductType.REACH }
    )

    @Test
    fun featuredPromotionsUseOneOfFiveSlotsInMatchingCategoryAndMunicipality() {
        val candidates = (1..4).map { id -> ListingCandidate(listing("organic-$id")) } +
            ListingCandidate(
                listing = listing("featured", promoted = true),
                activePromotions = listOf(promotion("featured", PromotionScope.MUNICIPALITY, ProductType.FEATURE))
            )
        val ranked = ranker.filterAndRank(
            candidates = candidates,
            filter = ListingSearchFilter(categoryId = "cat", municipalityId = 101),
            now = now
        )

        assertEquals(5, ranked.size)
        assertEquals("featured", ranked[4].listing.id)
        assertEquals(1, ranked.count { it.isFeatured })
        assertEquals(1, ranked.count { it.isPromoted })
    }

    @Test
    fun paidBoostAppliesEvenBeforeTheOwnerHasRatings() {
        val candidate = ListingCandidate(
            listing = listing("paid-boost", promoted = true),
            ownerReputationAvg = BigDecimal.ZERO,
            activePromotions = listOf(promotion("paid-boost", PromotionScope.MUNICIPALITY, ProductType.BOOST))
        )

        val ranked = ranker.scoreCandidate(candidate, now = now)

        assertEquals(true, ranked.isBoostApplied)
        assertEquals(ranked.baseScore * 1.3, ranked.finalScore, 0.000001)
    }

    @Test
    fun reachPromotionExtendsMunicipalityFilterOnlyWithinConfiguredScope() {
        val provincePromotion = ListingCandidate(
            listing = listing("province"),
            activePromotions = listOf(promotion("province", PromotionScope.PROVINCE))
        )
        assertEquals(true, ranker.matchesFilter(provincePromotion, ListingSearchFilter(municipalityId = 102), now))
        assertEquals(false, ranker.matchesFilter(provincePromotion, ListingSearchFilter(municipalityId = 201), now))

        val nationalPromotion = provincePromotion.copy(
            listing = listing("national"),
            activePromotions = listOf(promotion("national", PromotionScope.NATIONAL))
        )
        assertEquals(true, ranker.matchesFilter(nationalPromotion, ListingSearchFilter(municipalityId = 701), now))
    }

    @Test
    fun reachPromotionBoostsRankingOnlyInsideItsGeographicScope() {
        val candidate = ListingCandidate(
            listing = listing("province"),
            activePromotions = listOf(promotion("province", PromotionScope.PROVINCE))
        )

        val insideProvince = ranker.scoreCandidate(candidate, targetMunicipalityId = 102, now = now)
        val outsideProvince = ranker.scoreCandidate(candidate, targetMunicipalityId = 201, now = now)

        assertEquals(insideProvince.baseScore * 1.15, insideProvince.finalScore, 0.000001)
        assertEquals(outsideProvince.baseScore, outsideProvince.finalScore, 0.000001)
    }

    @Test
    fun localTransferTimestampIsParsedWithExplicitDeviceZone() {
        val parsed = PaymentDateTime.parseLocalInput("2026-10-18 05:30", ZoneOffset.ofHours(-7))
        assertNotNull(parsed)
        assertEquals(Instant.parse("2026-10-18T12:30:00Z"), parsed)
        assertNull(PaymentDateTime.parseLocalInput("2026-13-42 40:00", ZoneOffset.UTC))
    }
}

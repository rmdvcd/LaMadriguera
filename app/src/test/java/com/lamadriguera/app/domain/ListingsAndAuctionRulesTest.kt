package com.lamadriguera.app.domain

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.AuctionConfig
import com.lamadriguera.app.domain.model.AuctionConfigCodec
import com.lamadriguera.app.domain.model.AuctionState
import com.lamadriguera.app.domain.model.Bid
import com.lamadriguera.app.domain.model.BidStatus
import com.lamadriguera.app.domain.model.BidVisibility
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.ListingCandidate
import com.lamadriguera.app.domain.model.ListingDraft
import com.lamadriguera.app.domain.model.ListingKind
import com.lamadriguera.app.domain.model.ListingSearchFilter
import com.lamadriguera.app.domain.model.ListingStatus
import com.lamadriguera.app.domain.model.ListingVisibility
import com.lamadriguera.app.domain.model.PrivacyLevel
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.domain.model.User
import com.lamadriguera.app.domain.model.UserStatus
import com.lamadriguera.app.domain.model.VerificationLevel
import com.lamadriguera.app.domain.usecase.AuctionConfigValidator
import com.lamadriguera.app.domain.usecase.AuctionStateMachine
import com.lamadriguera.app.domain.usecase.BidValidator
import com.lamadriguera.app.domain.usecase.ImageCompressor
import com.lamadriguera.app.domain.usecase.ListingRanker
import com.lamadriguera.app.domain.usecase.ListingValidator
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingRankerTest {

    private val ranker = ListingRanker()
    private val now: Instant = Instant.parse("2026-10-17T12:00:00Z")

    private fun sampleListing(
        id: String,
        title: String = "Bicicleta eléctrica",
        description: String = "Batería de litio nueva",
        kind: ListingKind = ListingKind.OFFER,
        categoryId: String = "cat-1",
        municipalityId: Int? = 101,
        boostedUntil: Instant? = null,
        createdAt: Instant = now
    ) = Listing(
        id = id,
        ownerId = "owner-$id",
        kind = kind,
        title = title,
        description = description,
        categoryId = categoryId,
        price = BigDecimal("500"),
        municipalityId = municipalityId,
        boostedUntil = boostedUntil,
        createdAt = createdAt
    )

    @Test
    fun exactWeightFormulaSumsToOneWithoutBoost() {
        val score = ranker.computeScore(
            relevance = 1.0,
            proximity = 1.0,
            reputationAvg = BigDecimal("5.0"),
            freshness = 1.0,
            response = 1.0,
            isBoosted = false
        )
        assertEquals(1.0, score, 1e-6)

        val partial = ranker.computeBaseScore(
            relevance = 0.8,
            proximity = 0.6,
            reputation = 0.8,
            freshness = 0.5,
            response = 1.0
        )
        val expected = 0.45 * 0.8 + 0.25 * 0.6 + 0.15 * 0.8 + 0.10 * 0.5 + 0.05 * 1.0
        assertEquals(expected, partial, 1e-6)
    }

    @Test
    fun boostMultipliesBy1Point3OnlyWhenReputationIsAtLeast3() {
        val highRepBoosted = ranker.computeScore(
            relevance = 0.8,
            proximity = 0.8,
            reputationAvg = BigDecimal("3.0"),
            freshness = 0.8,
            response = 0.8,
            isBoosted = true
        )
        val highRepUnboosted = ranker.computeScore(
            relevance = 0.8,
            proximity = 0.8,
            reputationAvg = BigDecimal("3.0"),
            freshness = 0.8,
            response = 0.8,
            isBoosted = false
        )
        assertEquals(highRepUnboosted * 1.3, highRepBoosted, 1e-6)

        val lowRepBoosted = ranker.computeScore(
            relevance = 0.8,
            proximity = 0.8,
            reputationAvg = BigDecimal("2.95"),
            freshness = 0.8,
            response = 0.8,
            isBoosted = true
        )
        val lowRepUnboosted = ranker.computeScore(
            relevance = 0.8,
            proximity = 0.8,
            reputationAvg = BigDecimal("2.95"),
            freshness = 0.8,
            response = 0.8,
            isBoosted = false
        )
        assertEquals(lowRepUnboosted, lowRepBoosted, 1e-6)
    }

    @Test
    fun boostedListingWithReputationBelow3DoesNotJumpAhead() {
        val normalGood = ListingCandidate(
            listing = sampleListing(id = "good", boostedUntil = null),
            ownerReputationAvg = BigDecimal("4.2"),
            responseRate = 0.9
        )
        val boostedLowRep = ListingCandidate(
            listing = sampleListing(id = "low-rep-boosted", boostedUntil = now.plus(2, ChronoUnit.DAYS)),
            ownerReputationAvg = BigDecimal("2.8"),
            responseRate = 0.9
        )
        val ranked = ranker.filterAndRank(
            candidates = listOf(boostedLowRep, normalGood),
            referenceMunicipalityId = 101,
            now = now
        )
        assertEquals("good", ranked[0].listing.id)
        assertEquals("low-rep-boosted", ranked[1].listing.id)
        assertFalse(ranked[1].isBoostApplied)
    }

    @Test
    fun searchFiltersByMunicipalityCategoryAndKind() {
        val offerHabana = ListingCandidate(
            listing = sampleListing(id = "1", kind = ListingKind.OFFER, categoryId = "cat-tech", municipalityId = 101)
        )
        val requestHabana = ListingCandidate(
            listing = sampleListing(id = "2", kind = ListingKind.REQUEST, categoryId = "cat-tech", municipalityId = 101)
        )
        val offerSantiago = ListingCandidate(
            listing = sampleListing(id = "3", kind = ListingKind.OFFER, categoryId = "cat-tech", municipalityId = 701)
        )

        val filtered = ranker.filterAndRank(
            candidates = listOf(offerHabana, requestHabana, offerSantiago),
            filter = ListingSearchFilter(
                municipalityId = 101,
                categoryId = "cat-tech",
                kind = ListingKind.REQUEST
            ),
            now = now
        )
        assertEquals(1, filtered.size)
        assertEquals("2", filtered.single().listing.id)
    }
}

class AuctionConfigAndStateMachineTest {

    private val start: Instant = Instant.parse("2026-10-17T14:00:00Z")
    private val end: Instant = Instant.parse("2026-10-18T14:00:00Z")

    private fun validConfig(
        reservePrice: BigDecimal? = BigDecimal("200"),
        requiresIdentity: Boolean = true,
        visibleBids: BidVisibility = BidVisibility.ALL
    ) = AuctionConfig(
        startingPrice = BigDecimal("100"),
        minIncrement = BigDecimal("10"),
        reservePrice = reservePrice,
        startsAt = start,
        endsAt = end,
        autoExtendMinutes = 2,
        requiresIdentity = requiresIdentity,
        visibleBids = visibleBids
    )

    private fun auctionListing(
        config: AuctionConfig = validConfig(),
        status: ListingStatus = ListingStatus.AUCTION_LIVE
    ) = Listing(
        id = "listing-1",
        ownerId = "owner-1",
        title = "Teléfono en subasta",
        description = "Nuevo en caja",
        categoryId = "cat-1",
        price = config.startingPrice,
        saleMode = SaleMode.AUCTION,
        auctionConfig = config.toJsonObject(),
        status = status,
        createdAt = start.minus(1, ChronoUnit.HOURS)
    )

    @Test
    fun auctionConfigJsonRoundTripAndValidation() {
        val config = validConfig()
        val json = AuctionConfigCodec.encode(config)
        val decoded = AuctionConfigCodec.decode(json)
        assertEquals(config, decoded)
        assertNull(AuctionConfigValidator.validate(config))

        // Validaciones: ends_at > starts_at, starting_price > 0
        val badStart = config.copy(startingPrice = BigDecimal.ZERO)
        assertEquals(AuctionConfigValidator.MSG_STARTING_PRICE, AuctionConfigValidator.validate(badStart))

        val badDates = config.copy(endsAt = start)
        assertEquals(AuctionConfigValidator.MSG_ENDS_AFTER_STARTS, AuctionConfigValidator.validate(badDates))

        val badReserve = config.copy(reservePrice = BigDecimal("50"))
        assertEquals(AuctionConfigValidator.MSG_RESERVE_PRICE, AuctionConfigValidator.validate(badReserve))
    }

    @Test
    fun onlyOwnerCanEditAuctionConfigWhileNoBidsExist() {
        val listing = auctionListing()
        assertTrue(AuctionConfigValidator.canEdit(listing, "owner-1", bidsCount = 0))
        assertFalse(AuctionConfigValidator.canEdit(listing, "other-user", bidsCount = 0))
        assertFalse(AuctionConfigValidator.canEdit(listing, "owner-1", bidsCount = 1))
    }

    @Test
    fun bidHistoryIsVisibleOnlyWhenVisibleBidsIsAll() {
        assertTrue(AuctionConfigValidator.canShowBidHistory(validConfig(visibleBids = BidVisibility.ALL)))
        assertFalse(AuctionConfigValidator.canShowBidHistory(validConfig(visibleBids = BidVisibility.OWNER)))
    }

    @Test
    fun stateTransitionsFromScheduledToLiveToEndedAndTerminalStates() {
        val config = validConfig(reservePrice = BigDecimal("150"))
        val scheduledListing = auctionListing(config, ListingStatus.AUCTION_SCHEDULED)

        // Antes de starts_at -> AUCTION_SCHEDULED
        assertEquals(
            AuctionState.AUCTION_SCHEDULED,
            AuctionStateMachine.resolveState(scheduledListing, start.minus(5, ChronoUnit.MINUTES))
        )
        // Entre starts_at y ends_at -> AUCTION_LIVE
        assertEquals(
            AuctionState.AUCTION_LIVE,
            AuctionStateMachine.resolveState(scheduledListing, start.plus(5, ChronoUnit.MINUTES))
        )
        // Después de ends_at -> AUCTION_ENDED
        assertEquals(
            AuctionState.AUCTION_ENDED,
            AuctionStateMachine.resolveState(scheduledListing, end.plus(1, ChronoUnit.MINUTES))
        )

        // Cierre sin pujas -> EXPIRED
        val expiredOutcome = AuctionStateMachine.closeAuction(
            listing = scheduledListing,
            bids = emptyList(),
            now = end.plus(1, ChronoUnit.MINUTES)
        )
        assertEquals(AuctionState.EXPIRED, expiredOutcome.finalState)
        assertEquals(ListingStatus.EXPIRED, expiredOutcome.updatedListing.status)
        assertNull(expiredOutcome.winningBid)

        // Cierre con pujas pero sin alcanzar reserve -> NO_RESERVE_MET
        val lowBid = Bid(
            id = "bid-1",
            listingId = scheduledListing.id,
            bidderId = "bidder-1",
            amount = BigDecimal("120"),
            createdAt = start.plus(10, ChronoUnit.MINUTES)
        )
        val noReserveOutcome = AuctionStateMachine.closeAuction(
            listing = scheduledListing,
            bids = listOf(lowBid),
            now = end.plus(1, ChronoUnit.MINUTES)
        )
        assertEquals(AuctionState.NO_RESERVE_MET, noReserveOutcome.finalState)
        assertEquals(ListingStatus.NO_RESERVE_MET, noReserveOutcome.updatedListing.status)
        assertNull(noReserveOutcome.winningBid)
        assertEquals(BidStatus.LOSING, noReserveOutcome.updatedBids.single().status)

        // Cierre con pujas alcanzando reserve -> AWARDED y gana la mayor
        val highBid = Bid(
            id = "bid-2",
            listingId = scheduledListing.id,
            bidderId = "bidder-2",
            amount = BigDecimal("180"),
            createdAt = start.plus(20, ChronoUnit.MINUTES)
        )
        val awardedOutcome = AuctionStateMachine.closeAuction(
            listing = scheduledListing,
            bids = listOf(lowBid, highBid),
            now = end.plus(1, ChronoUnit.MINUTES)
        )
        assertEquals(AuctionState.AWARDED, awardedOutcome.finalState)
        assertEquals(ListingStatus.AWARDED, awardedOutcome.updatedListing.status)
        assertEquals(BigDecimal("180"), awardedOutcome.updatedListing.price)
        assertNotNull(awardedOutcome.winningBid)
        assertEquals("bid-2", awardedOutcome.winningBid?.id)
        assertEquals(BidStatus.WINNING, awardedOutcome.winningBid?.status)
    }

    @Test
    fun autoExtendAddsMinutesWhenBidPlacedNearEnd() {
        val config = validConfig()
        val bidNearEnd = end.minus(90, ChronoUnit.SECONDS) // <= 2 minutos
        val extended = AuctionStateMachine.computeAutoExtendedEndsAt(config, bidNearEnd)
        assertEquals(end.plus(2, ChronoUnit.MINUTES), extended)

        val bidEarly = end.minus(10, ChronoUnit.MINUTES)
        val notExtended = AuctionStateMachine.computeAutoExtendedEndsAt(config, bidEarly)
        assertEquals(end, notExtended)
    }

    @Test
    fun bidSecurityEnforcesIdentityMax3BidsAndNoOpenDispute() {
        val listing = auctionListing()
        val liveTime = start.plus(30, ChronoUnit.MINUTES)

        val phoneOnlyUser = User(
            id = "user-phone",
            verificationLevel = VerificationLevel.PHONE,
            status = UserStatus.TEMP,
            createdAt = start
        )
        val verifiedUser = User(
            id = "user-verified",
            verificationLevel = VerificationLevel.IDENTITY,
            status = UserStatus.ACTIVE,
            createdAt = start
        )

        // 1. Pujar requiere verification_level='identity' si requires_identity=true
        assertEquals(
            BidValidator.MSG_IDENTITY_REQUIRED,
            BidValidator.validateBid(
                listing = listing,
                bidder = phoneOnlyUser,
                amount = BigDecimal("100"),
                existingBids = emptyList(),
                hasOpenDispute = false,
                now = liveTime
            )
        )

        // 2. No se puede pujar si el usuario tiene una disputa abierta
        assertEquals(
            BidValidator.MSG_OPEN_DISPUTE,
            BidValidator.validateBid(
                listing = listing,
                bidder = verifiedUser,
                amount = BigDecimal("100"),
                existingBids = emptyList(),
                hasOpenDispute = true,
                now = liveTime
            )
        )

        // 3. Máximo 3 pujas por usuario por subasta
        val threeExistingBids = (1..3).map { idx ->
            Bid(
                id = "b-$idx",
                listingId = listing.id,
                bidderId = verifiedUser.id,
                amount = BigDecimal(100 + idx * 10),
                createdAt = liveTime.plusSeconds(idx.toLong())
            )
        }
        assertEquals(
            BidValidator.MSG_MAX_BIDS_REACHED,
            BidValidator.validateBid(
                listing = listing,
                bidder = verifiedUser,
                amount = BigDecimal("200"),
                existingBids = threeExistingBids,
                hasOpenDispute = false,
                now = liveTime
            )
        )

        // Puja válida cuando cumple todas las reglas
        assertNull(
            BidValidator.validateBid(
                listing = listing,
                bidder = verifiedUser,
                amount = BigDecimal("140"),
                existingBids = threeExistingBids.take(2),
                hasOpenDispute = false,
                now = liveTime
            )
        )
    }
}

class ListingValidatorAndCompressorTest {

    private val compressor = ImageCompressor()

    @Test
    fun offerAndRequestValidationRules() {
        val validOffer = ListingDraft(
            kind = ListingKind.OFFER,
            title = "Cemento P-350",
            description = "Sacos sellados con entrega en Playa",
            categoryId = "cat-1",
            price = BigDecimal("1500"),
            municipalityId = 102,
            radiusKm = 15,
            privacyLevel = PrivacyLevel.PUBLIC
        )
        assertNull(ListingValidator.validate(validOffer))

        // Oferta con precio "a convenir"
        val negotiableOffer = validOffer.copy(price = BigDecimal.ZERO, priceNegotiable = true)
        assertNull(ListingValidator.validate(negotiableOffer))

        // Demanda con visibilidad solo proveedores
        val providersRequest = validOffer.copy(
            kind = ListingKind.REQUEST,
            visibility = ListingVisibility.PROVIDERS_ONLY
        )
        assertNull(ListingValidator.validate(providersRequest))

        // Oferta NO puede tener visibilidad providers_only
        val invalidOfferVisibility = validOffer.copy(
            kind = ListingKind.OFFER,
            visibility = ListingVisibility.PROVIDERS_ONLY
        )
        assertEquals(
            ListingValidator.MSG_OFFER_VISIBILITY,
            ListingValidator.validate(invalidOfferVisibility)
        )
    }

    @Test
    fun imageCompressorCompressesOversizedJpegBelow1Mb() {
        // Creamos un payload JPEG sintético de 1.5 MB (> 1 MB)
        val oversized = ByteArray((1.5 * 1024 * 1024).toInt()).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xD8.toByte()
            this[2] = 0xFF.toByte()
        }
        val result = compressor.compressForListing(oversized)
        assertTrue(result is Result.Success)
        val compressed = (result as Result.Success).data
        assertTrue(compressed.size <= ListingValidator.MAX_PHOTO_BYTES)
        assertNull(ListingValidator.validatePhoto(compressed))
    }
}

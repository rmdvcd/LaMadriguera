package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.*
import com.lamadriguera.app.domain.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

class RowMappersTest {
    @Test fun userRowRoundTrip() {
        val row = UserRow(
            id = "id-value",
            phone = "phone-value",
            verificationLevel = VerificationLevel.NONE,
            status = UserStatus.TEMP,
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(UserRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(UserRow.serializer(), json))
    }

    @Test fun identityVerificationRowRoundTrip() {
        val row = IdentityVerificationRow(
            id = "id-value",
            userId = "userId-value",
            docType = "docType-value",
            docRef = "\\x0080ff",
            fullName = "fullName-value",
            birthdate = "1990-03-14",
            status = IdentityVerificationStatus.APPROVED,
            verifiedBy = "verifiedBy-value",
            verifiedAt = "2026-10-17T12:30:00Z",
            retentionUntil = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(IdentityVerificationRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(IdentityVerificationRow.serializer(), json))
    }

    @Test fun profileRowRoundTrip() {
        val row = ProfileRow(
            userId = "userId-value",
            displayName = "displayName-value",
            alias = "alias-value",
            avatarUrl = "avatarUrl-value",
            municipalityId = 25,
            privacyLevel = PrivacyLevel.PUBLIC,
            bio = "bio-value",
            reputationAvg = BigDecimal("4.25"),
            reviewsCount = 25,
            defaultRevealPolicy = RevealPolicy.ON_DEAL
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(ProfileRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(ProfileRow.serializer(), json))
    }

    @Test fun categoryRowRoundTrip() {
        val row = CategoryRow(
            id = "id-value",
            name = "name-value",
            parentId = "parentId-value",
            isCustom = true,
            createdBy = "createdBy-value"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(CategoryRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(CategoryRow.serializer(), json))
    }

    @Test fun listingRowRoundTrip() {
        val row = ListingRow(
            id = "id-value",
            ownerId = "ownerId-value",
            kind = ListingKind.OFFER,
            title = "title-value",
            description = "description-value",
            categoryId = "categoryId-value",
            price = BigDecimal("1234567890.0123456789"),
            currency = "currency-value",
            priceNegotiable = true,
            municipalityId = 25,
            radiusKm = 25,
            privacyLevel = PrivacyLevel.PUBLIC,
            saleMode = SaleMode.FIXED,
            auctionConfig = SupabaseJson.parseToJsonElement("""{"ends_at":"2026-11-01T00:00:00Z","reserve":12.50}""") as kotlinx.serialization.json.JsonObject,
            status = ListingStatus.ACTIVE,
            boostedUntil = "2026-10-17T12:30:00Z",
            createdAt = "2026-10-17T12:30:00Z",
            photoUrl = "https://placeholder.supabase.co/storage/v1/object/public/listing-photos/u/1.jpg",
            visibility = ListingVisibility.PUBLIC
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(ListingRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(ListingRow.serializer(), json))
    }

    @Test fun bidRowRoundTrip() {
        val row = BidRow(
            id = "id-value",
            listingId = "listingId-value",
            bidderId = "bidderId-value",
            amount = BigDecimal("1234567890.0123456789"),
            deviceHash = "deviceHash-value",
            ipHash = "ipHash-value",
            status = BidStatus.VALID,
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(BidRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(BidRow.serializer(), json))
    }

    @Test fun conversationRowRoundTrip() {
        val row = ConversationRow(
            id = "id-value",
            listingId = "listingId-value",
            participantA = "participantA-value",
            participantB = "participantB-value",
            revealPolicy = RevealPolicy.NEVER,
            sessionKeyWrapped = "\\x0080ff",
            disputeKeySplitA = "\\x0080ff",
            disputeKeySplitB = "\\x0080ff",
            status = "disputed"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(ConversationRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(ConversationRow.serializer(), json))
    }

    @Test fun messageRowRoundTrip() {
        val row = MessageRow(
            id = "id-value",
            conversationId = "conversationId-value",
            senderId = "senderId-value",
            type = MessageType.TEXT,
            contentRef = "contentRef-value",
            expiresAt = "2026-10-17T12:30:00Z",
            reported = true,
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(MessageRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(MessageRow.serializer(), json))
    }

    @Test fun reviewRowRoundTrip() {
        val row = ReviewRow(
            id = "id-value",
            reviewerId = "reviewerId-value",
            revieweeId = "revieweeId-value",
            conversationId = "conversationId-value",
            rating = 4,
            text = "text-value",
            status = "status-value",
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(ReviewRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(ReviewRow.serializer(), json))
    }

    @Test fun reportRowRoundTrip() {
        val row = ReportRow(
            id = "id-value",
            reporterId = "reporterId-value",
            targetType = "targetType-value",
            targetId = "targetId-value",
            reason = "reason-value",
            evidenceUrl = "evidenceUrl-value",
            status = "status-value",
            handledBy = "handledBy-value",
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(ReportRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(ReportRow.serializer(), json))
    }

    @Test fun paymentRowRoundTrip() {
        val row = PaymentRow(
            id = "id-value",
            userId = "userId-value",
            productType = ProductType.BOOST,
            targetId = "targetId-value",
            amount = BigDecimal("1234567890.0123456789"),
            method = "method-value",
            proofRef = "proofRef-value",
            code = "code-value",
            status = PaymentStatus.PENDING,
            confirmedBy = "confirmedBy-value",
            confirmedAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(PaymentRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(PaymentRow.serializer(), json))
    }

    @Test fun boostRowRoundTrip() {
        val row = BoostRow(
            id = "id-value",
            listingId = "listingId-value",
            tier = "tier-value",
            startsAt = "2026-10-17T12:30:00Z",
            endsAt = "2026-10-17T12:30:00Z",
            paymentId = "paymentId-value"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(BoostRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(BoostRow.serializer(), json))
    }

    @Test fun bettingGroupRowRoundTrip() {
        val row = BettingGroupRow(
            id = "id-value",
            ownerId = "ownerId-value",
            name = "name-value",
            topic = "topic-value",
            visibility = "visibility-value",
            status = "status-value",
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(BettingGroupRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(BettingGroupRow.serializer(), json))
    }

    @Test fun bettingGroupMemberRowRoundTrip() {
        val row = BettingGroupMemberRow(
            id = "id-value",
            groupId = "groupId-value",
            userId = "userId-value",
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(BettingGroupMemberRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(BettingGroupMemberRow.serializer(), json))
    }

    @Test fun betRowRoundTrip() {
        val row = BetRow(
            id = "id-value",
            groupId = "groupId-value",
            creatorId = "creatorId-value",
            acceptorId = "acceptorId-value",
            description = "description-value",
            stakeAmount = BigDecimal("1234567890.0123456789"),
            stakeCurrency = "stakeCurrency-value",
            odds = BigDecimal("1234567890.0123456789"),
            status = BetStatus.OPEN,
            settledBy = "settledBy-value",
            settledAt = "2026-10-17T12:30:00Z",
            evidenceRef = "evidenceRef-value",
            evidenceText = "evidenceText-value",
            acceptedAt = "2026-10-17T12:30:00Z",
            decisionBy = "decisionBy-value",
            decisionNote = "decisionNote-value",
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(BetRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(BetRow.serializer(), json))
    }

    @Test fun betEventRowRoundTrip() {
        val row = BetEventRow(
            id = "event-id",
            betId = "bet-id",
            actorId = "actor-id",
            eventType = "disputed",
            outcome = "acceptor",
            evidenceText = "No estoy de acuerdo",
            evidenceRef = "user/group/bet/proof.jpg",
            decisionNote = null,
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(BetEventRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(BetEventRow.serializer(), json))
    }

    @Test fun bettingControlsRowRoundTrip() {
        val row = BettingControlsRow(
            userId = "user-id",
            dailyLimitCup = BigDecimal("1000.25"),
            selfExcludedUntil = "2026-11-16T12:30:00Z",
            updatedAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(BettingControlsRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(BettingControlsRow.serializer(), json))
    }

    @Test fun betOddsCanBeNullAndEncodeAsSqlJsonNull() {
        val row = BetRow(
            id = "bet-id",
            groupId = "group-id",
            creatorId = "creator-id",
            description = "Partido",
            stakeAmount = BigDecimal("25.50"),
            odds = null,
            createdAt = "2026-10-17T12:30:00Z"
        )
        val json = SupabaseJson.encodeToString(BetRow.serializer(), row)
        assertTrue(json.contains("\"odds\":null"))
        assertEquals(row, SupabaseJson.decodeFromString(BetRow.serializer(), json))
    }

    @Test fun moderationActionRowRoundTrip() {
        val row = ModerationActionRow(
            id = "id-value",
            targetType = "targetType-value",
            targetId = "targetId-value",
            source = ModerationSource.USER_REPORT,
            category = "category-value",
            severity = 4,
            action = "action-value",
            reason = "reason-value",
            actorId = "actorId-value",
            createdAt = "2026-10-17T12:30:00Z"
        )
        val domain = row.toDomain()
        assertEquals(row, domain.toRow())
        val json = SupabaseJson.encodeToString(ModerationActionRow.serializer(), row)
        assertEquals(row, SupabaseJson.decodeFromString(ModerationActionRow.serializer(), json))
    }

    @Test fun listingUsesWireNamesExactMoneyAndDomainTimestamp() {
        val json = """{
          "id":"listing", "owner_id":"owner", "kind":"request", "title":"Busco", "description":"Detalle",
          "category_id":"category", "price":12345678901234567890.123456789,
          "privacy_level":"anon", "sale_mode":"auction", "status":"paused_payment",
          "auction_config":{"reserve":123.45}, "created_at":"2026-10-17T12:30:00+00:00",
          "unknown_server_column":true
        }"""
        val row = SupabaseJson.decodeFromString(ListingRow.serializer(), json)
        val listing = row.toDomain()
        assertEquals("owner", listing.ownerId)
        assertEquals(ListingKind.REQUEST, listing.kind)
        assertEquals(ListingStatus.PAUSED_PAYMENT, listing.status)
        assertEquals(PrivacyLevel.ANON, listing.privacyLevel)
        assertEquals(SaleMode.AUCTION, listing.saleMode)
        assertEquals(BigDecimal("12345678901234567890.123456789"), listing.price)
        assertEquals(Instant.parse("2026-10-17T12:30:00Z"), listing.createdAt)
        assertEquals(10, listing.radiusKm)
        assertNull(listing.boostedUntil)
        val encoded = SupabaseJson.encodeToString(ListingRow.serializer(), row)
        assertTrue(encoded.contains("\"price\":12345678901234567890.123456789"))
        assertTrue(encoded.contains("\"owner_id\":\"owner\""))
        assertTrue(encoded.contains("\"status\":\"paused_payment\""))
    }

    @Test fun nullableKeysAndPostgresByteaAreNotBase64OrUtf8() {
        val row = ConversationRow("c", "l", "a", "b", sessionKeyWrapped = "\\x0080ff")
        val conversation = row.toDomain()
        assertArrayEquals(byteArrayOf(0, -128, -1), conversation.sessionKeyWrapped)
        assertNull(conversation.disputeKeySplitA)
        assertNull(conversation.disputeKeySplitB)
        assertEquals(row, conversation.toRow())
        assertEquals("\\x", byteArrayOf().encodeBytea())
        assertArrayEquals(byteArrayOf(), "\\x".decodeBytea())
    }

    @Test fun identityBirthdateIsDateNotDeviceTimezone() {
        val row = IdentityVerificationRow("i", "u", "id", "\\xff", "Nombre",
            "1990-03-14", retentionUntil = "2026-11-17T00:00:00Z")
        val identity = row.toDomain()
        assertEquals(LocalDate.of(1990, 3, 14), identity.birthdate)
        assertArrayEquals(byteArrayOf(-1), identity.docRef)
        assertNull(identity.verifiedAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidByteaPrefixIsRejected() { "base64==".decodeBytea() }

    @Test(expected = IllegalArgumentException::class)
    fun oddByteaLengthIsRejected() { "\\xabc".decodeBytea() }

    @Test(expected = IllegalArgumentException::class)
    fun invalidHexIsRejected() { "\\xzz".decodeBytea() }

    @Test(expected = IllegalArgumentException::class)
    fun signedHexIsRejected() { "\\x-1".decodeBytea() }

    @Test(expected = SerializationException::class)
    fun unknownEnumIsNotSilentlyMappedToAnActiveUser() {
        SupabaseJson.decodeFromString(UserRow.serializer(),
            """{"id":"u","status":"unknown","created_at":"2026-10-17T00:00:00Z"}""")
    }
}

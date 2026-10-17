package com.lamadriguera.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class VerificationLevel {
    @SerialName("none") NONE,
    @SerialName("phone") PHONE,
    @SerialName("identity") IDENTITY
}

@Serializable
enum class UserStatus {
    @SerialName("temp") TEMP,
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
    @SerialName("banned") BANNED
}

@Serializable
enum class PrivacyLevel {
    @SerialName("public") PUBLIC,
    @SerialName("anon") ANON
}

@Serializable
enum class ListingKind {
    @SerialName("offer") OFFER,
    @SerialName("request") REQUEST
}

@Serializable
enum class SaleMode {
    @SerialName("fixed") FIXED,
    @SerialName("auction") AUCTION
}

@Serializable
enum class ListingStatus {
    @SerialName("active") ACTIVE,
    @SerialName("paused_payment") PAUSED_PAYMENT,
    @SerialName("auction_live") AUCTION_LIVE,
    @SerialName("awarded") AWARDED,
    @SerialName("closed") CLOSED
}

@Serializable
enum class BidStatus {
    @SerialName("valid") VALID,
    @SerialName("retracted") RETRACTED,
    @SerialName("winning") WINNING,
    @SerialName("losing") LOSING
}

@Serializable
enum class RevealPolicy {
    @SerialName("never") NEVER,
    @SerialName("on_deal") ON_DEAL,
    @SerialName("on_dispute") ON_DISPUTE,
    @SerialName("configurable") CONFIGURABLE
}

@Serializable
enum class MessageType {
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("audio") AUDIO,
    @SerialName("location") LOCATION
}

@Serializable
enum class ProductType {
    @SerialName("boost") BOOST,
    @SerialName("feature") FEATURE,
    @SerialName("reach") REACH
}

@Serializable
enum class PaymentStatus {
    @SerialName("pending") PENDING,
    @SerialName("review") REVIEW,
    @SerialName("confirmed") CONFIRMED,
    @SerialName("rejected") REJECTED,
    @SerialName("expired") EXPIRED
}

@Serializable
enum class BetStatus {
    @SerialName("open") OPEN,
    @SerialName("accepted") ACCEPTED,
    @SerialName("settled_creator") SETTLED_CREATOR,
    @SerialName("settled_acceptor") SETTLED_ACCEPTOR,
    @SerialName("void") VOID,
    @SerialName("disputed") DISPUTED
}

@Serializable
enum class ModerationSource {
    @SerialName("ai") AI,
    @SerialName("user_report") USER_REPORT,
    @SerialName("admin") ADMIN,
    @SerialName("group_admin") GROUP_ADMIN
}

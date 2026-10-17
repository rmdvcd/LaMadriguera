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

/**
 * Estado de la solicitud de verificación de identidad.
 * El cliente solo crea solicitudes [PENDING]; un administrador pasa a [APPROVED]
 * o [REJECTED]. El wire coincide con el CHECK de identity_verifications.
 */
@Serializable
enum class IdentityVerificationStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED
}

/**
 * Clase de cuenta en el dispositivo: 1 teléfono (dispositivo) = 1 cuenta
 * principal. Las cuentas adicionales requieren verificación de identidad para
 * subastas y apuestas (ver [UserCapabilities]).
 */
enum class AccountKind {
    PRIMARY,
    SECONDARY
}

@Serializable
enum class ListingKind {
    @SerialName("offer") OFFER,
    @SerialName("request") REQUEST
}

/**
 * Visibilidad de las demandas (kind = 'request').
 * Las ofertas son siempre públicas; las demandas pueden restringirse solo a proveedores.
 */
@Serializable
enum class ListingVisibility {
    @SerialName("public") PUBLIC,
    @SerialName("providers_only") PROVIDERS_ONLY
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
    @SerialName("closed") CLOSED,
    @SerialName("fixed") FIXED,
    @SerialName("auction_scheduled") AUCTION_SCHEDULED,
    @SerialName("auction_ended") AUCTION_ENDED,
    @SerialName("no_reserve_met") NO_RESERVE_MET,
    @SerialName("expired") EXPIRED
}

/**
 * Visibilidad del historial de pujas en una subasta.
 * Si es [ALL], todos los usuarios pueden abrir BidHistoryScreen;
 * si es [OWNER], solo el propietario ve las pujas.
 */
@Serializable
enum class BidVisibility {
    @SerialName("all") ALL,
    @SerialName("owner") OWNER
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

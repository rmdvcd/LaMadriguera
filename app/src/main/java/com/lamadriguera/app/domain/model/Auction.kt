package com.lamadriguera.app.domain.model

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Configuración de subasta serializada en `listings.auction_config` (JSONB).
 *
 * Campos requeridos por especificación:
 * - starting_price (> 0)
 * - min_increment (> 0)
 * - reserve_price opcional (>= starting_price si se define)
 * - starts_at (ISO-8601)
 * - ends_at (ISO-8601, > starts_at)
 * - auto_extend_minutes (default 2)
 * - requires_identity (true si es subasta con dinero)
 * - visible_bids ('all' | 'owner')
 */
data class AuctionConfig(
    val startingPrice: BigDecimal,
    val minIncrement: BigDecimal,
    val reservePrice: BigDecimal? = null,
    val startsAt: Instant,
    val endsAt: Instant,
    val autoExtendMinutes: Int = DEFAULT_AUTO_EXTEND_MINUTES,
    val requiresIdentity: Boolean = startingPrice > BigDecimal.ZERO,
    val visibleBids: BidVisibility = BidVisibility.ALL
) {
    fun toJsonObject(): JsonObject = AuctionConfigCodec.encode(this)

    companion object {
        const val DEFAULT_AUTO_EXTEND_MINUTES = 2

        fun fromJsonObjectOrNull(json: JsonObject?): AuctionConfig? =
            AuctionConfigCodec.decodeOrNull(json)
    }
}

/**
 * Codec bidireccional entre [AuctionConfig] y [JsonObject] (`listings.auction_config`).
 */
object AuctionConfigCodec {

    const val KEY_STARTING_PRICE = "starting_price"
    const val KEY_MIN_INCREMENT = "min_increment"
    const val KEY_RESERVE_PRICE = "reserve_price"
    const val KEY_STARTS_AT = "starts_at"
    const val KEY_ENDS_AT = "ends_at"
    const val KEY_AUTO_EXTEND_MINUTES = "auto_extend_minutes"
    const val KEY_REQUIRES_IDENTITY = "requires_identity"
    const val KEY_VISIBLE_BIDS = "visible_bids"

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(config: AuctionConfig): JsonObject = buildJsonObject {
        put(KEY_STARTING_PRICE, JsonUnquotedLiteral(config.startingPrice.toPlainString()))
        put(KEY_MIN_INCREMENT, JsonUnquotedLiteral(config.minIncrement.toPlainString()))
        if (config.reservePrice != null) {
            put(KEY_RESERVE_PRICE, JsonUnquotedLiteral(config.reservePrice.toPlainString()))
        } else {
            put(KEY_RESERVE_PRICE, JsonNull)
        }
        put(KEY_STARTS_AT, config.startsAt.toString())
        put(KEY_ENDS_AT, config.endsAt.toString())
        put(KEY_AUTO_EXTEND_MINUTES, config.autoExtendMinutes)
        put(KEY_REQUIRES_IDENTITY, config.requiresIdentity)
        put(
            KEY_VISIBLE_BIDS,
            when (config.visibleBids) {
                BidVisibility.ALL -> "all"
                BidVisibility.OWNER -> "owner"
            }
        )
    }

    fun decodeOrNull(json: JsonObject?): AuctionConfig? {
        if (json == null) return null
        return runCatching { decode(json) }.getOrNull()
    }

    fun decode(json: JsonObject): AuctionConfig {
        val startingPrice = json.decimalOrNull(KEY_STARTING_PRICE)
            ?: throw IllegalArgumentException("Missing starting_price")
        val minIncrement = json.decimalOrNull(KEY_MIN_INCREMENT)
            ?: throw IllegalArgumentException("Missing min_increment")
        val reservePrice = json.decimalOrNull(KEY_RESERVE_PRICE)
            ?: json.decimalOrNull("reserve")
        val startsAt = json.instantOrNull(KEY_STARTS_AT)
            ?: throw IllegalArgumentException("Missing starts_at")
        val endsAt = json.instantOrNull(KEY_ENDS_AT)
            ?: throw IllegalArgumentException("Missing ends_at")
        val autoExtend = (json[KEY_AUTO_EXTEND_MINUTES] as? JsonPrimitive)?.intOrNull
            ?: AuctionConfig.DEFAULT_AUTO_EXTEND_MINUTES
        val requiresIdentity = (json[KEY_REQUIRES_IDENTITY] as? JsonPrimitive)?.booleanOrNull
            ?: (startingPrice > BigDecimal.ZERO)
        val visibleBidsRaw = (json[KEY_VISIBLE_BIDS] as? JsonPrimitive)?.contentOrNull ?: "all"
        val visibleBids = when (visibleBidsRaw.lowercase()) {
            "owner" -> BidVisibility.OWNER
            else -> BidVisibility.ALL
        }
        return AuctionConfig(
            startingPrice = startingPrice,
            minIncrement = minIncrement,
            reservePrice = reservePrice,
            startsAt = startsAt,
            endsAt = endsAt,
            autoExtendMinutes = autoExtend,
            requiresIdentity = requiresIdentity,
            visibleBids = visibleBids
        )
    }

    private fun JsonObject.decimalOrNull(key: String): BigDecimal? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val content = (element as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        if (content.isEmpty() || content.equals("null", ignoreCase = true)) return null
        return BigDecimal(content)
    }

    private fun JsonObject.instantOrNull(key: String): Instant? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val content = (element as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        if (content.isEmpty()) return null
        return Instant.parse(content)
    }
}

/**
 * Estados del flujo de subasta:
 * fixed -> auction_scheduled -> auction_live -> auction_ended ->
 * awarded | no_reserve_met | expired
 */
@Serializable
enum class AuctionState {
    @SerialName("fixed") FIXED,
    @SerialName("auction_scheduled") AUCTION_SCHEDULED,
    @SerialName("auction_live") AUCTION_LIVE,
    @SerialName("auction_ended") AUCTION_ENDED,
    @SerialName("awarded") AWARDED,
    @SerialName("no_reserve_met") NO_RESERVE_MET,
    @SerialName("expired") EXPIRED
}

/**
 * Vista agregada de una subasta sobre una publicación ([Listing]).
 */
data class Auction(
    val id: String,
    val listingId: String,
    val listing: Listing? = null,
    val config: AuctionConfig? = null,
    val startingPrice: BigDecimal,
    val currentBid: BigDecimal? = null,
    val currentBidderId: String? = null,
    val endsAt: Instant,
    val state: AuctionState = AuctionState.AUCTION_LIVE,
    val bidsCount: Int = 0,
    val userBidsCount: Int = 0
) {
    /** Monto mínimo requerido para la siguiente puja válida. */
    val minimumNextBid: BigDecimal
        get() {
            val increment = config?.minIncrement ?: BigDecimal.ONE
            return if (currentBid == null) startingPrice else currentBid + increment
        }
}

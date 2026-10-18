package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.domain.model.CubanMunicipalities
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.ListingCandidate
import com.lamadriguera.app.domain.model.ListingSearchFilter
import com.lamadriguera.app.domain.model.RankedListing
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Motor de búsqueda, filtrado y ranking de publicaciones.
 *
 * Pesos de ranking:
 * - Relevancia: 45% (0.45)
 * - Proximidad: 25% (0.25)
 * - Reputación: 15% (0.15)
 * - Frescura:   10% (0.10)
 * - Respuesta:   5% (0.05)
 *
 * Promociones:
 * - Un boost pagado activo multiplica por ×1.3 incluso antes de que el dueño tenga reseñas;
 *   el boost legado/no pagado conserva el umbral de reputación de 3.0.
 * - Un producto de alcance activo permite aparecer en su ámbito y aplica ×1.15 en ese feed geográfico.
 */
class ListingRanker @Inject constructor() {

    fun filterAndRank(
        candidates: List<ListingCandidate>,
        filter: ListingSearchFilter = ListingSearchFilter(),
        referenceMunicipalityId: Int? = filter.municipalityId,
        now: Instant = Instant.now()
    ): List<RankedListing> {
        val targetMunicipality = filter.municipalityId ?: referenceMunicipalityId
        val ranked = candidates
            .asSequence()
            .filter { matchesFilter(it, filter, now) }
            .map { candidate -> scoreCandidate(candidate, filter.query, targetMunicipality, now) }
            .sortedWith(
                compareByDescending<RankedListing> { it.finalScore }
                    .thenByDescending { it.baseScore }
                    .thenByDescending { it.listing.createdAt }
            )
            .toList()
        return placeFeaturedSlots(ranked, filter, now)
    }

    fun matchesFilter(
        candidate: ListingCandidate,
        filter: ListingSearchFilter,
        now: Instant = Instant.now()
    ): Boolean {
        val listing = candidate.listing
        if (filter.kind != null && listing.kind != filter.kind) return false
        if (filter.saleMode != null && listing.saleMode != filter.saleMode) return false
        if (!filter.categoryId.isNullOrBlank() && listing.categoryId != filter.categoryId) return false
        if (filter.municipalityId != null && !isVisibleInMunicipality(candidate, filter.municipalityId, now)) return false
        if (filter.query.isNotBlank() && computeRelevance(listing, filter.query, candidate.categoryName) <= 0.0) {
            return false
        }
        return true
    }

    /**
     * Inserta las publicaciones de la categoría/municipio en un espacio de cada
     * cinco (después de cuatro orgánicas). Si no hay cuatro espacios orgánicos
     * disponibles no se fuerza una posición patrocinada fuera de la regla.
     */
    private fun placeFeaturedSlots(
        ranked: List<RankedListing>,
        filter: ListingSearchFilter,
        now: Instant
    ): List<RankedListing> {
        if (filter.categoryId.isNullOrBlank() || filter.municipalityId == null) return ranked
        val eligibleIds = ranked.asSequence()
            .filter { it.isFeatured }
            .filter { it.listing.categoryId == filter.categoryId }
            .filter { it.listing.municipalityId == filter.municipalityId }
            .filter { it.listing.boostedUntil?.isAfter(now) == true }
            .map { it.listing.id }
            .toSet()
        if (eligibleIds.isEmpty()) return ranked

        val organic = ranked.filterNot { it.listing.id in eligibleIds }
        val featured = ranked.filter { it.listing.id in eligibleIds }
        val result = ArrayList<RankedListing>(ranked.size)
        var organicIndex = 0
        var featuredIndex = 0
        while (organicIndex + 4 <= organic.size && featuredIndex < featured.size) {
            result.addAll(organic.subList(organicIndex, organicIndex + 4))
            organicIndex += 4
            result += featured[featuredIndex++]
        }
        result.addAll(organic.drop(organicIndex))
        return result
    }

    private fun isVisibleInMunicipality(
        candidate: ListingCandidate,
        targetMunicipalityId: Int,
        now: Instant
    ): Boolean = candidate.listing.municipalityId == targetMunicipalityId ||
        hasActiveReachPromotion(candidate, targetMunicipalityId, now)

    private fun hasActiveReachPromotion(
        candidate: ListingCandidate,
        targetMunicipalityId: Int,
        now: Instant
    ): Boolean {
        val origin = candidate.listing.municipalityId
        return candidate.activePromotions.asSequence()
            .filter { it.productType == com.lamadriguera.app.domain.model.ProductType.REACH }
            .filter { it.startsAt <= now && it.endsAt > now }
            .any { promotion ->
                when (promotion.reachScope) {
                    com.lamadriguera.app.domain.model.PromotionScope.MUNICIPALITY -> origin == targetMunicipalityId
                    com.lamadriguera.app.domain.model.PromotionScope.PROVINCE ->
                        origin != null && CubanMunicipalities.isSameProvince(origin, targetMunicipalityId)
                    com.lamadriguera.app.domain.model.PromotionScope.NATIONAL -> true
                    null -> false
                }
            }
    }

    fun scoreCandidate(
        candidate: ListingCandidate,
        query: String = "",
        targetMunicipalityId: Int? = null,
        now: Instant = Instant.now()
    ): RankedListing {
        val listing = candidate.listing
        val relevance = computeRelevance(listing, query, candidate.categoryName)
        val proximity = computeProximity(listing, targetMunicipalityId, candidate.distanceKm)
        val reputation = computeReputation(candidate.ownerReputationAvg)
        val freshness = computeFreshness(listing.createdAt, now)
        val response = candidate.responseRate.coerceIn(0.0, 1.0)

        val baseScore = computeBaseScore(
            relevance = relevance,
            proximity = proximity,
            reputation = reputation,
            freshness = freshness,
            response = response
        )

        val hasActiveBoost = listing.boostedUntil != null && listing.boostedUntil.isAfter(now)
        val hasPaidBoost = candidate.activePromotions.any {
            it.productType == com.lamadriguera.app.domain.model.ProductType.BOOST &&
                it.startsAt <= now && it.endsAt > now
        }
        // Un boost comprado se cumple aunque el anunciante aún no tenga reputación; la
        // antigua bonificación no pagada conserva el umbral antispam existente.
        val canApplyBoost = hasPaidBoost || (
            hasActiveBoost && candidate.activePromotions.isEmpty() &&
                candidate.ownerReputationAvg >= MIN_REPUTATION_FOR_BOOST
        )
        val hasActiveReach = targetMunicipalityId?.let {
            hasActiveReachPromotion(candidate, it, now)
        } == true
        val boostMultiplier = if (canApplyBoost) BOOST_MULTIPLIER else 1.0
        val reachMultiplier = if (hasActiveReach) REACH_MULTIPLIER else 1.0
        val finalScore = baseScore * boostMultiplier * reachMultiplier

        return RankedListing(
            listing = listing,
            ownerReputationAvg = candidate.ownerReputationAvg,
            ownerReviewsCount = candidate.ownerReviewsCount,
            ownerDisplayName = candidate.ownerDisplayName,
            categoryName = candidate.categoryName,
            relevanceScore = relevance,
            proximityScore = proximity,
            reputationScore = reputation,
            freshnessScore = freshness,
            responseScore = response,
            baseScore = baseScore,
            finalScore = finalScore,
            isBoostApplied = canApplyBoost,
            isPromoted = hasActiveBoost,
            isFeatured = candidate.activePromotions.any {
                it.productType == com.lamadriguera.app.domain.model.ProductType.FEATURE &&
                    it.startsAt <= now && it.endsAt > now
            }
        )
    }

    /**
     * Calcula la puntuación combinada con factores ya normalizados en [0.0, 1.0].
     * Si `isBoosted = true` y `reputationAvg >= 3`, aplica el multiplicador `×1.3`;
     * si `reputationAvg < 3`, no aplica el boost.
     */
    fun computeScore(
        relevance: Double,
        proximity: Double,
        reputationAvg: BigDecimal,
        freshness: Double,
        response: Double,
        isBoosted: Boolean
    ): Double {
        val reputationNorm = computeReputation(reputationAvg)
        val base = computeBaseScore(
            relevance = relevance.coerceIn(0.0, 1.0),
            proximity = proximity.coerceIn(0.0, 1.0),
            reputation = reputationNorm,
            freshness = freshness.coerceIn(0.0, 1.0),
            response = response.coerceIn(0.0, 1.0)
        )
        return if (isBoosted && reputationAvg >= MIN_REPUTATION_FOR_BOOST) {
            base * BOOST_MULTIPLIER
        } else {
            base
        }
    }

    fun computeBaseScore(
        relevance: Double,
        proximity: Double,
        reputation: Double,
        freshness: Double,
        response: Double
    ): Double =
        WEIGHT_RELEVANCE * relevance.coerceIn(0.0, 1.0) +
            WEIGHT_PROXIMITY * proximity.coerceIn(0.0, 1.0) +
            WEIGHT_REPUTATION * reputation.coerceIn(0.0, 1.0) +
            WEIGHT_FRESHNESS * freshness.coerceIn(0.0, 1.0) +
            WEIGHT_RESPONSE * response.coerceIn(0.0, 1.0)

    fun computeRelevance(
        listing: Listing,
        query: String,
        categoryName: String = ""
    ): Double {
        val normalizedQuery = normalizeText(query)
        if (normalizedQuery.isEmpty()) return 1.0

        val normalizedTitle = normalizeText(listing.title)
        val normalizedDesc = normalizeText(listing.description)
        val normalizedCat = normalizeText(categoryName)

        if (normalizedTitle == normalizedQuery) return 1.0
        if (normalizedTitle.contains(normalizedQuery)) return 0.9

        val tokens = normalizedQuery.split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return 1.0

        val titleHits = tokens.count { normalizedTitle.contains(it) }.toDouble() / tokens.size
        val descHits = tokens.count { normalizedDesc.contains(it) }.toDouble() / tokens.size
        val catHits = tokens.count { normalizedCat.contains(it) }.toDouble() / tokens.size

        val combined = (titleHits * 0.7) + (descHits * 0.25) + (catHits * 0.05)
        return when {
            combined > 0.0 -> combined.coerceIn(0.1, 1.0)
            normalizedDesc.contains(normalizedQuery) -> 0.5
            else -> 0.0
        }
    }

    fun computeProximity(
        listing: Listing,
        targetMunicipalityId: Int?,
        distanceKm: Double? = null
    ): Double {
        if (distanceKm != null) {
            val radius = listing.radiusKm.coerceAtLeast(1).toDouble()
            return when {
                distanceKm <= 0.0 -> 1.0
                distanceKm <= radius -> (1.0 - (distanceKm / (radius * 2.0))).coerceIn(0.5, 1.0)
                else -> (1.0 / (1.0 + (distanceKm - radius) / 20.0)).coerceIn(0.0, 0.5)
            }
        }
        if (targetMunicipalityId == null) return 1.0
        val listingMunicipality = listing.municipalityId ?: return 0.3
        return when {
            listingMunicipality == targetMunicipalityId -> 1.0
            CubanMunicipalities.isSameProvince(listingMunicipality, targetMunicipalityId) -> 0.6
            else -> 0.2
        }
    }

    fun computeReputation(reputationAvg: BigDecimal): Double =
        (reputationAvg.toDouble() / 5.0).coerceIn(0.0, 1.0)

    fun computeFreshness(createdAt: Instant, now: Instant): Double {
        val ageMinutes = Duration.between(createdAt, now).toMinutes().coerceAtLeast(0L)
        val ageHours = ageMinutes / 60.0
        return (1.0 / (1.0 + ageHours / 72.0)).coerceIn(0.0, 1.0)
    }

    private fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input.trim().lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        const val WEIGHT_RELEVANCE = 0.45
        const val WEIGHT_PROXIMITY = 0.25
        const val WEIGHT_REPUTATION = 0.15
        const val WEIGHT_FRESHNESS = 0.10
        const val WEIGHT_RESPONSE = 0.05
        const val BOOST_MULTIPLIER = 1.3
        const val REACH_MULTIPLIER = 1.15
        val MIN_REPUTATION_FOR_BOOST: BigDecimal = BigDecimal("3")
    }
}

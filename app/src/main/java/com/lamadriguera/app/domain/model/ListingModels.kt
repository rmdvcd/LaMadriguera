package com.lamadriguera.app.domain.model

import java.math.BigDecimal

/**
 * Municipio cubano para filtrado, publicación y cálculo de proximidad.
 */
data class CubanMunicipality(
    val id: Int,
    val name: String,
    val province: String
)

/**
 * Catálogo de municipios de referencia para el selector y cálculo de proximidad.
 */
object CubanMunicipalities {

    val all: List<CubanMunicipality> = listOf(
        CubanMunicipality(101, "Plaza de la Revolución", "La Habana"),
        CubanMunicipality(102, "Playa", "La Habana"),
        CubanMunicipality(103, "Centro Habana", "La Habana"),
        CubanMunicipality(104, "La Habana Vieja", "La Habana"),
        CubanMunicipality(105, "Diez de Octubre", "La Habana"),
        CubanMunicipality(106, "Cerro", "La Habana"),
        CubanMunicipality(107, "Marianao", "La Habana"),
        CubanMunicipality(108, "Boyeros", "La Habana"),
        CubanMunicipality(109, "Guanabacoa", "La Habana"),
        CubanMunicipality(110, "La Habana del Este", "La Habana"),
        CubanMunicipality(111, "San Miguel del Padrón", "La Habana"),
        CubanMunicipality(112, "Arroyo Naranjo", "La Habana"),
        CubanMunicipality(113, "Lisa", "La Habana"),
        CubanMunicipality(114, "Cotorro", "La Habana"),
        CubanMunicipality(115, "Regla", "La Habana"),
        CubanMunicipality(201, "Matanzas", "Matanzas"),
        CubanMunicipality(202, "Cárdenas", "Matanzas"),
        CubanMunicipality(301, "Santa Clara", "Villa Clara"),
        CubanMunicipality(401, "Cienfuegos", "Cienfuegos"),
        CubanMunicipality(501, "Camagüey", "Camagüey"),
        CubanMunicipality(601, "Holguín", "Holguín"),
        CubanMunicipality(701, "Santiago de Cuba", "Santiago de Cuba"),
        CubanMunicipality(801, "Pinar del Río", "Pinar del Río")
    )

    fun findById(id: Int?): CubanMunicipality? =
        if (id == null) null else all.firstOrNull { it.id == id }

    fun displayName(id: Int?): String = when {
        id == null -> "Todos los municipios"
        else -> findById(id)?.let { "${it.name} (${it.province})" } ?: "Municipio $id"
    }

    fun isSameProvince(idA: Int, idB: Int): Boolean {
        val munA = findById(idA)
        val munB = findById(idB)
        return if (munA != null && munB != null) {
            munA.province == munB.province
        } else {
            (idA / 100) == (idB / 100)
        }
    }
}

/**
 * Filtros de búsqueda del listado de publicaciones.
 */
data class ListingSearchFilter(
    val query: String = "",
    val municipalityId: Int? = null,
    val categoryId: String? = null,
    val kind: ListingKind? = null,
    val saleMode: SaleMode? = null
)

/**
 * Datos enriquecidos de un listing para calcular su posición en el ranking.
 */
data class ListingCandidate(
    val listing: Listing,
    val ownerReputationAvg: BigDecimal = BigDecimal.ZERO,
    val ownerReviewsCount: Int = 0,
    val ownerDisplayName: String = "",
    val categoryName: String = "",
    /** Tasa de respuesta del anunciante en [0.0, 1.0]. */
    val responseRate: Double = 0.5,
    /** Distancia estimada en km cuando se dispone de ubicación relativa. */
    val distanceKm: Double? = null,
    /** Promociones vigentes del anuncio, usadas para alcance y espacios 1/5. */
    val activePromotions: List<Boost> = emptyList()
)

/**
 * Resultado del algoritmo de ranking con desglose de puntuaciones.
 */
data class RankedListing(
    val listing: Listing,
    val ownerReputationAvg: BigDecimal,
    val ownerReviewsCount: Int = 0,
    val ownerDisplayName: String = "",
    val categoryName: String = "",
    val relevanceScore: Double,
    val proximityScore: Double,
    val reputationScore: Double,
    val freshnessScore: Double,
    val responseScore: Double,
    val baseScore: Double,
    val finalScore: Double,
    /** El multiplicador puede estar limitado por reputación; la etiqueta no. */
    val isBoostApplied: Boolean,
    val isPromoted: Boolean = isBoostApplied,
    val isFeatured: Boolean = false
)

/**
 * Borrador de creación o edición de una publicación (oferta o demanda).
 */
data class ListingDraft(
    val kind: ListingKind = ListingKind.OFFER,
    val title: String = "",
    val description: String = "",
    val categoryId: String = "",
    val price: BigDecimal = BigDecimal.ZERO,
    val currency: String = "CUP",
    val priceNegotiable: Boolean = false,
    val municipalityId: Int? = null,
    val radiusKm: Int = 10,
    val privacyLevel: PrivacyLevel = PrivacyLevel.PUBLIC,
    val visibility: ListingVisibility = ListingVisibility.PUBLIC,
    val photoBytes: ByteArray? = null,
    val existingPhotoUrl: String? = null,
    val allowAuction: Boolean = false,
    val auctionConfig: AuctionConfig? = null
)

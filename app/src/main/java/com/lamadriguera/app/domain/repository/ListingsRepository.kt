package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.AuctionConfig
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.ListingDraft
import com.lamadriguera.app.domain.model.ListingSearchFilter
import com.lamadriguera.app.domain.model.RankedListing

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface ListingsRepository : MutableRepository<Listing>, DeleteRepository<Listing> {

    /**
     * true si el usuario ya publicó alguna vez (aunque la publicación no esté
     * activa). El nivel de privacidad por defecto solo puede cambiar antes de
     * publicar.
     */
    suspend fun hasPublished(ownerId: String): Result<Boolean>

    /**
     * Busca, filtra por municipio + categoría + offer/request y ordena con el
     * algoritmo de ranking (relevancia 45%, proximidad 25%, reputación 15%,
     * frescura 10%, respuesta 5%; boost ×1.3 sin saltar reputación < 3).
     */
    suspend fun searchAndRank(
        filter: ListingSearchFilter = ListingSearchFilter(),
        referenceMunicipalityId: Int? = null
    ): Result<List<RankedListing>>

    /**
     * Crea una publicación (oferta o demanda) comprimiendo y subiendo la foto
     * opcional si existe (`<= 1 MB`) y configurando el modo subasta si procede.
     */
    suspend fun createFromDraft(draft: ListingDraft): Result<Listing>

    /**
     * Actualiza una publicación existente. Si cambia la configuración de
     * subasta, verifica que el usuario sea el dueño y que aún no existan pujas.
     */
    suspend fun updateFromDraft(listingId: String, draft: ListingDraft): Result<Listing>

    /**
     * Actualiza únicamente la configuración de subasta de una publicación,
     * permitido solo al propietario mientras no haya pujas.
     */
    suspend fun updateAuctionConfig(listingId: String, config: AuctionConfig?): Result<Listing>

    /**
     * Comprime en cliente (`<= 1 MB`) y sube una foto de publicación al bucket
     * `listing-photos`, devolviendo su URL pública.
     */
    suspend fun uploadPhoto(bytes: ByteArray): Result<String>
}

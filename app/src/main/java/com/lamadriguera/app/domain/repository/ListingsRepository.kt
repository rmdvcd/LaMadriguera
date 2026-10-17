package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Listing

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface ListingsRepository : MutableRepository<Listing>, DeleteRepository<Listing> {

    /**
     * true si el usuario ya publicó alguna vez (aunque la publicación no esté
     * activa). El nivel de privacidad por defecto solo puede cambiar antes de
     * publicar.
     */
    suspend fun hasPublished(ownerId: String): Result<Boolean>
}

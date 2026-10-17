package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.data.repository.ImageFormat
import com.lamadriguera.app.domain.model.ListingDraft
import com.lamadriguera.app.domain.model.ListingKind
import com.lamadriguera.app.domain.model.ListingVisibility
import java.math.BigDecimal

/**
 * Validaciones del formulario de creación/edición de publicaciones (ofertas y demandas).
 */
object ListingValidator {

    const val MAX_PHOTO_BYTES: Int = 1024 * 1024 // 1 MB
    const val MIN_TITLE_LENGTH: Int = 3
    const val MAX_TITLE_LENGTH: Int = 120
    const val MIN_DESCRIPTION_LENGTH: Int = 5

    const val MSG_TITLE_REQUIRED = "Introduce un título de al menos 3 caracteres"
    const val MSG_TITLE_TOO_LONG = "El título no puede superar los 120 caracteres"
    const val MSG_DESCRIPTION_REQUIRED = "Introduce una descripción detallada"
    const val MSG_CATEGORY_REQUIRED = "Selecciona una categoría"
    const val MSG_PRICE_INVALID = "Introduce un precio válido o marca «A convenir»"
    const val MSG_RADIUS_INVALID = "El radio debe ser mayor o igual a 0 km"
    const val MSG_OFFER_VISIBILITY = "Las ofertas siempre tienen visibilidad pública"
    const val MSG_PHOTO_FORMAT = "La foto debe estar en formato JPEG o PNG"
    const val MSG_PHOTO_TOO_LARGE = "La foto no puede superar 1 MB después de comprimir"
    const val MSG_AUCTION_CONFIG_REQUIRED = "Configura los parámetros de la subasta"

    fun validate(draft: ListingDraft): String? {
        val trimmedTitle = draft.title.trim()
        if (trimmedTitle.length < MIN_TITLE_LENGTH) return MSG_TITLE_REQUIRED
        if (trimmedTitle.length > MAX_TITLE_LENGTH) return MSG_TITLE_TOO_LONG

        val trimmedDesc = draft.description.trim()
        if (trimmedDesc.length < MIN_DESCRIPTION_LENGTH) return MSG_DESCRIPTION_REQUIRED

        if (draft.categoryId.isBlank()) return MSG_CATEGORY_REQUIRED

        if (draft.radiusKm < 0) return MSG_RADIUS_INVALID

        if (draft.kind == ListingKind.OFFER && draft.visibility != ListingVisibility.PUBLIC) {
            return MSG_OFFER_VISIBILITY
        }

        if (!draft.allowAuction) {
            if (draft.price < BigDecimal.ZERO) return MSG_PRICE_INVALID
            if (!draft.priceNegotiable && draft.price <= BigDecimal.ZERO) return MSG_PRICE_INVALID
        } else {
            val config = draft.auctionConfig ?: return MSG_AUCTION_CONFIG_REQUIRED
            AuctionConfigValidator.validate(config)?.let { return it }
        }

        draft.photoBytes?.let { bytes ->
            validatePhoto(bytes)?.let { return it }
        }

        return null
    }

    fun validatePhoto(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return MSG_PHOTO_FORMAT
        if (ImageFormat.detect(bytes) == null) return MSG_PHOTO_FORMAT
        if (bytes.size > MAX_PHOTO_BYTES) return MSG_PHOTO_TOO_LARGE
        return null
    }
}

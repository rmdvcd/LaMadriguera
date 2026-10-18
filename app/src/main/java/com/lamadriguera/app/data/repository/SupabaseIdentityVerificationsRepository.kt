package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.mapper.IdentityDocRefCodec
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.IdentityVerificationRow
import com.lamadriguera.app.domain.model.IdentityDocRef
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.domain.model.IdentityVerificationRequest
import com.lamadriguera.app.domain.model.IdentityVerificationStatus
import com.lamadriguera.app.domain.repository.IdentityVerificationsRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseIdentityVerificationsRepository @Inject constructor(
    private val client: SupabaseClient
) : SupabaseTableRepository<IdentityVerification, IdentityVerificationRow>(
        client = client,
        table = TABLE,
        primaryKey = "id",
        serializer = IdentityVerificationRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf(
            "id", "user_id", "doc_type", "doc_ref", "full_name", "birthdate", "retention_until"
        ),
        updateColumns = emptySet()
    ), IdentityVerificationsRepository {

    override suspend fun latestForUser(userId: String): Result<IdentityVerification?> = request {
        require(userId.isNotBlank()) { "Empty user id" }
        decodeRows(client.from(TABLE).select {
            filter { eq("user_id", userId) }
            order("retention_until", Order.DESCENDING)
            limit(1)
        }.data).singleOrNull()?.toDomain()
    }

    override suspend fun latestApprovedCarnetForUser(userId: String): Result<IdentityVerification?> = request {
        require(userId.isNotBlank()) { "Empty user id" }
        decodeRows(client.from(TABLE).select {
            filter {
                eq("user_id", userId)
                eq("doc_type", IdentityVerificationRequest.DOC_TYPE_CARNET)
                eq("status", APPROVED_VALUE)
            }
            order("verified_at", Order.DESCENDING)
            limit(1)
        }.data).singleOrNull()?.toDomain()
    }

    override suspend fun submit(draft: IdentityVerificationRequest): Result<IdentityVerification> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())
        val front = ImageFormat.detect(draft.frontImage)
            ?: return Result.Error(AppError.Validation("La foto del frente debe ser JPEG o PNG"))
        val back = ImageFormat.detect(draft.backImage)
            ?: return Result.Error(AppError.Validation("La foto del reverso debe ser JPEG o PNG"))
        if (draft.frontImage.size > ImageFormat.MAX_IMAGE_BYTES ||
            draft.backImage.size > ImageFormat.MAX_IMAGE_BYTES
        ) {
            return Result.Error(AppError.Validation("Cada imagen debe pesar menos de 5 MB"))
        }
        val fullName = draft.fullName.trim()
        if (fullName.isEmpty()) {
            return Result.Error(AppError.Validation("Introduce tu nombre completo"))
        }

        val frontPath = "$userId/${UUID.randomUUID()}.${front.extension}"
        val backPath = "$userId/${UUID.randomUUID()}.${back.extension}"
        val bucket = client.storage.from(BUCKET)
        val uploaded = request {
            bucket.upload(frontPath, draft.frontImage) { contentType = ContentType.parse(front.mimeType) }
            bucket.upload(backPath, draft.backImage) { contentType = ContentType.parse(back.mimeType) }
        }
        if (uploaded is Result.Error) return uploaded

        val created = create(
            IdentityVerification(
                id = UUID.randomUUID().toString(),
                userId = userId,
                docType = draft.docType,
                docRef = IdentityDocRefCodec.encode(IdentityDocRef(frontPath, backPath)),
                fullName = fullName,
                birthdate = draft.birthdate,
                status = IdentityVerificationStatus.PENDING,
                retentionUntil = Instant.now().plus(RETENTION_DAYS, ChronoUnit.DAYS)
            )
        )
        if (created is Result.Error) {
            // Si la fila no se pudo crear, se retiran las imágenes subidas.
            runCatching { bucket.delete(frontPath, backPath) }
        }
        return created
    }

    override suspend fun review(id: String, approved: Boolean): Result<IdentityVerification> = request {
        require(id.isNotBlank()) { "Empty primary key" }
        val reviewerId = client.auth.currentUserOrNull()?.id ?: throw AppError.Unauthorized()
        val payload = buildJsonObject {
            put("status", if (approved) APPROVED_VALUE else REJECTED_VALUE)
            put("verified_by", reviewerId)
            put("verified_at", Instant.now().toString())
        }
        decodeRows(client.from(TABLE).update(payload) {
            filter { eq("id", id) }
            select()
        }.data).singleOrNull()?.toDomain() ?: throw AppError.NotFound()
    }

    private fun decodeRows(data: String): List<IdentityVerificationRow> =
        SupabaseJson.decodeFromString(ListSerializer(IdentityVerificationRow.serializer()), data)

    companion object {
        private const val TABLE = "identity_verifications"

        /** Bucket privado de las fotos del carnet (políticas por carpeta <uid>/). */
        const val BUCKET = "identity-docs"

        /** Retención de los documentos: 2 años desde la solicitud. */
        const val RETENTION_DAYS = 730L

        const val APPROVED_VALUE = "approved"
        const val REJECTED_VALUE = "rejected"
    }
}

package com.lamadriguera.app.data.repository

import com.lamadriguera.app.BuildConfig
import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.MonetizationProductRow
import com.lamadriguera.app.data.remote.supabase.row.MonetizationSettingsRow
import com.lamadriguera.app.data.remote.supabase.row.PaymentReviewQueueRow
import com.lamadriguera.app.data.remote.supabase.row.PaymentRow
import com.lamadriguera.app.domain.model.MonetizationProduct
import com.lamadriguera.app.domain.model.MonetizationSettings
import com.lamadriguera.app.domain.model.Payment
import com.lamadriguera.app.domain.model.PaymentReviewItem
import com.lamadriguera.app.domain.repository.PaymentsRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.client.request.contentType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SupabasePaymentsRepository @Inject constructor(
    private val client: SupabaseClient,
    private val httpClient: HttpClient
) : SupabaseTableRepository<Payment, PaymentRow>(
    client = client,
    table = TABLE,
    primaryKey = "id",
    serializer = PaymentRow.serializer(),
    toDomain = { it.toDomain() },
    toRow = { it.toRow() },
    // La creación y toda transición de estado son RPC de servidor, nunca INSERT/UPDATE directo.
    insertColumns = emptySet(),
    updateColumns = emptySet()
), PaymentsRepository {

    override suspend fun listForUser(userId: String): Result<List<Payment>> = request {
        require(userId.isNotBlank()) { "Empty user id" }
        // Hace efectivo el vencimiento aun cuando el proyecto no tenga pg_cron habilitado.
        runCatching { client.postgrest.rpc("expire_my_pending_monetization_payments") }
        decodeRows(
            PaymentRow.serializer(),
            client.from(TABLE).select {
                filter { eq("user_id", userId) }
                order("created_at", Order.DESCENDING)
                range(0, 99)
            }.data
        ).map { it.toDomain() }
    }

    override suspend fun latestForListing(listingId: String): Result<Payment?> = request {
        require(listingId.isNotBlank()) { "Empty listing id" }
        runCatching { client.postgrest.rpc("expire_my_pending_monetization_payments") }
        decodeRows(
            PaymentRow.serializer(),
            client.from(TABLE).select {
                filter { eq("target_id", listingId) }
                order("created_at", Order.DESCENDING)
                range(0, 0)
            }.data
        ).singleOrNull()?.toDomain()
    }

    override suspend fun listProducts(includeInactive: Boolean): Result<List<MonetizationProduct>> = request {
        decodeRows(
            MonetizationProductRow.serializer(),
            client.from(PRODUCTS_TABLE).select {
                if (!includeInactive) filter { eq("is_active", true) }
                order("sort_order", Order.ASCENDING)
            }.data
        ).map { it.toDomain() }
    }

    override suspend fun getSettings(): Result<MonetizationSettings> = request {
        decodeRows(
            MonetizationSettingsRow.serializer(),
            client.from(SETTINGS_TABLE).select { filter { eq("id", 1) } }.data
        ).single().toDomain()
    }

    override suspend fun isPaymentAdmin(): Result<Boolean> = request {
        client.postgrest.rpc("is_payment_reviewer").data.let { raw ->
            SupabaseJson.parseToJsonElement(raw).jsonPrimitive.content.toBooleanStrict()
        }
    }

    override suspend fun createOrder(listingId: String, productCode: String): Result<Payment> {
        if (listingId.isBlank() || productCode.isBlank()) {
            return Result.Error(AppError.Validation("Selecciona una publicación y un producto"))
        }
        return request {
            val data = client.postgrest.rpc(
                "create_monetization_payment",
                buildJsonObject {
                    put("p_listing_id", listingId)
                    put("p_product_code", productCode)
                }
            ).data
            decodeSingle(PaymentRow.serializer(), data).toDomain()
        }
    }

    override suspend fun submitProof(
        paymentId: String,
        imageBytes: ByteArray,
        transferLast4: String,
        transferAt: Instant
    ): Result<Payment> {
        if (paymentId.isBlank()) return Result.Error(AppError.Validation("Pago no válido"))
        if (!transferLast4.matches(Regex("^[0-9]{4}$"))) {
            return Result.Error(AppError.Validation("Escribe exactamente los últimos cuatro dígitos"))
        }
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())
        val accessToken = client.auth.currentAccessTokenOrNull()
            ?: return Result.Error(AppError.Unauthorized())
        val format = ImageFormat.detect(imageBytes)
            ?: return Result.Error(AppError.Validation("La captura debe ser una imagen JPEG o PNG"))
        if (imageBytes.isEmpty() || imageBytes.size > ImageFormat.MAX_IMAGE_BYTES) {
            return Result.Error(AppError.Validation("La captura debe pesar como máximo 5 MB"))
        }

        val path = "$userId/$paymentId/${UUID.randomUUID()}.${format.extension}"
        val bucket = client.storage.from(PROOFS_BUCKET)
        val uploadResult = request {
            bucket.upload(path, imageBytes) {
                contentType = ContentType.parse(format.mimeType)
            }
        }
        if (uploadResult is Result.Error) return uploadResult

        val result = request {
            val response = httpClient.post("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/submit-payment-proof") {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(
                    SubmitPaymentProofRequest(
                        paymentId = paymentId,
                        proofRef = path,
                        transferLast4 = transferLast4,
                        transferAt = transferAt.toString()
                    )
                )
            }
            val responseText = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val message = runCatching {
                    SupabaseJson.decodeFromString(EdgeErrorResponse.serializer(), responseText).error
                }.getOrNull()
                when (response.status.value) {
                    401, 403 -> throw AppError.Unauthorized(message ?: "No se pudo validar la sesión")
                    409 -> throw AppError.Conflict(message ?: "El comprobante ya fue usado")
                    413, 415 -> throw AppError.Validation(message ?: "La captura no es válida")
                    else -> throw AppError.Validation(message ?: "No se pudo enviar el comprobante")
                }
            }
            SupabaseJson.decodeFromString(SubmitPaymentProofResponse.serializer(), responseText)
                .payment.toDomain()
        }
        if (result is Result.Error) {
            // La política Storage solo permite borrar al dueño si la ruta todavía no está asociada.
            runCatching { bucket.delete(path) }
        }
        return result
    }

    override suspend fun reviewQueue(): Result<List<PaymentReviewItem>> = request {
        val data = client.postgrest.rpc("payment_review_queue").data
        decodeRows(PaymentReviewQueueRow.serializer(), data).map { it.toDomain() }
    }

    override suspend fun proofSignedUrl(proofRef: String): Result<String> {
        if (proofRef.isBlank()) return Result.Error(AppError.Validation("No hay captura asociada"))
        return request { client.storage.from(PROOFS_BUCKET).createSignedUrl(proofRef, SIGNED_URL_LIFETIME) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun confirmPayment(
        paymentId: String,
        receivedAmount: BigDecimal,
        transferAt: Instant,
        transferLast4: String,
        reference: String?
    ): Result<Payment> {
        if (paymentId.isBlank() || receivedAmount <= BigDecimal.ZERO) {
            return Result.Error(AppError.Validation("Monto o pago no válido"))
        }
        if (!transferLast4.matches(Regex("^[0-9]{4}$"))) {
            return Result.Error(AppError.Validation("Confirma los últimos cuatro dígitos del extracto"))
        }
        return request {
            val data = client.postgrest.rpc(
                "confirm_monetization_payment",
                buildJsonObject {
                    put("p_payment_id", paymentId)
                    put("p_received_amount", JsonUnquotedLiteral(receivedAmount.toPlainString()))
                    put("p_transfer_at", transferAt.toString())
                    put("p_transfer_last4", transferLast4)
                    put("p_reference", reference?.trim()?.takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull)
                }
            ).data
            decodeSingle(PaymentRow.serializer(), data).toDomain()
        }
    }

    override suspend fun rejectPayment(
        paymentId: String,
        reason: String,
        blacklistReceipt: Boolean
    ): Result<Payment> {
        if (paymentId.isBlank() || reason.trim().isBlank()) {
            return Result.Error(AppError.Validation("Explica el motivo del rechazo"))
        }
        return request {
            val data = client.postgrest.rpc(
                "reject_monetization_payment",
                buildJsonObject {
                    put("p_payment_id", paymentId)
                    put("p_reason", reason.trim())
                    put("p_blacklist_receipt", blacklistReceipt)
                }
            ).data
            decodeSingle(PaymentRow.serializer(), data).toDomain()
        }
    }

    override suspend fun updateSettings(
        transferAccount: String,
        accountHolder: String?
    ): Result<MonetizationSettings> {
        val digits = transferAccount.filter(Char::isDigit)
        if (digits.length !in 8..20) {
            return Result.Error(AppError.Validation("La cuenta debe tener entre 8 y 20 dígitos"))
        }
        return request {
            val data = client.postgrest.rpc(
                "update_monetization_settings",
                buildJsonObject {
                    put("p_transfer_account", digits)
                    put("p_account_holder", accountHolder?.trim()?.takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull)
                }
            ).data
            decodeSingle(MonetizationSettingsRow.serializer(), data).toDomain()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun updateProductPrice(
        code: String,
        amount: BigDecimal,
        isActive: Boolean
    ): Result<MonetizationProduct> {
        if (code.isBlank() || amount <= BigDecimal.ZERO) {
            return Result.Error(AppError.Validation("El precio debe ser mayor que cero"))
        }
        return request {
            val data = client.postgrest.rpc(
                "update_monetization_product_price",
                buildJsonObject {
                    put("p_code", code)
                    put("p_amount", JsonUnquotedLiteral(amount.toPlainString()))
                    put("p_is_active", isActive)
                }
            ).data
            decodeSingle(MonetizationProductRow.serializer(), data).toDomain()
        }
    }

    private fun <Row : Any> decodeRows(serializer: KSerializer<Row>, data: String): List<Row> =
        SupabaseJson.decodeFromString(ListSerializer(serializer), data)

    private fun <Row : Any> decodeSingle(serializer: KSerializer<Row>, data: String): Row {
        val parsed = SupabaseJson.parseToJsonElement(data)
        return if (parsed is JsonArray) {
            SupabaseJson.decodeFromJsonElement(ListSerializer(serializer), parsed).single()
        } else {
            SupabaseJson.decodeFromJsonElement(serializer, parsed)
        }
    }

    @Serializable
    private data class SubmitPaymentProofRequest(
        @kotlinx.serialization.SerialName("payment_id") val paymentId: String,
        @kotlinx.serialization.SerialName("proof_ref") val proofRef: String,
        @kotlinx.serialization.SerialName("transfer_last4") val transferLast4: String,
        @kotlinx.serialization.SerialName("transfer_at") val transferAt: String
    )

    @Serializable
    private data class SubmitPaymentProofResponse(val payment: PaymentRow)

    @Serializable
    private data class EdgeErrorResponse(val error: String? = null)

    companion object {
        private const val TABLE = "payments"
        private const val PRODUCTS_TABLE = "monetization_products"
        private const val SETTINGS_TABLE = "monetization_settings"
        private const val PROOFS_BUCKET = "payment-proofs"
        private val SIGNED_URL_LIFETIME = 5.minutes
    }
}

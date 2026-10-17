package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Order
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Shared PostgREST implementation. Public domain interfaces expose only supported operations. */
abstract class SupabaseTableRepository<Model, Row : Any>(
    private val client: SupabaseClient,
    private val table: String,
    private val primaryKey: String,
    private val serializer: KSerializer<Row>,
    private val toDomain: (Row) -> Model,
    private val toRow: (Model) -> Row,
    private val insertColumns: Set<String>,
    private val updateColumns: Set<String>
) {
    suspend fun list(page: Int, pageSize: Int): Result<List<Model>> = request {
        require(page >= 0 && pageSize in 1..100) { "Invalid pagination" }
        val start = page.toLong() * pageSize
        decode(client.from(table).select {
            order(primaryKey, Order.ASCENDING)
            range(start, start + pageSize - 1)
        }.data).map(toDomain)
    }

    suspend fun find(id: String): Result<Model?> = request {
        require(id.isNotBlank()) { "Empty primary key" }
        decode(client.from(table).select { filter { eq(primaryKey, id) } }.data)
            .singleOrNull()?.let(toDomain)
    }

    suspend fun create(model: Model): Result<Model> = request {
        check(insertColumns.isNotEmpty()) { "Read-only repository" }
        val payload = payload(model, insertColumns)
        decode(client.from(table).insert(payload) { select() }.data).single().let(toDomain)
    }

    suspend fun update(id: String, model: Model): Result<Model> = request {
        require(id.isNotBlank()) { "Empty primary key" }
        require(updateColumns.isNotEmpty()) { "Updates require a trusted backend for this table" }
        val payload = payload(model, updateColumns)
        val rows = decode(client.from(table).update(payload) {
            filter { eq(primaryKey, id) }
            select()
        }.data)
        rows.singleOrNull()?.let(toDomain) ?: throw AppError.NotFound()
    }

    suspend fun delete(id: String): Result<Unit> = request {
        require(id.isNotBlank()) { "Empty primary key" }
        val response = client.from(table).delete {
            filter { eq(primaryKey, id) }
            select()
        }
        if (SupabaseJson.parseToJsonElement(response.data).jsonArray.isEmpty()) throw AppError.NotFound()
    }

    private fun decode(data: String): List<Row> = SupabaseJson.decodeFromString(ListSerializer(serializer), data)

    private fun payload(model: Model, columns: Set<String>): JsonObject = JsonObject(
        SupabaseJson.encodeToJsonElement(serializer, toRow(model)).jsonObject.filterKeys { it in columns }
    )
}

/** Never swallow structured concurrency cancellation or expose raw database/PII error messages. */
internal suspend fun <T> request(block: suspend () -> T): Result<T> = try {
    Result.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: PostgrestRestException) {
    val mapped = when (error.code) {
        "42501", "PGRST301", "PGRST302" -> AppError.Unauthorized()
        "23505" -> AppError.Conflict()
        "23503", "23514", "22P02", "22003" -> AppError.Validation("Datos no válidos")
        else -> when (error.statusCode) {
            401, 403 -> AppError.Unauthorized()
            404 -> AppError.NotFound()
            409 -> AppError.Conflict()
            else -> AppError.Server()
        }
    }
    Result.Error(mapped, code = error.statusCode)
} catch (error: AppError) {
    Result.Error(error)
} catch (error: IllegalArgumentException) {
    Result.Error(AppError.Validation("Datos o paginación no válidos"))
} catch (error: IOException) {
    Result.Error(AppError.Network())
} catch (error: Exception) {
    Result.Error(AppError.Unknown())
}

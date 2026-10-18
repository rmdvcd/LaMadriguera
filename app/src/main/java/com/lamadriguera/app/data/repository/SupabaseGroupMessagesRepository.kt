package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.GroupMessageRow
import com.lamadriguera.app.domain.model.GroupMessage
import com.lamadriguera.app.domain.repository.GroupMessagesRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import javax.inject.Inject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Muro de mensajes de grupos. Lectura por PostgREST (RLS: miembros/grupos
 * públicos) y escritura solo por RPC `post_group_message` (el texto pasa la
 * moderación en cliente antes de publicar).
 */
class SupabaseGroupMessagesRepository @Inject constructor(
    private val client: SupabaseClient
) : GroupMessagesRepository {

    override suspend fun listForGroup(groupId: String, limit: Int): Result<List<GroupMessage>> = request {
        require(groupId.isNotBlank()) { "Grupo no válido" }
        require(limit in 1..200) { "Límite no válido" }
        val data = client.from(TABLE).select {
            filter { eq("group_id", groupId) }
            order("created_at", Order.DESCENDING)
            range(0, limit - 1L)
        }.data
        SupabaseJson.decodeFromString(ListSerializer(GroupMessageRow.serializer()), data).map { it.toDomain() }
    }

    override suspend fun post(groupId: String, body: String): Result<GroupMessage> = request {
        require(groupId.isNotBlank()) { "Grupo no válido" }
        val trimmed = body.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 1000) { "Mensaje no válido" }
        val data = client.postgrest.rpc(
            "post_group_message",
            buildJsonObject {
                put("p_group_id", groupId)
                put("p_body", trimmed)
            }
        ).data
        val element = SupabaseJson.parseToJsonElement(data)
        when (element) {
            is kotlinx.serialization.json.JsonArray ->
                SupabaseJson.decodeFromJsonElement(ListSerializer(GroupMessageRow.serializer()), element)
                .single().toDomain()
            else -> SupabaseJson.decodeFromJsonElement(GroupMessageRow.serializer(), element).toDomain()
        }
    }

    private companion object {
        const val TABLE = "group_messages"
    }
}

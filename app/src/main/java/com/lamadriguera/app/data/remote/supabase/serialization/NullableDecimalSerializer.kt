package com.lamadriguera.app.data.remote.supabase.serialization

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.ExperimentalSerializationApi

/** Nullable numeric serializer that preserves decimal precision and SQL NULL. */
object NullableDecimalSerializer : KSerializer<BigDecimal?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal? = if (decoder is JsonDecoder) {
        val element = decoder.decodeJsonElement()
        if (element == JsonNull) null else element.jsonPrimitive.content.toBigDecimal()
    } else {
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            null
        } else {
            decoder.decodeString().toBigDecimal()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: BigDecimal?) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(value?.let { JsonUnquotedLiteral(it.toPlainString()) } ?: JsonNull)
        } else if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.toPlainString())
        }
    }
}

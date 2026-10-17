package com.lamadriguera.app.data.mapper

/** PostgreSQL/PostgREST bytea hex format (JSON escapes the initial backslash). */
internal fun String.decodeBytea(): ByteArray {
    require(startsWith("\\x")) { "Expected PostgreSQL bytea hex format" }
    val hex = substring(2)
    require(hex.length % 2 == 0) { "Invalid bytea length" }
    return ByteArray(hex.length / 2) { index ->
        val high = hex[index * 2].digitToInt(16)
        val low = hex[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}

internal fun ByteArray.encodeBytea(): String = "\\x" + joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}

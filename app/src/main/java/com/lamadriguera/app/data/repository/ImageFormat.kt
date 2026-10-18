package com.lamadriguera.app.data.repository

/**
 * Detección mínima de formato de imagen por firmas de bytes.
 * Solo se aceptan JPEG/PNG para el carnet y las evidencias de apuestas.
 */
internal object ImageFormat {

    const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

    enum class Format(val extension: String, val mimeType: String) {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png")
    }

    fun detect(bytes: ByteArray): Format? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            Format.JPEG
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() ->
            Format.PNG
        else -> null
    }
}

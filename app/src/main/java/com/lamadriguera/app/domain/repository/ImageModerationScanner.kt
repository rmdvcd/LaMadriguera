package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result

/**
 * Clasificador NSFW ejecutado EN CLIENTE antes de subir imágenes
 * (implementación: NSFW.js en WebView + heurística de respaldo offline).
 * Si el resultado es dudoso, la imagen NO se sube.
 */
interface ImageModerationScanner {
    suspend fun scan(bytes: ByteArray): Result<ImageScanResult>
}

enum class ImageVerdict { SAFE, DUBIOUS, UNSAFE }

data class ImageScanResult(
    val verdict: ImageVerdict,
    /** Scores por categoría (Neutral, Drawing, Sexy, Porn, Hentai u otras). */
    val scores: Map<String, Float> = emptyMap(),
    val engine: String = "unknown"
) {
    /** "Si es dudoso, no se sube": dudoso o inseguro bloquean la subida. */
    val blocksUpload: Boolean get() = verdict != ImageVerdict.SAFE
}

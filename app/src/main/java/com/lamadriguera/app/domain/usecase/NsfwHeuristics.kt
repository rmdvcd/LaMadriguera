package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.domain.repository.ImageScanResult
import com.lamadriguera.app.domain.repository.ImageVerdict

/**
 * Heurística NSFW de respaldo, 100% offline y determinista (sin TensorFlow).
 * Se usa cuando NSFW.js no está disponible (sin modelo configurado, timeout o
 * error del motor). Es deliberadamente conservadora: ante la duda → DUBIOUS,
 * y "si es dudoso, no se sube".
 *
 * Calcula la proporción de píxeles con tono de piel aproximado; una proporción
 * muy alta es típica de contenido erótico/explicito sin ropa.
 */
object NsfwHeuristics {

    /** Fracción de piel que ya se considera dudosa. */
    const val DUBIOUS_SKIN_RATIO = 0.55f

    /** Fracción de piel que se considera insegura. */
    const val UNSAFE_SKIN_RATIO = 0.78f

    /** Mínimo de píxeles para emitir un veredicto (evita ruido en iconos). */
    const val MIN_PIXELS = 64

    /**
     * @param argb píxeles en formato ARGB (una fila tras otra).
     * @param width ancho de la imagen; se usa para estimar bordes y densidad.
     */
    fun verdictFor(argb: IntArray, width: Int, height: Int): ImageScanResult {
        if (argb.isEmpty() || width <= 0 || height <= 0 || argb.size < MIN_PIXELS) {
            // Sin señal suficiente: dudoso (no se sube), nunca "safe" por defecto.
            return ImageScanResult(ImageVerdict.DUBIOUS, mapOf("skinRatio" to 0f), engine = "heuristics")
        }
        var skin = 0
        var opaque = 0
        for (pixel in argb) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 32) continue
            opaque++
            if (isSkinTone(pixel)) skin++
        }
        if (opaque < MIN_PIXELS) {
            return ImageScanResult(ImageVerdict.DUBIOUS, mapOf("skinRatio" to 0f), engine = "heuristics")
        }
        val ratio = skin.toFloat() / opaque
        val verdict = when {
            ratio >= UNSAFE_SKIN_RATIO -> ImageVerdict.UNSAFE
            ratio >= DUBIOUS_SKIN_RATIO -> ImageVerdict.DUBIOUS
            else -> ImageVerdict.SAFE
        }
        return ImageScanResult(
            verdict = verdict,
            scores = mapOf("skinRatio" to ratio),
            engine = "heuristics"
        )
    }

    /** Tono de piel aproximado en espacio YCbCr (acepta amplio rango de pieles). */
    fun isSkinTone(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        // Regla clásica RGB + validación de croma YCbCr.
        val rgbRule = r > 95 && g > 40 && b > 20 &&
            (maxOf(r, g, b) - minOf(r, g, b)) > 15 &&
            kotlin.math.abs(r - g) > 15 && r > g && r > b
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
        val ycbcrRule = y > 40 && cb in 77.0..127.0 && cr in 133.0..173.0
        return rgbRule || ycbcrRule
    }
}

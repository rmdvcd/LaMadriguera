package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.domain.repository.ImageScanResult
import com.lamadriguera.app.domain.repository.ImageVerdict

/**
 * Conversión de las predicciones de NSFW.js (Neutral, Drawing, Sexy, Porn,
 * Hentai) al veredicto de La Madriguera. Umbral bajo: ante la duda, dudoso,
 * y «si es dudoso, no se sube».
 */
object NsfwScoring {
    const val UNSAFE_SCORE = 0.30f
    const val DUBIOUS_SCORE = 0.10f

    fun verdictForScores(scores: Map<String, Float>, engine: String = "nsfwjs"): ImageScanResult {
        val porn = scores["Porn"] ?: 0f
        val hentai = scores["Hentai"] ?: 0f
        val sexy = scores["Sexy"] ?: 0f
        val neutral = scores["Neutral"] ?: 0f
        val drawing = scores["Drawing"] ?: 0f
        val adult = porn + hentai
        val verdict = when {
            adult >= UNSAFE_SCORE -> ImageVerdict.UNSAFE
            sexy >= UNSAFE_SCORE || adult >= DUBIOUS_SCORE || (neutral + drawing) < 0.55f -> ImageVerdict.DUBIOUS
            else -> ImageVerdict.SAFE
        }
        return ImageScanResult(verdict = verdict, scores = scores, engine = engine)
    }
}

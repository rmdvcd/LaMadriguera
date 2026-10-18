package com.lamadriguera.app.domain

import com.lamadriguera.app.domain.repository.ImageVerdict
import com.lamadriguera.app.domain.usecase.NsfwHeuristics
import com.lamadriguera.app.domain.usecase.NsfwScoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwImageScoringTest {

    @Test
    fun explicitNsfwJsScoresBlockUpload() {
        val result = NsfwScoring.verdictForScores(
            mapOf("Porn" to 0.72f, "Neutral" to 0.1f, "Drawing" to 0.05f, "Sexy" to 0.1f, "Hentai" to 0.03f)
        )
        assertEquals(ImageVerdict.UNSAFE, result.verdict)
        assertTrue(result.blocksUpload)
    }

    @Test
    fun doubtfulNsfwJsScoresAlsoBlockUpload() {
        val result = NsfwScoring.verdictForScores(
            mapOf("Sexy" to 0.32f, "Neutral" to 0.5f, "Drawing" to 0.1f, "Porn" to 0.05f, "Hentai" to 0.03f)
        )
        assertEquals(ImageVerdict.DUBIOUS, result.verdict)
        assertTrue(result.blocksUpload)

        val lowAdult = NsfwScoring.verdictForScores(
            mapOf("Porn" to 0.12f, "Neutral" to 0.8f, "Drawing" to 0.05f, "Sexy" to 0.03f, "Hentai" to 0f)
        )
        assertEquals(ImageVerdict.DUBIOUS, lowAdult.verdict)
    }

    @Test
    fun neutralImagesPass() {
        val result = NsfwScoring.verdictForScores(
            mapOf("Neutral" to 0.92f, "Drawing" to 0.05f, "Sexy" to 0.02f, "Porn" to 0.005f, "Hentai" to 0.005f)
        )
        assertEquals(ImageVerdict.SAFE, result.verdict)
        assertFalse(result.blocksUpload)
    }

    @Test
    fun skinHeuristicsAreConservative() {
        val skinPixel = 0xFFE0B48C.toInt()
        val safePixel = 0xFF2E5E4E.toInt()

        val mostlySkin = IntArray(100) { skinPixel }
        assertEquals(ImageVerdict.UNSAFE, NsfwHeuristics.verdictFor(mostlySkin, 10, 10).verdict)

        val mixed = IntArray(100) { if (it < 60) skinPixel else safePixel }
        assertEquals(ImageVerdict.DUBIOUS, NsfwHeuristics.verdictFor(mixed, 10, 10).verdict)

        val clothed = IntArray(100) { if (it < 20) skinPixel else safePixel }
        assertEquals(ImageVerdict.SAFE, NsfwHeuristics.verdictFor(clothed, 10, 10).verdict)

        // Sin señal suficiente: dudoso (no se sube), nunca "safe" por defecto.
        assertEquals(ImageVerdict.DUBIOUS, NsfwHeuristics.verdictFor(IntArray(10), 2, 5).verdict)
    }
}

package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.data.repository.ImageFormat
import com.lamadriguera.app.domain.model.IdentityDocRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityDocRefCodecTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val ref = IdentityDocRef(
            frontPath = "00000000-0000-0000-0000-000000000001/abc-front.jpg",
            backPath = "00000000-0000-0000-0000-000000000001/abc-back.jpg"
        )
        assertEquals(ref, IdentityDocRefCodec.decode(IdentityDocRefCodec.encode(ref)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedReferenceIsRejected() {
        IdentityDocRefCodec.decode("nada".toByteArray())
    }
}

class ImageFormatTest {

    @Test
    fun detectsJpegAndPngBySignature() {
        assertEquals(
            ImageFormat.Format.JPEG,
            ImageFormat.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        )
        assertEquals(
            ImageFormat.Format.PNG,
            ImageFormat.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A))
        )
    }

    @Test
    fun rejectsOtherBytes() {
        assertNull(ImageFormat.detect(byteArrayOf()))
        assertNull(ImageFormat.detect("GIF89a".toByteArray()))
    }
}

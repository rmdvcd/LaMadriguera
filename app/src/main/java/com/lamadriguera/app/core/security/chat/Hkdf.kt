package com.lamadriguera.app.core.security.chat

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869) para derivar claves de cifrado a partir de secretos
 * compartidos X25519. Sin dependencia de APIs nuevas (válido desde minSdk 26).
 */
object Hkdf {

    private const val MAC_ALGORITHM = "HmacSHA256"
    private const val HASH_BYTES = 32

    /**
     * Extract + Expand. [salt] nulo equivale a 32 ceros (RFC 5869 §2.2).
     * [info] debe identificar el propósito (dominio de separación).
     */
    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_BYTES)) { "Longitud HKDF no válida" }
        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }

    private fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(salt ?: ByteArray(HASH_BYTES), MAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, MAC_ALGORITHM))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            previous = mac.doFinal(previous + info + byteArrayOf(counter.toByte()))
            val step = minOf(HASH_BYTES, length - offset)
            System.arraycopy(previous, 0, out, offset, step)
            offset += step
            counter++
        }
        return out
    }
}

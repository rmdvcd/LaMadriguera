package com.lamadriguera.app.core.security.chat

import java.math.BigInteger

/**
 * X25519 (RFC 7748) en Kotlin puro sobre [BigInteger].
 *
 * Se usa para el intercambio de claves del diseño C:
 * - Envolvimiento de la `session_key` de cada conversación hacia la clave
 *   pública de disputa y hacia las claves públicas de los participantes.
 * - Envolvimiento de las mitades de la clave privada de disputa hacia los
 *   custodios (admin general + moderador senior).
 *
 * Notas de seguridad:
 * - [sharedSecret] rechaza entradas de orden pequeño (secreto compartido
 *   todo-ceros), como exige el RFC 7748 §6.1, para evitar ataques de
 *   contribución de clave.
 * - La escalera de Montgomery con [BigInteger] no es de tiempo constante; en
 *   el cliente solo protege datos locales propios. El proceso backend de
 *   disputa maneja secretos más sensibles y debe ejecutarse en un entorno
 *   controlado (Edge Function con aislación de trabajo).
 * - Validado con los vectores conocidos del RFC 7748 (ver ChatCryptoTest).
 */
object X25519 {

    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val P_MINUS_2: BigInteger = P.subtract(BigInteger.TWO)
    private val A24: BigInteger = BigInteger.valueOf(121665)

    /** Punto base u = 9. */
    private val BASE_POINT = ByteArray(KEY_BYTES).apply { this[0] = 9 }

    const val KEY_BYTES = 32

    /** Clave pública X25519 correspondiente a una clave privada de 32 bytes. */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_BYTES) { "La clave privada X25519 debe tener 32 bytes" }
        return scalarMult(privateKey, BASE_POINT)
    }

    /**
     * Secreto compartido Diffie-Hellman con la clave pública del peer.
     * @throws IllegalArgumentException si la clave pública es inválida o de orden pequeño.
     */
    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_BYTES) { "La clave privada X25519 debe tener 32 bytes" }
        require(peerPublicKey.size == KEY_BYTES) { "La clave pública X25519 debe tener 32 bytes" }
        require(peerPublicKey.any { it != 0.toByte() }) { "Clave pública de orden pequeño" }
        val shared = scalarMult(privateKey, peerPublicKey)
        require(shared.any { it != 0.toByte() }) { "Secreto compartido de orden pequeño" }
        return shared
    }

    /** Escalera de Montgomery del RFC 7748 §5. Entradas y salida little-endian de 32 bytes. */
    private fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        val k = decodeScalar(scalar)
        val x1 = decodeU(u).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                val tmpX = x2; x2 = x3; x3 = tmpX
                val tmpZ = z2; z2 = z3; z3 = tmpZ
            }
            swap = kt

            val a = x2.add(z2)
            val aa = a.multiply(a)
            val b = x2.subtract(z2)
            val bb = b.multiply(b)
            val e = aa.subtract(bb)
            val c = x3.add(z3)
            val d = x3.subtract(z3)
            val da = d.multiply(a)
            val cb = c.multiply(b)
            val daPlusCb = da.add(cb).mod(P)
            val daMinusCb = da.subtract(cb)

            x3 = daPlusCb.multiply(daPlusCb).mod(P)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) {
            val tmpX = x2; x2 = x3; x3 = tmpX
            val tmpZ = z2; z2 = z3; z3 = tmpZ
        }

        return encode(x2.multiply(z2.modPow(P_MINUS_2, P)).mod(P))
    }

    /** Clamping del escalar RFC 7748 §5: bits 0-2 a 0, bit 255 a 0, bit 254 a 1. */
    private fun decodeScalar(scalar: ByteArray): BigInteger {
        val k = scalar.copyOf(KEY_BYTES)
        k[0] = (k[0].toInt() and 248).toByte()
        k[KEY_BYTES - 1] = (k[KEY_BYTES - 1].toInt() and 127).toByte()
        k[KEY_BYTES - 1] = (k[KEY_BYTES - 1].toInt() or 64).toByte()
        return decodeLittleEndian(k)
    }

    /** Coordenada u: se ignora el bit más significativo (RFC 7748 §5). */
    private fun decodeU(u: ByteArray): BigInteger {
        val v = u.copyOf(KEY_BYTES)
        v[KEY_BYTES - 1] = (v[KEY_BYTES - 1].toInt() and 127).toByte()
        return decodeLittleEndian(v)
    }

    private fun decodeLittleEndian(bytes: ByteArray): BigInteger =
        BigInteger(1, bytes.reversedArray())

    private fun encode(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray()
        val trimmed = when {
            bigEndian.size > KEY_BYTES -> bigEndian.copyOfRange(bigEndian.size - KEY_BYTES, bigEndian.size)
            else -> bigEndian
        }
        val out = ByteArray(KEY_BYTES)
        for (i in trimmed.indices) {
            out[i] = trimmed[trimmed.size - 1 - i]
        }
        return out
    }
}

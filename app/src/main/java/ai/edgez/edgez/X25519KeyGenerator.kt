package ai.edgez.edgez

import java.math.BigInteger
import java.security.SecureRandom

private val CURVE25519_P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
private val CURVE25519_A24 = BigInteger.valueOf(121665)
private val CURVE25519_BASE_U = BigInteger.valueOf(9)

object X25519KeyGenerator {
    private val secureRandom = SecureRandom()

    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val privateKey = ByteArray(32)
        secureRandom.nextBytes(privateKey)
        clamp(privateKey)
        return privateKey to publicKey(privateKey)
    }

    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        val scalar = privateKey.copyOf()
        clamp(scalar)
        return scalarMult(scalar, CURVE25519_BASE_U)
    }

    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(peerPublicKey.size == 32) { "X25519 peer public key must be 32 bytes" }
        val scalar = privateKey.copyOf()
        clamp(scalar)
        return scalarMult(scalar, peerPublicKey.toLittleEndianBigInteger())
    }

    private fun clamp(key: ByteArray) {
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = ((key[31].toInt() and 127) or 64).toByte()
    }

    private fun scalarMult(scalar: ByteArray, pointU: BigInteger): ByteArray {
        val x1 = pointU.mod(CURVE25519_P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = bitAt(scalar, t)
            swap = swap xor kt
            if (swap == 1) {
                val oldX2 = x2
                x2 = x3
                x3 = oldX2
                val oldZ2 = z2
                z2 = z3
                z3 = oldZ2
            }
            swap = kt

            val a = x2.add(z2).modP()
            val aa = a.multiply(a).modP()
            val b = x2.subtract(z2).modP()
            val bb = b.multiply(b).modP()
            val e = aa.subtract(bb).modP()
            val c = x3.add(z3).modP()
            val d = x3.subtract(z3).modP()
            val da = d.multiply(a).modP()
            val cb = c.multiply(b).modP()
            x3 = da.add(cb).modP().squareModP()
            z3 = x1.multiply(da.subtract(cb).modP().squareModP()).modP()
            x2 = aa.multiply(bb).modP()
            z2 = e.multiply(aa.add(CURVE25519_A24.multiply(e)).modP()).modP()
        }

        if (swap == 1) {
            val oldX2 = x2
            x2 = x3
            x3 = oldX2
            val oldZ2 = z2
            z2 = z3
            z3 = oldZ2
        }

        return x2.multiply(z2.modInverse(CURVE25519_P)).modP().toLittleEndian32()
    }

    private fun bitAt(bytes: ByteArray, bitIndex: Int): Int {
        return (bytes[bitIndex / 8].toInt() ushr (bitIndex and 7)) and 1
    }

    private fun BigInteger.modP(): BigInteger = mod(CURVE25519_P)

    private fun BigInteger.squareModP(): BigInteger = multiply(this).modP()

    private fun BigInteger.toLittleEndian32(): ByteArray {
        val bigEndian = toByteArray()
        val out = ByteArray(32)
        var sourceIndex = bigEndian.lastIndex
        var outIndex = 0
        while (sourceIndex >= 0 && outIndex < out.size) {
            out[outIndex++] = bigEndian[sourceIndex--]
        }
        return out
    }

    private fun ByteArray.toLittleEndianBigInteger(): BigInteger {
        val bigEndian = ByteArray(size)
        for (i in indices) {
            bigEndian[lastIndex - i] = this[i]
        }
        return BigInteger(1, bigEndian)
    }
}

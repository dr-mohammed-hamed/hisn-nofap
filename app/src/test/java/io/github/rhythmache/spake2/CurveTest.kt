package io.github.rhythmache.spake2

import java.math.BigInteger
import java.security.MessageDigest
import org.junit.Test

class CurveTest {
    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "")
        val result = ByteArray(cleanHex.length / 2)
        for (i in result.indices) {
            result[i] = cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun testBasePointEncoding() {
        val basePoint = PointExt.fromAffine(Curve.Bx, Curve.By)
        val encoded = basePoint.encode()
        // RFC 8032: Base point B encoded in hex is 5866666666666666666666666666666666666666666666666666666666666666
        val expectedHex = "5866666666666666666666666666666666666666666666666666666666666666"
        assertEquals(expectedHex, bytesToHex(encoded))
    }

    @Test
    fun testBasePointOrderLMultiplication() {
        val basePoint = PointExt.fromAffine(Curve.Bx, Curve.By)
        // L * B must equal IDENTITY (0, 1)
        val result = basePoint.scalarMul(Curve.L)
        val (ax, ay) = result.toAffine()
        assertEquals(BigInteger.ZERO, ax)
        assertEquals(BigInteger.ONE, ay)
    }

    @Test
    fun testPointAdditionEqualsScalarTwo() {
        val basePoint = PointExt.fromAffine(Curve.Bx, Curve.By)
        val p2a = basePoint.add(basePoint)
        val p2b = basePoint.scalarMul(BigInteger.valueOf(2))

        val encA = bytesToHex(p2a.encode())
        val encB = bytesToHex(p2b.encode())
        assertEquals(encA, encB)
    }

    @Test
    fun testRfc8032TestVector1PublicKeyDerivation() {
        // RFC 8032 Test Vector 1 secret key
        val secretKey = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val md = MessageDigest.getInstance("SHA-512")
        val digest = md.digest(secretKey)

        // Clamp scalar a per RFC 8032 Section 5.1.5
        val clamped = digest.copyOfRange(0, 32)
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = (clamped[31].toInt() and 127).toByte()
        clamped[31] = (clamped[31].toInt() or 64).toByte()

        // Convert little-endian bytes to BigInteger
        val a = BigInteger(1, clamped.reversedArray())

        val basePoint = PointExt.fromAffine(Curve.Bx, Curve.By)
        val pubPoint = basePoint.scalarMul(a)
        val pubBytes = pubPoint.encode()

        // Expected RFC 8032 Test Vector 1 public key
        val expectedPubHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        assertEquals(expectedPubHex, bytesToHex(pubBytes))
    }

    @Test
    fun testPointDecodeEncodeRoundtrip() {
        val basePoint = PointExt.fromAffine(Curve.Bx, Curve.By)
        val encoded = basePoint.encode()
        val decoded = PointExt.decode(encoded)
        val reencoded = decoded.encode()

        assertEquals(bytesToHex(encoded), bytesToHex(reencoded))
    }
}

package io.github.rhythmache.spake2

import io.github.rhythmache.spake2.internal.field.GeCached
import io.github.rhythmache.spake2.internal.field.GeOps
import io.github.rhythmache.spake2.internal.field.GeP1P1
import io.github.rhythmache.spake2.internal.field.GeP2
import io.github.rhythmache.spake2.internal.field.GeP3
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.junit.Test

@Suppress("ktlint:standard:property-naming")
class GeOpsTest {
    private val random = SecureRandom()
    private val basePoint = PointExt.fromAffine(Curve.Bx, Curve.By)

    private fun pointToGeP3(p: PointExt): GeP3 =
        GeP3(
            X = FieldInterop.bigIntegerToFe(p.x),
            Y = FieldInterop.bigIntegerToFe(p.y),
            Z = FieldInterop.bigIntegerToFe(p.z),
            T = FieldInterop.bigIntegerToFe(p.t),
        )

    private fun geP3ToAffine(p: GeP3): Pair<BigInteger, BigInteger> {
        val x = FieldInterop.feToBigInteger(p.X)
        val y = FieldInterop.feToBigInteger(p.Y)
        val z = FieldInterop.feToBigInteger(p.Z)
        val zInv = Field.inv(z)
        val ax = Field.mul(x, zInv)
        val ay = Field.mul(y, zInv)
        return Pair(ax, ay)
    }

    private fun geP2ToAffine(p: GeP2): Pair<BigInteger, BigInteger> {
        val x = FieldInterop.feToBigInteger(p.X)
        val y = FieldInterop.feToBigInteger(p.Y)
        val z = FieldInterop.feToBigInteger(p.Z)
        val zInv = Field.inv(z)
        val ax = Field.mul(x, zInv)
        val ay = Field.mul(y, zInv)
        return Pair(ax, ay)
    }

    private fun randomScalar(): BigInteger {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return BigInteger(1, bytes.reversedArray()).mod(Curve.L)
    }

    private fun bigIntegerToScalarBytes(b: BigInteger): ByteArray {
        val mag = b.mod(BigInteger.valueOf(2L).pow(256)).toByteArray()
        val bytes = ByteArray(32)
        val startInMag = if (mag.size > 32) mag.size - 32 else 0
        val lenToCopy = minOf(mag.size, 32)
        val destStart = 32 - lenToCopy
        System.arraycopy(mag, startInMag, bytes, destStart, lenToCopy)
        return bytes.reversedArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "")
        val result = ByteArray(cleanHex.length / 2)
        for (i in result.indices) {
            result[i] = cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // -----------------------------------------------------------------------
    // Constant Verification
    // -----------------------------------------------------------------------
    @Test
    fun testD2ConstantValue() {
        val expectedD2 = Field.mul(BigInteger.valueOf(2), Curve.D)
        val actualD2 = FieldInterop.feToBigInteger(GeOps.D2)
        assertEquals(expectedD2, actualD2, "D2 constant mismatch")
    }

    // -----------------------------------------------------------------------
    // Scalar Multiplication (geScalarmult) Cross-Check against PointExt.scalarMul
    // -----------------------------------------------------------------------
    @Test
    fun testGeScalarmultCrossCheck500() {
        val p3Base = pointToGeP3(basePoint)
        val actualP2 = GeP2()

        for (i in 0 until 500) {
            val k = randomScalar()
            val scalarBytes = bigIntegerToScalarBytes(k)
            val expectedPt = basePoint.scalarMul(k)

            GeOps.geScalarmult(actualP2, scalarBytes, p3Base)

            val (actualAx, actualAy) = geP2ToAffine(actualP2)
            val (expectedAx, expectedAy) = expectedPt.toAffine()

            assertEquals(expectedAx, actualAx, "geScalarmult X affine mismatch at #$i")
            assertEquals(expectedAy, actualAy, "geScalarmult Y affine mismatch at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Scalar Multiplication by Base Point Order L (L * B == IDENTITY)
    // -----------------------------------------------------------------------
    @Test
    fun testGeScalarmultBasePointOrderL() {
        val p3Base = pointToGeP3(basePoint)
        val actualP2 = GeP2()
        val lBytes = bigIntegerToScalarBytes(Curve.L)

        GeOps.geScalarmult(actualP2, lBytes, p3Base)

        val (actualAx, actualAy) = geP2ToAffine(actualP2)
        assertEquals(BigInteger.ZERO, actualAx, "L * B X should be 0")
        assertEquals(BigInteger.ONE, actualAy, "L * B Y should be 1")
    }

    // -----------------------------------------------------------------------
    // RFC 8032 Test Vector 1 Public Key Derivation via geScalarmult & geToBytes
    // -----------------------------------------------------------------------
    @Test
    fun testRfc8032TestVector1PublicKeyDerivation() {
        val secretKey = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val md = MessageDigest.getInstance("SHA-512")
        val digest = md.digest(secretKey)

        val clamped = digest.copyOfRange(0, 32)
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = (clamped[31].toInt() and 127).toByte()
        clamped[31] = (clamped[31].toInt() or 64).toByte()

        val p3Base = pointToGeP3(basePoint)
        val gePubP2 = GeP2()
        GeOps.geScalarmult(gePubP2, clamped, p3Base)

        val pubBytes = ByteArray(32)
        GeOps.geToBytes(pubBytes, gePubP2)

        val expectedPubHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        assertEquals(expectedPubHex, bytesToHex(pubBytes), "RFC 8032 Test Vector 1 public key mismatch")
    }

    // -----------------------------------------------------------------------
    // Point Serialization: geToBytes & geP3ToBytes vs PointExt.encode()
    // -----------------------------------------------------------------------
    @Test
    fun testGeToBytesCrossCheck1k() {
        val actualP2Bytes = ByteArray(32)
        val actualP3Bytes = ByteArray(32)

        for (i in 0 until 1000) {
            val k = randomScalar()
            val pt = basePoint.scalarMul(k)
            val expectedBytes = pt.encode()

            val p3 = pointToGeP3(pt)
            val p2 = GeP2()
            System.arraycopy(p3.X.v, 0, p2.X.v, 0, 5)
            System.arraycopy(p3.Y.v, 0, p2.Y.v, 0, 5)
            System.arraycopy(p3.Z.v, 0, p2.Z.v, 0, 5)

            GeOps.geToBytes(actualP2Bytes, p2)
            GeOps.geP3ToBytes(actualP3Bytes, p3)

            assertContentEquals(expectedBytes, actualP2Bytes, "geToBytes P2 mismatch at #$i")
            assertContentEquals(expectedBytes, actualP3Bytes, "geP3ToBytes P3 mismatch at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Point Deserialization: geFromBytesVartime vs PointExt.decode()
    // -----------------------------------------------------------------------
    @Test
    fun testGeFromBytesVartimeCrossCheck1k() {
        val decodedP3 = GeP3()

        for (i in 0 until 1000) {
            val k = randomScalar()
            val pt = basePoint.scalarMul(k)
            val encodedBytes = pt.encode()

            val success = GeOps.geFromBytesVartime(decodedP3, encodedBytes)
            assertTrue(success, "geFromBytesVartime failed for valid point at #$i")

            val (actualAx, actualAy) = geP3ToAffine(decodedP3)
            val (expectedAx, expectedAy) = pt.toAffine()

            assertEquals(expectedAx, actualAx, "geFromBytesVartime X affine mismatch at #$i")
            assertEquals(expectedAy, actualAy, "geFromBytesVartime Y affine mismatch at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Roundtrip Serialization: bytes -> geFromBytesVartime -> geP3ToBytes -> bytes
    // -----------------------------------------------------------------------
    @Test
    fun testPointRoundtripSerialization() {
        val decodedP3 = GeP3()
        val reencodedBytes = ByteArray(32)

        for (i in 0 until 1000) {
            val k = randomScalar()
            val pt = basePoint.scalarMul(k)
            val originalBytes = pt.encode()

            val success = GeOps.geFromBytesVartime(decodedP3, originalBytes)
            assertTrue(success, "geFromBytesVartime failed at #$i")

            GeOps.geP3ToBytes(reencodedBytes, decodedP3)
            assertContentEquals(originalBytes, reencodedBytes, "Roundtrip bytes mismatch at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Invalid Point Rejection
    // -----------------------------------------------------------------------
    @Test
    fun testInvalidPointRejection() {
        val p3 = GeP3()
        val invalidBytes = ByteArray(32).also { it[0] = 2 }

        val success = GeOps.geFromBytesVartime(p3, invalidBytes)
        assertFalse(success, "geFromBytesVartime should reject invalid point with y=2")
    }

    // -----------------------------------------------------------------------
    // RFC 8032 Base Point Serialization Vector
    // -----------------------------------------------------------------------
    @Test
    fun testRfc8032BasePointEncoding() {
        val p3Base = pointToGeP3(basePoint)
        val encodedBytes = ByteArray(32)
        GeOps.geP3ToBytes(encodedBytes, p3Base)

        val expectedHex = "5866666666666666666666666666666666666666666666666666666666666666"
        assertEquals(expectedHex, bytesToHex(encodedBytes), "RFC 8032 base point encoding mismatch")
    }

    // -----------------------------------------------------------------------
    // Conversion Consistency: GeP1P1 -> GeP2 vs GeP1P1 -> GeP3
    // -----------------------------------------------------------------------
    @Test
    fun testGeP1P1ToP2AndP3Consistency() {
        for (i in 0 until 100) {
            val k = randomScalar()
            val pt = basePoint.scalarMul(k)
            val p3 = pointToGeP3(pt)
            val cached = GeCached()
            GeOps.geP3ToCached(cached, p3)

            val p1p1 = GeP1P1()
            GeOps.geAdd(p1p1, p3, cached)

            val p2 = GeP2()
            val p3Res = GeP3()
            GeOps.geP1P1ToP2(p2, p1p1)
            GeOps.geP1P1ToP3(p3Res, p1p1)

            val affineP2 = geP2ToAffine(p2)
            val affineP3 = geP3ToAffine(p3Res)

            assertEquals(affineP2.first, affineP3.first, "X affine mismatch between P2 and P3 at #$i")
            assertEquals(affineP2.second, affineP3.second, "Y affine mismatch between P2 and P3 at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // 2,000 Random Point Operations Cross-Check
    // -----------------------------------------------------------------------
    @Test
    fun testGeAddAndSubRandomCrossCheck2k() {
        val p1p1 = GeP1P1()
        val p3Res = GeP3()

        for (i in 0 until 2000) {
            val k1 = randomScalar()
            val k2 = randomScalar()
            val pt1 = basePoint.scalarMul(k1)
            val pt2 = basePoint.scalarMul(k2)

            val p3_1 = pointToGeP3(pt1)
            val p3_2 = pointToGeP3(pt2)
            val cached2 = GeCached()
            GeOps.geP3ToCached(cached2, p3_2)

            GeOps.geAdd(p1p1, p3_1, cached2)
            GeOps.geP1P1ToP3(p3Res, p1p1)
            val actualAdd = geP3ToAffine(p3Res)
            val expectedAdd = pt1.add(pt2).toAffine()
            assertEquals(expectedAdd.first, actualAdd.first, "geAdd X mismatch at #$i")
            assertEquals(expectedAdd.second, actualAdd.second, "geAdd Y mismatch at #$i")

            GeOps.geSub(p1p1, p3_1, cached2)
            GeOps.geP1P1ToP3(p3Res, p1p1)
            val actualSub = geP3ToAffine(p3Res)
            val expectedSub = pt1.sub(pt2).toAffine()
            assertEquals(expectedSub.first, actualSub.first, "geSub X mismatch at #$i")
            assertEquals(expectedSub.second, actualSub.second, "geSub Y mismatch at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Group Axiom: Identity Addition & Subtraction (P + 0 = P, P - 0 = P)
    // -----------------------------------------------------------------------
    @Test
    fun testGroupAxiomIdentity() {
        val identityPt = PointExt.IDENTITY
        val p3Id = pointToGeP3(identityPt)
        val cachedId = GeCached()
        GeOps.geP3ToCached(cachedId, p3Id)

        val p1p1 = GeP1P1()
        val p3Res = GeP3()

        for (i in 0 until 500) {
            val k = randomScalar()
            val pt = basePoint.scalarMul(k)
            val p3 = pointToGeP3(pt)

            GeOps.geAdd(p1p1, p3, cachedId)
            GeOps.geP1P1ToP3(p3Res, p1p1)
            val (addX, addY) = geP3ToAffine(p3Res)
            val (expectedX, expectedY) = pt.toAffine()
            assertEquals(expectedX, addX, "P + 0 X mismatch at #$i")
            assertEquals(expectedY, addY, "P + 0 Y mismatch at #$i")

            GeOps.geSub(p1p1, p3, cachedId)
            GeOps.geP1P1ToP3(p3Res, p1p1)
            val (subX, subY) = geP3ToAffine(p3Res)
            assertEquals(expectedX, subX, "P - 0 X mismatch at #$i")
            assertEquals(expectedY, subY, "P - 0 Y mismatch at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Group Axiom: Self Subtraction (P - P = IDENTITY = (0, 1))
    // -----------------------------------------------------------------------
    @Test
    fun testGroupAxiomSelfSubtraction() {
        val p1p1 = GeP1P1()
        val p3Res = GeP3()
        val cached = GeCached()

        for (i in 0 until 500) {
            val k = randomScalar()
            val pt = basePoint.scalarMul(k)
            val p3 = pointToGeP3(pt)
            GeOps.geP3ToCached(cached, p3)

            GeOps.geSub(p1p1, p3, cached)
            GeOps.geP1P1ToP3(p3Res, p1p1)
            val (actualX, actualY) = geP3ToAffine(p3Res)

            assertEquals(BigInteger.ZERO, actualX, "P - P X is not 0 at #$i")
            assertEquals(BigInteger.ONE, actualY, "P - P Y is not 1 at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Base Point Sequential Scalar Addition Stress Test (B, 2B, 3B, ..., 1000B)
    // -----------------------------------------------------------------------
    @Test
    fun testBasePointSequentialAddition1k() {
        val p3Base = pointToGeP3(basePoint)
        val cachedBase = GeCached()
        GeOps.geP3ToCached(cachedBase, p3Base)

        val p1p1 = GeP1P1()
        val p3Cur = pointToGeP3(basePoint)

        for (n in 2..1000) {
            val expectedNext = basePoint.scalarMul(BigInteger.valueOf(n.toLong()))

            GeOps.geAdd(p1p1, p3Cur, cachedBase)
            GeOps.geP1P1ToP3(p3Cur, p1p1)

            val (actualX, actualY) = geP3ToAffine(p3Cur)
            val (expectedX, expectedY) = expectedNext.toAffine()

            assertEquals(expectedX, actualX, "Base point addition X mismatch at n=$n")
            assertEquals(expectedY, actualY, "Base point addition Y mismatch at n=$n")
        }
    }
}

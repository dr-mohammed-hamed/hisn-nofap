package io.github.rhythmache.spake2

import io.github.rhythmache.spake2.internal.field.Fe
import io.github.rhythmache.spake2.internal.field.FeLoose
import io.github.rhythmache.spake2.internal.field.FieldOps
import java.math.BigInteger
import java.security.SecureRandom
import org.junit.Test

@Suppress("ktlint:standard:property-naming")
class FieldOpsTest {
    private val random = SecureRandom()
    private val P = Field.P
    private val ZERO = BigInteger.ZERO
    private val ONE = BigInteger.ONE

    // -----------------------------------------------------------------------
    // Helper: generate a random field element as BigInteger in [0, p)
    // -----------------------------------------------------------------------
    private fun randomFieldBI(): BigInteger {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return BigInteger(1, bytes.reversedArray()).mod(P)
    }

    // -----------------------------------------------------------------------
    // Phase B: Constants & Copy
    // -----------------------------------------------------------------------
    @Test
    fun testPhaseBConstantsAndCopy() {
        val f0 = Fe()
        FieldOps.fe0(f0)
        assertEquals(ZERO, FieldInterop.feToBigInteger(f0))

        val f1 = Fe()
        FieldOps.fe1(f1)
        assertEquals(ONE, FieldInterop.feToBigInteger(f1))

        val copy = Fe()
        FieldOps.feCopy(copy, f1)
        assertEquals(ONE, FieldInterop.feToBigInteger(copy))
    }

    // -----------------------------------------------------------------------
    // Phase C: Add, Sub, Carry — 10k random cross-checks
    // -----------------------------------------------------------------------
    @Test
    fun testPhaseCAddSubCarryCrossCheck() {
        val loose = FeLoose()
        val feRes = Fe()

        for (i in 0 until 10_000) {
            val biA = randomFieldBI()
            val biB = randomFieldBI()
            val a = FieldInterop.bigIntegerToFe(biA)
            val b = FieldInterop.bigIntegerToFe(biB)

            FieldOps.feAdd(loose, a, b)
            FieldOps.feCarry(feRes, loose)
            assertEquals(Field.add(biA, biB), FieldInterop.feToBigInteger(feRes), "Add mismatch #$i")

            FieldOps.feSub(loose, a, b)
            FieldOps.feCarry(feRes, loose)
            assertEquals(Field.sub(biA, biB), FieldInterop.feToBigInteger(feRes), "Sub mismatch #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Phase D: Serialization — roundtrip + byte agreement, 10k random
    // -----------------------------------------------------------------------
    @Test
    fun testPhaseDSerializationRoundtrip() {
        val bytes = ByteArray(32)
        val outBytes = ByteArray(32)
        val fe = Fe()

        for (i in 0 until 10_000) {
            random.nextBytes(bytes)
            bytes[31] = (bytes[31].toInt() and 0x7F).toByte()

            FieldOps.feFromBytes(fe, bytes)
            FieldOps.feToBytes(outBytes, fe)

            val expectedBI = BigInteger(1, bytes.reversedArray()).mod(P)
            val actualBI = BigInteger(1, outBytes.reversedArray())
            assertEquals(expectedBI, actualBI, "Serialization roundtrip mismatch #$i")

            val expectedBytes = FieldInterop.bigIntegerToBytes(expectedBI)
            assertContentEquals(expectedBytes, outBytes, "Byte agreement mismatch #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Phase D: Serialization — boundary values
    // -----------------------------------------------------------------------
    @Test
    fun testPhaseDSerializationBoundaryValues() {
        val outBytes = ByteArray(32)
        val fe = Fe()

        // 0
        val zero = ByteArray(32)
        FieldOps.feFromBytes(fe, zero)
        FieldOps.feToBytes(outBytes, fe)
        assertContentEquals(zero, outBytes, "zero roundtrip")

        // 1
        val oneBytes = ByteArray(32).also { it[0] = 1 }
        FieldOps.feFromBytes(fe, oneBytes)
        FieldOps.feToBytes(outBytes, fe)
        assertContentEquals(oneBytes, outBytes, "one roundtrip")

        // p - 1 = 2^255 - 20
        val pMinus1 = P - ONE
        val pMinus1Fe = FieldInterop.bigIntegerToFe(pMinus1)
        FieldOps.feToBytes(outBytes, pMinus1Fe)
        val gotBI = BigInteger(1, outBytes.reversedArray())
        assertEquals(pMinus1, gotBI, "p-1 encoding")

        // p (should reduce to 0)
        val pFe = FieldInterop.bigIntegerToFe(P)
        FieldOps.feToBytes(outBytes, pFe)
        assertEquals(ZERO, BigInteger(1, outBytes.reversedArray()), "p should reduce to 0")

        // 2^255 - 1 (all bits set in 255-bit range, not canonical, reduces to 18)
        val max255 = BigInteger.valueOf(2L).pow(255) - ONE
        val max255Fe = FieldInterop.bigIntegerToFe(max255)
        FieldOps.feToBytes(outBytes, max255Fe)
        assertEquals(max255.mod(P), BigInteger(1, outBytes.reversedArray()), "2^255-1 encoding")

        // all 0xFF bytes with top bit cleared → 2^255 - 1
        val allOnes = ByteArray(32) { 0xFF.toByte() }
        allOnes[31] = 0x7F
        FieldOps.feFromBytes(fe, allOnes)
        FieldOps.feToBytes(outBytes, fe)
        val allOnesBI = BigInteger(1, allOnes.reversedArray()).mod(P)
        assertEquals(allOnesBI, BigInteger(1, outBytes.reversedArray()), "all-0xFF encoding")

        // p + 1 (should reduce to 1)
        val pPlus1Fe = FieldInterop.bigIntegerToFe(P + ONE)
        FieldOps.feToBytes(outBytes, pPlus1Fe)
        assertEquals(ONE, BigInteger(1, outBytes.reversedArray()), "p+1 should reduce to 1")
    }

    // -----------------------------------------------------------------------
    // Phase E: Multiplication — 100k random cross-checks against BigInteger
    // -----------------------------------------------------------------------
    @Test
    fun testPhaseEMulCrossCheck100k() {
        val feRes = Fe()

        for (i in 0 until 100_000) {
            val biA = randomFieldBI()
            val biB = randomFieldBI()
            val a = FieldInterop.bigIntegerToFe(biA)
            val b = FieldInterop.bigIntegerToFe(biB)

            FieldOps.feMul(feRes, a, b)
            assertEquals(Field.mul(biA, biB), FieldInterop.feToBigInteger(feRes), "Mul mismatch #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Phase E: Multiplication — boundary values
    // -----------------------------------------------------------------------
    @Test
    fun testPhaseEMulBoundaryValues() {
        val feRes = Fe()

        val boundaryValues =
            listOf(
                ZERO,
                ONE,
                BigInteger.valueOf(2L),
                BigInteger.valueOf(19),
                P - ONE, // p - 1
                P - BigInteger.valueOf(19), // p - 19
                BigInteger.valueOf(2L).pow(51) - ONE, // max single limb
                BigInteger.valueOf(2L).pow(51), // limb boundary
                BigInteger.valueOf(2L).pow(102), // two-limb boundary
                BigInteger.valueOf(2L).pow(204), // four-limb boundary
                BigInteger.valueOf(2L).pow(254), // near top
            )

        for ((i, biA) in boundaryValues.withIndex()) {
            for ((j, biB) in boundaryValues.withIndex()) {
                val a = FieldInterop.bigIntegerToFe(biA.mod(P))
                val b = FieldInterop.bigIntegerToFe(biB.mod(P))

                FieldOps.feMul(feRes, a, b)
                val expected = Field.mul(biA.mod(P), biB.mod(P))
                assertEquals(
                    expected,
                    FieldInterop.feToBigInteger(feRes),
                    "Mul boundary mismatch: values[$i] * values[$j]",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Phase F: Squaring — 100k random cross-checks
    // -----------------------------------------------------------------------
    @Test
    fun testPhaseFSquareCrossCheck100k() {
        val feRes = Fe()

        for (i in 0 until 100_000) {
            val biA = randomFieldBI()
            val a = FieldInterop.bigIntegerToFe(biA)

            FieldOps.feSquare(feRes, a)
            assertEquals(Field.sq(biA), FieldInterop.feToBigInteger(feRes), "Square mismatch #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Phase F: Square vs Mul consistency — square(a) == mul(a, a)
    // -----------------------------------------------------------------------
    @Test
    fun testPhaseFSquareVsMul() {
        val feSq = Fe()
        val feMul = Fe()

        for (i in 0 until 10_000) {
            val biA = randomFieldBI()
            val a = FieldInterop.bigIntegerToFe(biA)

            FieldOps.feSquare(feSq, a)
            FieldOps.feMul(feMul, a, a)

            val sqBytes = ByteArray(32)
            val mulBytes = ByteArray(32)
            FieldOps.feToBytes(sqBytes, feSq)
            FieldOps.feToBytes(mulBytes, feMul)

            assertContentEquals(sqBytes, mulBytes, "square(a) != mul(a,a) at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Phase G: Inversion — 1k random cross-checks against BigInteger
    // -----------------------------------------------------------------------
    @Test
    fun testPhaseGInvertCrossCheck() {
        val feRes = Fe()

        for (i in 0 until 1_000) {
            val biA = randomFieldBI()
            if (biA == ZERO) continue
            val a = FieldInterop.bigIntegerToFe(biA)

            FieldOps.feInvert(feRes, a)
            assertEquals(Field.inv(biA), FieldInterop.feToBigInteger(feRes), "Invert mismatch #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Algebraic Identity: (a * b) * inv(b) == a  (for nonzero b)
    // -----------------------------------------------------------------------
    @Test
    fun testAlgebraicMulInvertRoundtrip() {
        val feProd = Fe()
        val feInvB = Fe()
        val feResult = Fe()

        for (i in 0 until 500) {
            val biA = randomFieldBI()
            var biB = randomFieldBI()
            if (biB == ZERO) biB = ONE

            val a = FieldInterop.bigIntegerToFe(biA)
            val b = FieldInterop.bigIntegerToFe(biB)

            FieldOps.feMul(feProd, a, b)
            FieldOps.feInvert(feInvB, b)
            FieldOps.feMul(feResult, feProd, feInvB)

            assertEquals(
                biA,
                FieldInterop.feToBigInteger(feResult),
                "(a*b)*inv(b) != a at #$i",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Algebraic Identity: inv(inv(a)) == a  (for nonzero a)
    // -----------------------------------------------------------------------
    @Test
    fun testAlgebraicDoubleInvert() {
        val feInv1 = Fe()
        val feInv2 = Fe()

        for (i in 0 until 500) {
            val biA = randomFieldBI()
            if (biA == ZERO) continue
            val a = FieldInterop.bigIntegerToFe(biA)

            FieldOps.feInvert(feInv1, a)
            FieldOps.feInvert(feInv2, feInv1)

            assertEquals(
                biA,
                FieldInterop.feToBigInteger(feInv2),
                "inv(inv(a)) != a at #$i",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Algebraic Identity: a * inv(a) == 1  (for nonzero a)
    // -----------------------------------------------------------------------
    @Test
    fun testAlgebraicMulInvertIsOne() {
        val feInv = Fe()
        val feProd = Fe()

        for (i in 0 until 1_000) {
            val biA = randomFieldBI()
            if (biA == ZERO) continue
            val a = FieldInterop.bigIntegerToFe(biA)

            FieldOps.feInvert(feInv, a)
            FieldOps.feMul(feProd, a, feInv)

            assertEquals(
                ONE,
                FieldInterop.feToBigInteger(feProd),
                "a * inv(a) != 1 at #$i",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Algebraic Identity: (a + b) - b == a
    // -----------------------------------------------------------------------
    @Test
    fun testAlgebraicAddSubRoundtrip() {
        val loose = FeLoose()
        val feSum = Fe()
        val feResult = Fe()

        for (i in 0 until 10_000) {
            val biA = randomFieldBI()
            val biB = randomFieldBI()
            val a = FieldInterop.bigIntegerToFe(biA)
            val b = FieldInterop.bigIntegerToFe(biB)

            // (a + b) - b == a
            FieldOps.feAdd(loose, a, b)
            FieldOps.feCarry(feSum, loose)
            FieldOps.feSub(loose, feSum, b)
            FieldOps.feCarry(feResult, loose)

            assertEquals(
                biA,
                FieldInterop.feToBigInteger(feResult),
                "(a+b)-b != a at #$i",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Algebraic Identity: a * 1 == a, a * 0 == 0
    // -----------------------------------------------------------------------
    @Test
    fun testAlgebraicMulIdentityAndZero() {
        val feRes = Fe()
        val feOne = Fe()
        val feZero = Fe()
        FieldOps.fe1(feOne)
        FieldOps.fe0(feZero)

        for (i in 0 until 10_000) {
            val biA = randomFieldBI()
            val a = FieldInterop.bigIntegerToFe(biA)

            FieldOps.feMul(feRes, a, feOne)
            assertEquals(biA, FieldInterop.feToBigInteger(feRes), "a*1 != a at #$i")

            FieldOps.feMul(feRes, a, feZero)
            assertEquals(ZERO, FieldInterop.feToBigInteger(feRes), "a*0 != 0 at #$i")
        }
    }

    // -----------------------------------------------------------------------
    // Carry propagation stress: repeated addition to push limbs high
    // -----------------------------------------------------------------------
    @Test
    fun testCarryPropagationStress() {
        val loose = FeLoose()
        val feAcc = Fe()
        val feOne = Fe()
        FieldOps.fe1(feOne)
        FieldOps.fe0(feAcc)

        // Add 1 many times, verify result = n mod p
        val n = 100_000
        for (i in 0 until n) {
            FieldOps.feAdd(loose, feAcc, feOne)
            FieldOps.feCarry(feAcc, loose)
        }
        val expected = BigInteger.valueOf(n.toLong()).mod(P)
        assertEquals(
            expected,
            FieldInterop.feToBigInteger(feAcc),
            "Adding 1 $n times should equal $n",
        )
    }

    // -----------------------------------------------------------------------
    // Chained squaring stress: square(square(...square(a)...))
    // -----------------------------------------------------------------------
    @Test
    fun testChainedSquaringStress() {
        val biA = randomFieldBI()
        val fe = FieldInterop.bigIntegerToFe(biA)
        var biExpected = biA

        for (i in 0 until 1_000) {
            FieldOps.feSquare(fe, fe)
            biExpected = Field.sq(biExpected)
        }

        assertEquals(
            biExpected,
            FieldInterop.feToBigInteger(fe),
            "1000 chained squarings diverged",
        )
    }

    // -----------------------------------------------------------------------
    // Chained multiplication stress: a * a * a * ...
    // -----------------------------------------------------------------------
    @Test
    fun testChainedMulStress() {
        val biA = randomFieldBI()
        val feBase = FieldInterop.bigIntegerToFe(biA)
        val feAcc = Fe()
        FieldOps.feCopy(feAcc, feBase)
        var biExpected = biA

        for (i in 0 until 1_000) {
            FieldOps.feMul(feAcc, feAcc, feBase)
            biExpected = Field.mul(biExpected, biA)
        }

        assertEquals(
            biExpected,
            FieldInterop.feToBigInteger(feAcc),
            "1000 chained multiplications diverged",
        )
    }

    // -----------------------------------------------------------------------
    // RFC 8032 Test Vector 1: Ed25519 base point encoding uses field ops
    // The base point y-coordinate is 4/5 mod p
    // -----------------------------------------------------------------------
    @Test
    fun testRfc8032BasePointYCoordinate() {
        // Ed25519 base point y = 4 * inv(5) mod p
        val four = BigInteger.valueOf(4)
        val five = BigInteger.valueOf(5)
        val expectedY = Field.mul(four, Field.inv(five))

        val feFour = FieldInterop.bigIntegerToFe(four)
        val feFive = FieldInterop.bigIntegerToFe(five)
        val feInv5 = Fe()
        val feY = Fe()

        FieldOps.feInvert(feInv5, feFive)
        FieldOps.feMul(feY, feFour, feInv5)

        assertEquals(
            expectedY,
            FieldInterop.feToBigInteger(feY),
            "4/5 mod p mismatch for Ed25519 base point y",
        )

        // Verify the encoding matches the known little-endian encoding
        val yBytes = ByteArray(32)
        FieldOps.feToBytes(yBytes, feY)
        // y = 4/5 mod p should encode to: 6666...6658 (the Ed25519 base point y)
        val yHex = yBytes.joinToString("") { "%02x".format(it) }
        assertEquals(
            "5866666666666666666666666666666666666666666666666666666666666666",
            yHex,
            "Ed25519 base point y encoding mismatch",
        )
    }
}

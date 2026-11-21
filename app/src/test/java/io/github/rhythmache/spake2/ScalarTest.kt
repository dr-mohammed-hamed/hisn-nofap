package io.github.rhythmache.spake2

import io.github.rhythmache.spake2.internal.field.ScalarOps
import java.math.BigInteger
import kotlin.random.Random
import org.junit.Test

class ScalarTest {
    @Test
    fun testScReduceStaysBelowOrder() {
        val random = Random(1234)
        val out32 = ByteArray(32)
        repeat(20) {
            val bytes = ByteArray(64) { random.nextBytes(1)[0] }
            ScalarOps.scReduce(out32, bytes)
            val reduced = BigInteger(1, out32.reversedArray())
            assertTrue(reduced < ScalarOps.L)
            assertTrue(reduced.signum() >= 0)
        }
    }

    @Test
    fun testScalarMul8MultipliesByEight() {
        val sBytes = FieldInterop.bigIntegerToBytes(BigInteger.valueOf(12345))
        val out32 = ByteArray(32)
        ScalarOps.scalarMul8(out32, sBytes)
        val got = BigInteger(1, out32.reversedArray())
        assertEquals(BigInteger.valueOf(12345 * 8), got)
    }

    @Test
    fun testCofactorHackClearsLowThreeBits() {
        val random = Random(5678)
        val reduced32 = ByteArray(32)
        val fixed32 = ByteArray(32)
        repeat(50) {
            val bytes = ByteArray(64) { random.nextBytes(1)[0] }
            ScalarOps.scReduce(reduced32, bytes)
            ScalarOps.applyPasswordScalarCofactorHack(fixed32, reduced32)
            val fixed = BigInteger(1, fixed32.reversedArray())
            assertEquals(BigInteger.ZERO, fixed.and(BigInteger.valueOf(7)))
        }
    }

    @Test
    fun testCofactorHackIsDeterministic() {
        val sBytes = FieldInterop.bigIntegerToBytes(BigInteger.valueOf(999999999999L).mod(ScalarOps.L))
        val a32 = ByteArray(32)
        val b32 = ByteArray(32)
        ScalarOps.applyPasswordScalarCofactorHack(a32, sBytes)
        ScalarOps.applyPasswordScalarCofactorHack(b32, sBytes)
        assertContentEquals(a32, b32)
    }
}

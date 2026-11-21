package io.github.rhythmache.spake2

import io.github.rhythmache.spake2.internal.Spake2Constants
import io.github.rhythmache.spake2.internal.field.GeOps
import io.github.rhythmache.spake2.internal.field.GeP3
import org.junit.Test

class Spake2ConstantsTest {
    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun testNPointDecodesAndRoundtrips() {
        val p3 = GeP3()
        assertTrue(GeOps.geFromBytesVartime(p3, Spake2Constants.N_ENCODED))
        val reencoded = ByteArray(32)
        GeOps.geP3ToBytes(reencoded, p3)
        assertEquals(bytesToHex(Spake2Constants.N_ENCODED), bytesToHex(reencoded))
    }

    @Test
    fun testMPointDecodesAndRoundtrips() {
        val p3 = GeP3()
        assertTrue(GeOps.geFromBytesVartime(p3, Spake2Constants.M_ENCODED))
        val reencoded = ByteArray(32)
        GeOps.geP3ToBytes(reencoded, p3)
        assertEquals(bytesToHex(Spake2Constants.M_ENCODED), bytesToHex(reencoded))
    }

    @Test
    fun testMAndNAreDistinctPoints() {
        val p3M = GeP3()
        val p3N = GeP3()
        assertTrue(GeOps.geFromBytesVartime(p3M, Spake2Constants.M_ENCODED))
        assertTrue(GeOps.geFromBytesVartime(p3N, Spake2Constants.N_ENCODED))

        val bytesM = ByteArray(32)
        val bytesN = ByteArray(32)
        GeOps.geP3ToBytes(bytesM, p3M)
        GeOps.geP3ToBytes(bytesN, p3N)
        assertEquals(false, bytesToHex(bytesM) == bytesToHex(bytesN))
    }
}

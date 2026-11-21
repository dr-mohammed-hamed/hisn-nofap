package io.github.rhythmache.spake2

import io.github.rhythmache.spake2.internal.field.Fe
import io.github.rhythmache.spake2.internal.field.FieldOps
import java.math.BigInteger

internal object FieldInterop {
    fun feToBigInteger(f: Fe): BigInteger {
        val bytes = ByteArray(32)
        FieldOps.feToBytes(bytes, f)
        return BigInteger(1, bytes.reversedArray()).mod(Field.P)
    }

    fun bigIntegerToFe(b: BigInteger): Fe {
        val reduced = b.mod(Field.P)
        val mag = reduced.toByteArray()
        val bytes = ByteArray(32)
        val startInMag = if (mag.size > 32) mag.size - 32 else 0
        val lenToCopy = minOf(mag.size, 32)
        val destStart = 32 - lenToCopy
        System.arraycopy(mag, startInMag, bytes, destStart, lenToCopy)

        val leBytes = bytes.reversedArray()
        val fe = Fe()
        FieldOps.feFromBytes(fe, leBytes)
        return fe
    }

    fun bigIntegerToBytes(b: BigInteger): ByteArray {
        val fe = bigIntegerToFe(b)
        val bytes = ByteArray(32)
        FieldOps.feToBytes(bytes, fe)
        return bytes
    }
}

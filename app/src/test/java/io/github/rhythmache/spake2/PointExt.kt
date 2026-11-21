package io.github.rhythmache.spake2

import java.math.BigInteger
import java.math.BigInteger.ONE
import java.math.BigInteger.ZERO

// Test Oracle PointExt helper for Ed25519
internal data class PointExt(
    val x: BigInteger,
    val y: BigInteger,
    val z: BigInteger,
    val t: BigInteger,
) {
    companion object {
        val IDENTITY = PointExt(ZERO, ONE, ONE, ZERO)

        fun fromAffine(
            x: BigInteger,
            y: BigInteger,
        ): PointExt = PointExt(x, y, ONE, Field.mul(x, y))

        fun decode(bytes: ByteArray): PointExt {
            require(bytes.size == 32) { "Point must be 32 bytes" }
            val signBit = (bytes[31].toInt() ushr 7) and 1
            val yLe = bytes.copyOf()
            yLe[31] = (yLe[31].toInt() and 0x7F).toByte()
            val y = BigInteger(1, yLe.reversedArray())
            val x = Curve.recoverX(y, signBit)
            return fromAffine(x, y)
        }
    }

    fun toAffine(): Pair<BigInteger, BigInteger> {
        val zInv = Field.inv(z)
        return Pair(Field.mul(x, zInv), Field.mul(y, zInv))
    }

    fun add(other: PointExt): PointExt {
        val a = Field.mul(Field.sub(y, x), Field.sub(other.y, other.x))
        val b = Field.mul(Field.add(y, x), Field.add(other.y, other.x))
        val c = Field.mul(Field.mul(t, Field.mul(BigInteger.valueOf(2L), Curve.D)), other.t)
        val dd = Field.mul(Field.mul(z, BigInteger.valueOf(2L)), other.z)
        val e = Field.sub(b, a)
        val f = Field.sub(dd, c)
        val g = Field.add(dd, c)
        val h = Field.add(b, a)
        return PointExt(
            x = Field.mul(e, f),
            y = Field.mul(g, h),
            z = Field.mul(f, g),
            t = Field.mul(e, h),
        )
    }

    fun negate(): PointExt = PointExt(Field.sub(ZERO, x), y, z, Field.sub(ZERO, t))

    fun sub(other: PointExt): PointExt = add(other.negate())

    fun scalarMul(scalar: BigInteger): PointExt {
        var result = IDENTITY
        var addend = this
        var k = scalar
        while (k.signum() > 0) {
            if (k.testBit(0)) result = result.add(addend)
            addend = addend.add(addend)
            k = k.shiftRight(1)
        }
        return result
    }

    fun encode(): ByteArray {
        val (ax, ay) = toAffine()
        val yBytes =
            ay.toByteArray().let { be ->
                val out = ByteArray(32)
                val src = if (be.size > 32) be.copyOfRange(be.size - 32, be.size) else be
                System.arraycopy(src, 0, out, 32 - src.size, src.size)
                out
            }
        val le = yBytes.reversedArray()
        if (ax.testBit(0)) {
            le[31] = (le[31].toInt() or 0x80).toByte()
        }
        return le
    }
}

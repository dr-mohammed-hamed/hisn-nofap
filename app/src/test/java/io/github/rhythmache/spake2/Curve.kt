package io.github.rhythmache.spake2

import java.math.BigInteger
import java.math.BigInteger.ONE
import java.math.BigInteger.ZERO

// Test Oracle Curve helper for Ed25519
internal object Curve {
    val P = Field.P

    val D: BigInteger =
        Field.reduce(
            BigInteger.valueOf(-121665).mod(P) * Field.inv(BigInteger.valueOf(121666)),
        )

    val L: BigInteger = BigInteger.valueOf(2L).pow(252) + BigInteger("27742317777372353535851937790883648493")

    val By: BigInteger =
        Field.reduce(
            BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
        )
    val Bx: BigInteger = recoverX(By, signBit = 0)

    fun recoverX(
        y: BigInteger,
        signBit: Int,
    ): BigInteger {
        val yy = Field.sq(y)
        val u = Field.sub(yy, ONE)
        val v = Field.add(Field.mul(D, yy), ONE)
        val vInv = Field.inv(v)
        val xx = Field.mul(u, vInv)
        var x = Field.pow(xx, P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)))

        if (Field.reduce(Field.sq(x) - xx) != ZERO) {
            val sqrtM1 = Field.pow(BigInteger.valueOf(2L), P.subtract(ONE).divide(BigInteger.valueOf(4)))
            x = Field.mul(x, sqrtM1)
        }
        if (Field.reduce(Field.sq(x) - xx) != ZERO) {
            throw IllegalArgumentException("Invalid point: no square root exists")
        }

        if (x.testBit(0) != (signBit == 1)) {
            x = Field.sub(ZERO, x)
        }
        return x
    }
}

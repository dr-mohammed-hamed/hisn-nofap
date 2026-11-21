package io.github.rhythmache.spake2

import java.math.BigInteger

// Reference Test Oracle: GF(2^255 - 19) using BigInteger
internal object Field {
    val P: BigInteger = BigInteger.valueOf(2L).pow(255) - BigInteger.valueOf(19)

    fun reduce(x: BigInteger): BigInteger {
        var r = x.mod(P)
        if (r.signum() < 0) r = r.add(P)
        return r
    }

    fun add(
        a: BigInteger,
        b: BigInteger,
    ): BigInteger = reduce(a + b)

    fun sub(
        a: BigInteger,
        b: BigInteger,
    ): BigInteger = reduce(a - b)

    fun mul(
        a: BigInteger,
        b: BigInteger,
    ): BigInteger = reduce(a * b)

    fun sq(a: BigInteger): BigInteger = reduce(a * a)

    fun inv(a: BigInteger): BigInteger = a.modInverse(P)

    fun pow(
        a: BigInteger,
        e: BigInteger,
    ): BigInteger = a.modPow(e, P)
}

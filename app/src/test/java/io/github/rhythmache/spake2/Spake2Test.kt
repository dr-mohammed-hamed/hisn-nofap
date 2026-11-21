package io.github.rhythmache.spake2

import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.junit.Test

class Spake2Test {
    private val aliceName = "alice".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    private val bobName = "bob".toByteArray(Charsets.UTF_8) + byteArrayOf(0)

    @Test
    fun testSpake2KeyExchange() {
        val password = "password123".toByteArray(Charsets.UTF_8)
        val alice = Spake2Ctx(Spake2Role.ALICE, aliceName, bobName)
        val bob = Spake2Ctx(Spake2Role.BOB, bobName, aliceName)

        val aliceMsg = alice.generateMessage(password)
        val bobMsg = bob.generateMessage(password)

        assertNotNull(aliceMsg)
        assertNotNull(bobMsg)

        val aliceSecret = alice.processMessage(bobMsg)
        val bobSecret = bob.processMessage(aliceMsg)

        assertNotNull(aliceSecret)
        assertNotNull(bobSecret)

        assertContentEquals(aliceSecret, bobSecret, "Alice and Bob derived secrets must be identical")
    }

    @Test
    fun testSpake2KeyExchangeMismatchPassword() {
        val passwordAlice = "password123".toByteArray(Charsets.UTF_8)
        val passwordBob = "wrongpassword".toByteArray(Charsets.UTF_8)

        val alice = Spake2Ctx(Spake2Role.ALICE, aliceName, bobName)
        val bob = Spake2Ctx(Spake2Role.BOB, bobName, aliceName)

        val aliceMsg = alice.generateMessage(passwordAlice)
        val bobMsg = bob.generateMessage(passwordBob)

        val aliceSecret = alice.processMessage(bobMsg)
        val bobSecret = bob.processMessage(aliceMsg)

        assertFalse(aliceSecret.contentEquals(bobSecret), "Derived secrets must not match on wrong password")
    }

    @Test
    fun testConcurrentSpake2KeyExchange() {
        val numThreads = 16
        val executor = Executors.newFixedThreadPool(numThreads)
        val futures = mutableListOf<Future<*>>()

        for (i in 0 until 50) {
            futures.add(
                executor.submit {
                    val password = "password_$i".toByteArray(Charsets.UTF_8)
                    val alice = Spake2Ctx(Spake2Role.ALICE, aliceName, bobName)
                    val bob = Spake2Ctx(Spake2Role.BOB, bobName, aliceName)

                    val aliceMsg = alice.generateMessage(password)
                    val bobMsg = bob.generateMessage(password)

                    val aliceSecret = alice.processMessage(bobMsg)
                    val bobSecret = bob.processMessage(aliceMsg)

                    assertContentEquals(aliceSecret, bobSecret, "Concurrent secret mismatch at iteration $i")
                },
            )
        }

        for (f in futures) {
            f.get()
        }
        executor.shutdown()
    }
}

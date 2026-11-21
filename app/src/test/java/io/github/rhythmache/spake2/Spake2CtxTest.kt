package io.github.rhythmache.spake2

import org.junit.Test

class Spake2CtxTest {
    private val aliceName = "alice".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    private val bobName = "bob".toByteArray(Charsets.UTF_8) + byteArrayOf(0)

    @Test
    fun testAliceAndBobDeriveSameKeyWithMatchingPassword() {
        val password = "123456".toByteArray(Charsets.UTF_8)

        val alice =
            Spake2Ctx(
                role = Spake2Role.ALICE,
                myName = aliceName,
                theirName = bobName,
            )
        val bob =
            Spake2Ctx(
                role = Spake2Role.BOB,
                myName = bobName,
                theirName = aliceName,
            )

        val aliceMsg = alice.generateMessage(password)
        val bobMsg = bob.generateMessage(password)

        assertEquals(32, aliceMsg.size)
        assertEquals(32, bobMsg.size)

        val aliceKey = alice.processMessage(bobMsg)
        val bobKey = bob.processMessage(aliceMsg)

        assertEquals(64, aliceKey.size)
        assertEquals(64, bobKey.size)
        assertContentEquals(aliceKey, bobKey)
    }

    @Test
    fun testMismatchedPasswordProducesDifferentKeys() {
        val alice =
            Spake2Ctx(
                role = Spake2Role.ALICE,
                myName = aliceName,
                theirName = bobName,
            )
        val bob =
            Spake2Ctx(
                role = Spake2Role.BOB,
                myName = bobName,
                theirName = aliceName,
            )

        val aliceMsg = alice.generateMessage("111111".toByteArray())
        val bobMsg = bob.generateMessage("222222".toByteArray())

        val aliceKey = alice.processMessage(bobMsg)
        val bobKey = bob.processMessage(aliceMsg)

        assert(!aliceKey.contentEquals(bobKey))
    }

    @Test
    fun testCannotGenerateMessageTwice() {
        val ctx =
            Spake2Ctx(
                role = Spake2Role.ALICE,
                myName = aliceName,
                theirName = bobName,
            )
        ctx.generateMessage("123456".toByteArray())
        assertFailsWith<IllegalStateException> {
            ctx.generateMessage("123456".toByteArray())
        }
    }

    @Test
    fun testCannotProcessMessageBeforeGenerating() {
        val ctx =
            Spake2Ctx(
                role = Spake2Role.ALICE,
                myName = aliceName,
                theirName = bobName,
            )
        assertFailsWith<IllegalStateException> {
            ctx.processMessage(ByteArray(32))
        }
    }
}

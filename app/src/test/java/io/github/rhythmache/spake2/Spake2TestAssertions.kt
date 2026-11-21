package io.github.rhythmache.spake2

fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
    org.junit.Assert.assertEquals(message, expected, actual)
}

fun assertTrue(actual: Boolean, message: String? = null) {
    org.junit.Assert.assertTrue(message ?: "", actual)
}

fun assertFalse(actual: Boolean, message: String? = null) {
    org.junit.Assert.assertFalse(message ?: "", actual)
}

fun assertNotNull(actual: Any?, message: String? = null) {
    org.junit.Assert.assertNotNull(message ?: "", actual)
}

fun assertContentEquals(expected: ByteArray?, actual: ByteArray?, message: String? = null) {
    org.junit.Assert.assertArrayEquals(message, expected, actual)
}

fun assertContentEquals(expected: IntArray?, actual: IntArray?, message: String? = null) {
    org.junit.Assert.assertArrayEquals(message, expected, actual)
}

fun assertContentEquals(expected: LongArray?, actual: LongArray?, message: String? = null) {
    org.junit.Assert.assertArrayEquals(message, expected, actual)
}

inline fun <reified T : Throwable> assertFailsWith(noinline block: () -> Unit) {
    org.junit.Assert.assertThrows(T::class.java) { block() }
}

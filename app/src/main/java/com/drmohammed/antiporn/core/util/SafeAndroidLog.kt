package com.drmohammed.antiporn.core.util

/**
 * JVM-test-safe wrapper around [android.util.Log] that preserves full Debug/Release logging
 * on Android while silently ignoring unmocked framework stubs in pure JVM unit tests.
 */
internal object SafeAndroidLog {

    fun d(tag: String, msg: String): Int = runCatching { android.util.Log.d(tag, msg) }.getOrDefault(0)

    fun i(tag: String, msg: String): Int = runCatching { android.util.Log.i(tag, msg) }.getOrDefault(0)

    fun w(tag: String, msg: String): Int = runCatching { android.util.Log.w(tag, msg) }.getOrDefault(0)

    fun w(tag: String, msg: String, tr: Throwable?): Int =
        runCatching { if (tr != null) android.util.Log.w(tag, msg, tr) else android.util.Log.w(tag, msg) }.getOrDefault(0)

    fun e(tag: String, msg: String): Int = runCatching { android.util.Log.e(tag, msg) }.getOrDefault(0)

    fun e(tag: String, msg: String, tr: Throwable?): Int =
        runCatching { if (tr != null) android.util.Log.e(tag, msg, tr) else android.util.Log.e(tag, msg) }.getOrDefault(0)
}

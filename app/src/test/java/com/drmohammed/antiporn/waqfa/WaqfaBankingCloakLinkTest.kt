package com.drmohammed.antiporn.waqfa

import android.content.Context
import android.content.ContextWrapper
import com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.banking.BankingCloakManager
import com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher
import com.drmohammed.antiporn.waqfa.engine.WatcherScheduler
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WaqfaBankingCloakLinkTest {

    private lateinit var dummyContext: DummyWatcherContext
    private lateinit var testScheduler: TestWatcherScheduler
    private lateinit var testBus: ForegroundAppEventBus
    private var testConfig = WaqfaConfig(isEnabled = false, targetPackages = emptySet())

    private class TestWatcherScheduler : WatcherScheduler {
        val postedRunnables = mutableListOf<Runnable>()
        val delayedRunnables = mutableListOf<Pair<Runnable, Long>>()
        var removedCount = 0

        override fun post(runnable: Runnable) {
            postedRunnables.add(runnable)
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            delayedRunnables.add(runnable to delayMs)
        }

        override fun removeCallbacks(runnable: Runnable) {
            postedRunnables.removeAll { it == runnable }
            delayedRunnables.removeAll { it.first == runnable }
            removedCount++
        }
    }

    private class DummyWatcherContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.drmohammed.antiporn"
        override fun getSystemService(name: String): Any? = null
    }

    @Before
    fun setUp() {
        dummyContext = DummyWatcherContext()
        testScheduler = TestWatcherScheduler()
        testBus = ForegroundAppEventBus(dummyContext)
        ForegroundAppEventBus.setInstance(testBus)

        testConfig = WaqfaConfig(isEnabled = false, targetPackages = emptySet())
        AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = true)
    }

    @After
    fun tearDown() {
        AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = true)
        ForegroundAppEventBus.setInstance(null)
        WaqfaForegroundWatcher.setInstance(null)
    }

    private fun createWatcher(config: WaqfaConfig = testConfig): WaqfaForegroundWatcher {
        return WaqfaForegroundWatcher(
            context = dummyContext,
            checkIntervalMs = 400L,
            injectedUsageStatsManager = null,
            injectedEventBus = testBus,
            injectedScheduler = testScheduler,
            sessionManagerProvider = null,
            configProvider = { config }
        )
    }

    @Test
    fun `test foreground change when cloaked restores protection if app switched to browser`() {
        val watcher = createWatcher()

        // 1. تفعيل وضع التخفي البنكي
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(BankingCloakManager.isCloaked)

        // 2. رصد مغادرة تطبيق البنك والتوجه إلى متصفح Chrome
        watcher.onForegroundPackageDetected("com.android.chrome")

        // 3. التحقق من استعادة الحماية تلقائياً وإلغاء وضع التخفي
        assertEquals("com.android.chrome", watcher.lastForegroundPackage)
        assertFalse("Cloak must be revoked immediately when switching away from bank", BankingCloakManager.isCloaked)
        assertEquals("com.android.chrome", testBus.activePackage.value)
    }

    @Test
    fun `test foreground change when cloaked stays cloaked if user is within banking app`() {
        val watcher = createWatcher()

        // 1. تفعيل وضع التخفي البنكي لتطبيق إنستاباي
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(BankingCloakManager.isCloaked)

        // 2. استمرار التنقل داخل واجهات تطبيق البنك
        watcher.onForegroundPackageDetected(BankingCloakManager.INSTAPAY_PACKAGE)

        // 3. التأكد من بقاء التخفي نشطاً دون انقطاع
        assertTrue("Cloak must remain active while in target banking app", BankingCloakManager.isCloaked)
    }

    @Test
    fun `test foreground change when cloaked ignores transient system ui packages`() {
        val watcher = createWatcher()

        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(BankingCloakManager.isCloaked)

        // ظهور واجهة نظامية أو لوحة مفاتيح لا يلغي التخفي
        watcher.onForegroundPackageDetected("com.android.systemui")
        assertTrue("SystemUI overlay must not cancel banking cloak", BankingCloakManager.isCloaked)

        watcher.onForegroundPackageDetected("com.google.android.inputmethod.latin")
        assertTrue("Keyboard input method must not cancel banking cloak", BankingCloakManager.isCloaked)
    }

    @Test
    fun `test screen turned off while cloaked keeps cloak active until user navigates away`() {
        val watcher = createWatcher()
        watcher.isRunning = true
        watcher.isScreenOn = true

        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(BankingCloakManager.isCloaked)

        // إغلاق الشاشة
        watcher.onScreenStateChanged(false)

        assertFalse(watcher.isScreenOn)
        assertTrue("Screen off must keep cloak active until user navigates away", BankingCloakManager.isCloaked)
    }

    @Test
    fun `test checkRunnable when cloaked and waqfa disabled does not pause loop`() {
        // Waqfa معطل تماماً
        val disabledConfig = WaqfaConfig(isEnabled = false, targetPackages = emptySet())
        val watcher = createWatcher(disabledConfig)
        watcher.isRunning = true
        watcher.isScreenOn = true
        testBus.setAccessibilityActive(false)

        // تفعيل التخفي البنكي
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(BankingCloakManager.isCloaked)

        // تشغيل دورة الفحص
        watcher.checkRunnable.run()

        // التأكد من استمرار جدولة الفحص التالي لأن وضع التخفي نشط
        assertEquals("Loop must schedule next check even if Waqfa is disabled", 1, testScheduler.delayedRunnables.size)
        assertEquals(400L, testScheduler.delayedRunnables[0].second)
    }

    @Test
    fun `test checkRunnable when not cloaked and waqfa disabled pauses loop`() {
        // Waqfa معطل والتخفي غير مفعل
        val disabledConfig = WaqfaConfig(isEnabled = false, targetPackages = emptySet())
        val watcher = createWatcher(disabledConfig)
        watcher.isRunning = true
        watcher.isScreenOn = true
        testBus.setAccessibilityActive(false)

        assertFalse(BankingCloakManager.isCloaked)

        // تشغيل دورة الفحص
        watcher.checkRunnable.run()

        // التأكد من توقف الفحص الدوري
        assertEquals("Loop must pause when Waqfa is disabled and not cloaked", 0, testScheduler.delayedRunnables.size)
    }

    @Test
    fun `test notifyConfigChanged schedules loop if cloaked even if config is disabled`() {
        val disabledConfig = WaqfaConfig(isEnabled = false, targetPackages = emptySet())
        val watcher = createWatcher(disabledConfig)
        watcher.isRunning = true
        watcher.isScreenOn = true
        testBus.setAccessibilityActive(false)

        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(BankingCloakManager.isCloaked)

        watcher.notifyConfigChanged()

        assertEquals(1, testScheduler.postedRunnables.size)
    }

    @Test
    fun `test stop and restart clears callbacks and manages lifecycle safely`() {
        val watcher = createWatcher()
        watcher.start()
        assertTrue(watcher.isRunning)

        watcher.stop()
        assertFalse(watcher.isRunning)
        assertTrue(testScheduler.removedCount > 0)

        // Starting again should succeed without issue
        watcher.start()
        assertTrue(watcher.isRunning)
        watcher.stop()
        assertFalse(watcher.isRunning)
    }
}

package com.drmohammed.antiporn.core.engine

import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SystemAppsHealingManager] and OEM App Stores immunity.
 */
class SystemAppsHealingManagerTest {

    @Test
    fun testPreinstalledOemStores_containsMajorVendors() {
        val stores = SystemAppsHealingManager.PREINSTALLED_OEM_STORES
        assertTrue("Must include Samsung Galaxy Store", stores.contains("com.sec.android.app.samsungapps"))
        assertTrue("Must include Xiaomi GetApps", stores.contains("com.xiaomi.mipicks"))
        assertTrue("Must include Huawei AppGallery", stores.contains("com.huawei.appmarket"))
        assertTrue("Must include Oppo Market", stores.contains("com.oppo.market"))
        assertTrue("Must include Realme Market", stores.contains("com.heytap.market"))
        assertTrue("Must include Vivo App Store", stores.contains("com.vivo.appstore"))
        assertTrue("Must include Honor App Market", stores.contains("com.hihonor.appmarket"))
        assertTrue("Must include Transsion Palm Store", stores.contains("com.transsion.palmstore"))
    }

    @Test
    fun testAppPolicyRegistry_isAppStoreRecognizesOemStores() {
        assertTrue(AppPolicyRegistry.isAppStore("com.sec.android.app.samsungapps"))
        assertTrue(AppPolicyRegistry.isAppStore("com.xiaomi.mipicks"))
        assertTrue(AppPolicyRegistry.isAppStore("com.huawei.appmarket"))
        assertTrue(AppPolicyRegistry.isAppStore("com.oppo.market"))
        assertTrue(AppPolicyRegistry.isAppStore("com.heytap.market"))
        assertTrue(AppPolicyRegistry.isAppStore("com.vivo.appstore"))
        assertTrue(AppPolicyRegistry.isAppStore("com.hihonor.appmarket"))
        assertTrue(AppPolicyRegistry.isAppStore("com.transsion.palmstore"))
        assertTrue(AppPolicyRegistry.isAppStore("com.android.vending"))
        assertFalse(AppPolicyRegistry.isAppStore("com.android.chrome"))
    }

    @Test
    fun testHealingConcurrencyGuard_initialStateIsFalse() {
        assertFalse(SystemAppsHealingManager.isHealingRunning.get())
    }
}

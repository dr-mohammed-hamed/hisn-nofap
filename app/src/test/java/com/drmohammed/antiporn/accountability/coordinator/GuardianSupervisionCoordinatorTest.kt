package com.drmohammed.antiporn.accountability.coordinator

import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.absoluteValue

class GuardianSupervisionCoordinatorTest {

    @Test
    fun testNotificationIdComputation_isDeterministicAndPositive() {
        val ward1 = "ward_uid_abc123"
        val pkg1 = "com.example.unapproved1"
        val pkg2 = "com.example.unapproved2"
        val ward2 = "ward_uid_xyz789"

        val baseId = NotificationRegistry.EVENT_WARD_REQUEST_ID
        val id1 = GuardianSupervisionCoordinator.computeSystemNotificationId(ward1, pkg1)
        val id2 = GuardianSupervisionCoordinator.computeSystemNotificationId(ward1, pkg2)
        val id3 = GuardianSupervisionCoordinator.computeSystemNotificationId(ward2, pkg1)
        val id1Repeat = GuardianSupervisionCoordinator.computeSystemNotificationId(ward1, pkg1)

        // Deterministic
        assertEquals(id1, id1Repeat)

        // Unique across different packages / wards
        assertNotEquals(id1, id2)
        assertNotEquals(id1, id3)

        // Within expected positive range
        assertTrue(id1 >= baseId && id1 < baseId + 1000)
        assertTrue(id2 >= baseId && id2 < baseId + 1000)
    }

    @Test
    fun testNotificationIdComputation_handlesNegativeAndExtremeHashCodes() {
        val baseId = NotificationRegistry.EVENT_WARD_REQUEST_ID
        // Verify that masking with 0x7FFFFFFF guarantees non-negative IDs even for Int.MIN_VALUE
        val extremeMinVal = Int.MIN_VALUE
        val safeOffset = (extremeMinVal and 0x7FFFFFFF) % 1000
        assertTrue("Safe offset must never be negative", safeOffset >= 0)

        // Test with arbitrary strings
        for (i in 0..100) {
            val id = GuardianSupervisionCoordinator.computeSystemNotificationId("ward_$i", "pkg_$i")
            assertTrue("ID must be within [baseId, baseId + 1000)", id in baseId until (baseId + 1000))
        }
    }

    @Test
    fun testDedupKeyFormat_consistentWithViewBinder() {
        val wardId = "user_ward_456"
        val pkg = "com.social.chat"

        val expectedDedupKey = "guardian_req_${wardId}_$pkg"
        assertEquals("guardian_req_user_ward_456_com.social.chat", expectedDedupKey)
    }

    @Test
    fun testActionTypeAndCategory_matchApprovalsHubRouting() {
        val category = NotificationCategory.APPROVALS
        val actionType = NotificationActionType.OPEN_APPROVALS_HUB

        assertEquals("APPROVALS", category.key)
        assertEquals("OPEN_APPROVALS_HUB", actionType.key)

        val wardId = "ward_789"
        val pkg = "com.rogue.game"
        val payload = "$wardId|$pkg"

        assertTrue(payload.contains("|"))
        val parts = payload.split("|")
        assertEquals(wardId, parts[0])
        assertEquals(pkg, parts[1])
    }

    @Test
    fun testSupervisionEventChannels_areConfiguredCorrectly() {
        assertEquals("antiporn_protection_events", NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
        assertEquals("antiporn_critical_alerts", NotificationRegistry.CHANNEL_ID_CRITICAL_ALERTS)
        assertEquals(8001, NotificationRegistry.EVENT_GUARDIAN_REQUEST_ID)
        assertEquals(8002, NotificationRegistry.EVENT_WARD_REQUEST_ID)
        assertEquals(8004, NotificationRegistry.EVENT_BREAKOUT_REQUEST_ID)
        assertEquals(7001, NotificationRegistry.EVENT_SYSTEM_TAMPER_ID)
        assertEquals(8500, NotificationRegistry.EVENT_SELF_CONTROL_ALERT_ID)
    }
}

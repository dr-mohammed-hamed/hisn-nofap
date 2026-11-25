package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSecondaryUserInspectorTest {

    private class FakeAppContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.drmohammed.antiporn"
        override fun getPackageManager(): PackageManager? = null
    }

    private fun shellOk(stdout: String, stderr: String = ""): ShellResult {
        return ShellResult(stdout.toByteArray(), stderr.toByteArray(), 0)
    }

    private fun shellFail(stdout: String = "", stderr: String = "", exitCode: Int = 1): ShellResult {
        return ShellResult(stdout.toByteArray(), stderr.toByteArray(), exitCode)
    }

    @Test
    fun parsePmListUsers_parsesSamsungXiaomiOppoPixelColonsAndExcludesPrimaryAndCurrent() {
        val output = """
            Users:
                UserInfo{0:Owner:4c13} running
                UserInfo{150:Secure Folder:10061030}
                UserInfo{152:Secure Folder:61090}
                UserInfo{95:DUAL_APP:20001010} running
                UserInfo{999:XSpace:20001010}
                UserInfo{999:MultiApp:20001010}
                UserInfo{10:Work: Profile:1030}
                UserInfo{11:Private space:1090}
                UserInfo{12:Guest:4}
        """.trimIndent()

        val parsedDefaultCurrent = AdbSecondaryUserInspector.parsePmListUsers(output, currentUserId = 0)
        assertEquals(
            listOf(150, 152, 95, 999, 10, 11, 12),
            parsedDefaultCurrent.map { it.userId }
        )
        assertFalse(parsedDefaultCurrent.any { it.userId <= 0 })
        assertEquals("XSpace", parsedDefaultCurrent.first { it.userId == 999 }.rawName)
        assertEquals("Work: Profile", parsedDefaultCurrent.first { it.userId == 10 }.rawName)

        val parsedNonZeroCurrent = AdbSecondaryUserInspector.parsePmListUsers(output, currentUserId = 10)
        assertFalse(parsedNonZeroCurrent.any { it.userId == 0 || it.userId == 10 })
        assertEquals(listOf(150, 152, 95, 999, 11, 12), parsedNonZeroCurrent.map { it.userId })
    }

    @Test
    fun classifyAdbUser_allCategoriesArabicEnglishAndLocalProfileType() {
        assertEquals(
            BlockingUserCategory.SECURE_FOLDER,
            AdbSecondaryUserInspector.classifyAdbUser(150, "Secure Folder", "10061030", isSamsung = true)
        )
        assertEquals(
            BlockingUserCategory.SECURE_FOLDER,
            AdbSecondaryUserInspector.classifyAdbUser(152, "المجلد الآمن", "61090", isSamsung = true)
        )
        assertEquals(
            BlockingUserCategory.SECURE_FOLDER,
            AdbSecondaryUserInspector.classifyAdbUser(155, "User 155", "1000", isSamsung = true)
        )
        assertEquals(
            BlockingUserCategory.SECONDARY_USER,
            AdbSecondaryUserInspector.classifyAdbUser(150, "Secondary", "1000", isSamsung = false)
        )

        assertEquals(
            BlockingUserCategory.DUAL_APP_CLONE,
            AdbSecondaryUserInspector.classifyAdbUser(95, "DUAL_APP", "20001010", isSamsung = true)
        )
        assertEquals(
            BlockingUserCategory.DUAL_APP_CLONE,
            AdbSecondaryUserInspector.classifyAdbUser(96, "Clone", "1010", isSamsung = false)
        )
        assertEquals(
            BlockingUserCategory.DUAL_APP_CLONE,
            AdbSecondaryUserInspector.classifyAdbUser(999, "XSpace", "20001010", isSamsung = false)
        )
        assertEquals(
            BlockingUserCategory.DUAL_APP_CLONE,
            AdbSecondaryUserInspector.classifyAdbUser(22, "تطبيق مزدوج", "1010", isSamsung = false)
        )

        assertEquals(
            BlockingUserCategory.PRIVATE_SPACE,
            AdbSecondaryUserInspector.classifyAdbUser(11, "Private space", "1090", isSamsung = false)
        )
        assertEquals(
            BlockingUserCategory.PRIVATE_SPACE,
            AdbSecondaryUserInspector.classifyAdbUser(13, "المساحة الخاصة", "1090", isSamsung = false)
        )
        assertEquals(
            BlockingUserCategory.PRIVATE_SPACE,
            AdbSecondaryUserInspector.classifyAdbUser(
                14,
                "Espace privé",
                "1090",
                isSamsung = false,
                localProfileType = SecondaryProfileDetector.ProfileType.PRIVATE_SPACE
            )
        )

        assertEquals(
            BlockingUserCategory.GUEST_USER,
            AdbSecondaryUserInspector.classifyAdbUser(12, "Guest", "4", isSamsung = false)
        )
        assertEquals(
            BlockingUserCategory.GUEST_USER,
            AdbSecondaryUserInspector.classifyAdbUser(15, "ضيف", "10", isSamsung = false)
        )

        assertEquals(
            BlockingUserCategory.WORK_PROFILE,
            AdbSecondaryUserInspector.classifyAdbUser(10, "Work profile", "1030", isSamsung = false)
        )
        assertEquals(
            BlockingUserCategory.WORK_PROFILE,
            AdbSecondaryUserInspector.classifyAdbUser(17, "Island", "1010", isSamsung = false)
        )
        assertEquals(
            BlockingUserCategory.WORK_PROFILE,
            AdbSecondaryUserInspector.classifyAdbUser(18, "Shelter", "1010", isSamsung = false)
        )

        assertEquals(
            BlockingUserCategory.SECONDARY_USER,
            AdbSecondaryUserInspector.classifyAdbUser(20, "Ahmed", "10", isSamsung = false)
        )
    }

    @Test
    fun parseThirdPartyPackagesOutput_handlesNormalListAndSecurityExceptions() {
        val normalOutput = """
            package:com.whatsapp
            package:org.telegram.messenger
            package:com.whatsapp
        """.trimIndent()
        val (pkgs, blockedNormal) = AdbSecondaryUserInspector.parseThirdPartyPackagesOutput(normalOutput)
        assertFalse(blockedNormal)
        assertEquals(listOf("com.whatsapp", "org.telegram.messenger"), pkgs)

        val knoxError = """
            Exception occurred while executing 'list':
            java.lang.SecurityException: Shell does not have permission to access user 150
        """.trimIndent()
        val (knoxPkgs, blockedKnox) = AdbSecondaryUserInspector.parseThirdPartyPackagesOutput(knoxError)
        assertTrue(blockedKnox)
        assertTrue(knoxPkgs.isEmpty())

        val permissionDenial = "Permission Denial: can't list packages for user 152"
        val (deniedPkgs, blockedDenied) = AdbSecondaryUserInspector.parseThirdPartyPackagesOutput(permissionDenial)
        assertTrue(blockedDenied)
        assertTrue(deniedPkgs.isEmpty())

        val doesNotHavePerm = "Shell does not have permission to access user 11"
        val (_, blockedDoesNotHave) = AdbSecondaryUserInspector.parseThirdPartyPackagesOutput(doesNotHavePerm)
        assertTrue(blockedDoesNotHave)
    }

    @Test
    fun hasRemoveUserError_detectsExitCodeAndErrorMessages() {
        assertFalse(AdbSecondaryUserInspector.hasRemoveUserError(shellOk("Success: removed user")))
        assertTrue(AdbSecondaryUserInspector.hasRemoveUserError(shellFail("Error: couldn't remove user")))
        assertTrue(AdbSecondaryUserInspector.hasRemoveUserError(shellOk("Failure: Couldn't remove user 150")))
        assertTrue(AdbSecondaryUserInspector.hasRemoveUserError(shellOk("Cannot remove primary user")))
        assertTrue(AdbSecondaryUserInspector.hasRemoveUserError(shellOk("Permission Denial: removeUser")))
    }

    @Test
    fun inspectBlockingUsersWithShell_handlesBlockedPackagesAndExcludesOwnPackage() = runBlocking {
        val context = FakeAppContext()
        val users = AdbSecondaryUserInspector.inspectBlockingUsersWithShell(context) { cmd ->
            when (cmd) {
                "am get-current-user" -> shellOk("0")
                "pm list users" -> shellOk(
                    """
                    Users:
                        UserInfo{0:Owner:4c13} running
                        UserInfo{95:DUAL_APP:20001010}
                        UserInfo{11:Private space:1090}
                    """.trimIndent()
                )
                "pm list packages -3 --user 95" -> shellOk("package:com.drmohammed.antiporn\npackage:com.whatsapp")
                "pm list packages -3 --user 11" -> shellFail("", "SecurityException: denied", 1)
                else -> error("Unexpected cmd: $cmd")
            }
        }

        assertEquals(2, users.size)
        assertEquals(95, users[0].userId)
        assertEquals(BlockingUserCategory.DUAL_APP_CLONE, users[0].category)
        assertEquals(listOf("com.whatsapp"), users[0].installedPackageNames)
        assertFalse(users[0].isAppsInspectionBlocked)

        assertEquals(11, users[1].userId)
        assertEquals(BlockingUserCategory.PRIVATE_SPACE, users[1].category)
        assertTrue(users[1].isAppsInspectionBlocked)
    }

    @Test
    fun inspectBlockingUsersWithShell_throwsWhenCurrentUserOrUserListFails() = runBlocking {
        val context = FakeAppContext()

        val failedCurrentUser = runCatching {
            AdbSecondaryUserInspector.inspectBlockingUsersWithShell(context) { cmd ->
                when (cmd) {
                    "am get-current-user" -> shellFail("", "Permission Denial", 1)
                    else -> shellOk("")
                }
            }
        }
        assertTrue(failedCurrentUser.isFailure)

        val failedUserList = runCatching {
            AdbSecondaryUserInspector.inspectBlockingUsersWithShell(context) { cmd ->
                when (cmd) {
                    "am get-current-user" -> shellOk("0")
                    "pm list users" -> shellFail("", "Exception", 1)
                    else -> shellOk("")
                }
            }
        }
        assertTrue(failedUserList.isFailure)
    }

    @Test
    fun removeApprovedUsersWithShell_protectsActiveUserAndHandlesSuccessAndFailure() = runBlocking {
        val context = FakeAppContext()
        var listUsersCalls = 0
        val outcome = AdbSecondaryUserInspector.removeApprovedUsersWithShell(
            context = context,
            approvedUserIds = setOf(10, 95, 150)
        ) { cmd ->
            when (cmd) {
                "am get-current-user" -> shellOk("10")
                "pm list users" -> {
                    listUsersCalls++
                    if (listUsersCalls == 1) {
                        shellOk(
                            """
                            Users:
                                UserInfo{0:Owner:4c13}
                                UserInfo{10:Active:10} running
                                UserInfo{95:DUAL_APP:20001010}
                                UserInfo{150:Secure Folder:10061030}
                            """.trimIndent()
                        )
                    } else {
                        shellOk(
                            """
                            Users:
                                UserInfo{0:Owner:4c13}
                                UserInfo{10:Active:10} running
                            """.trimIndent()
                        )
                    }
                }
                "pm remove-user 95" -> shellOk("Success: removed user")
                "pm remove-user 150" -> shellOk("Success: removed user")
                "pm remove-user 10" -> error("Must never invoke pm remove-user on active currentUserId=10")
                else -> shellOk("")
            }
        }

        assertEquals(listOf(95, 150), outcome.removedIds)
        assertTrue(outcome.failedIds.isEmpty())
        assertTrue(outcome.remainingUsers.isEmpty())
    }

    @Test
    fun blockingSecondaryUserJson_roundTripAndNullSafetyFilter() {
        val original = listOf(
            BlockingSecondaryUser(
                userId = 150,
                rawName = "Secure Folder",
                flagsHex = "10061030",
                category = BlockingUserCategory.SECURE_FOLDER,
                installedAppNames = emptyList(),
                installedPackageNames = emptyList(),
                isAppsInspectionBlocked = true
            ),
            BlockingSecondaryUser(
                userId = 95,
                rawName = "DUAL_APP",
                flagsHex = "20001010",
                category = BlockingUserCategory.DUAL_APP_CLONE,
                installedAppNames = listOf("WhatsApp", "Telegram"),
                installedPackageNames = listOf("com.whatsapp", "org.telegram.messenger"),
                isAppsInspectionBlocked = false
            )
        )

        val json = BlockingSecondaryUser.listToJson(original)
        val restored = BlockingSecondaryUser.listFromJson(json)
        assertEquals(original, restored)

        val unsafeJson = """[{"userId":0,"rawName":"Owner","flagsHex":"4c13","category":"SECONDARY_USER"}]"""
        assertTrue(BlockingSecondaryUser.listFromJson(unsafeJson).isEmpty())
        assertTrue(BlockingSecondaryUser.listFromJson(null).isEmpty())
        assertTrue(BlockingSecondaryUser.listFromJson("not-json").isEmpty())

        val invalidCategoryJson = """{"userId":95,"rawName":"Dual","category":"UNKNOWN_CAT"}"""
        assertNull(BlockingSecondaryUser.fromJson(JSONObject(invalidCategoryJson)))

        val jsonNullField = """{"userId":95,"rawName":null,"flagsHex":null,"category":"DUAL_APP_CLONE"}"""
        val parsedNull = BlockingSecondaryUser.fromJson(JSONObject(jsonNullField))
        assertEquals("", parsedNull?.rawName)
        assertEquals("", parsedNull?.flagsHex)
    }

    @Test
    fun removeSecondaryUsersAndPromote_rejectsMissingConsentEmptyOrNonPositiveUserIds() = runBlocking {
        val context = FakeAppContext()

        val noConsent = DeviceOwnerPromoter.removeSecondaryUsersAndPromote(
            context = context,
            connectPort = 5555,
            approvedUserIds = setOf(150),
            explicitUserConsentConfirmed = false
        )
        assertTrue(noConsent is PromotionResult.Failure)
        assertTrue((noConsent as PromotionResult.Failure).message.contains("Explicit user consent"))

        val emptyIds = DeviceOwnerPromoter.removeSecondaryUsersAndPromote(
            context = context,
            connectPort = 5555,
            approvedUserIds = emptySet(),
            explicitUserConsentConfirmed = true
        )
        assertTrue(emptyIds is PromotionResult.Failure)
        assertTrue((emptyIds as PromotionResult.Failure).message.contains("Explicit user consent"))

        val zeroUser = DeviceOwnerPromoter.removeSecondaryUsersAndPromote(
            context = context,
            connectPort = 5555,
            approvedUserIds = setOf(0),
            explicitUserConsentConfirmed = true
        )
        assertTrue(zeroUser is PromotionResult.Failure)
        assertTrue((zeroUser as PromotionResult.Failure).message.contains("Explicit user consent"))

        val mixedWithZero = DeviceOwnerPromoter.removeSecondaryUsersAndPromote(
            context = context,
            connectPort = 5555,
            approvedUserIds = setOf(150, 0),
            explicitUserConsentConfirmed = true
        )
        assertTrue(mixedWithZero is PromotionResult.Failure)
        assertTrue((mixedWithZero as PromotionResult.Failure).message.contains("Explicit user consent"))

        val negativeUser = DeviceOwnerPromoter.removeSecondaryUsersAndPromote(
            context = context,
            connectPort = 5555,
            approvedUserIds = setOf(-1),
            explicitUserConsentConfirmed = true
        )
        assertTrue(negativeUser is PromotionResult.Failure)
        assertTrue((negativeUser as PromotionResult.Failure).message.contains("Explicit user consent"))
    }
}

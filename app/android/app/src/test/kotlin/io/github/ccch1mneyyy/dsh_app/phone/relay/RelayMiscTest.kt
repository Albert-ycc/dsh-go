package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.phone.AppEntry
import io.github.ccch1mneyyy.dsh_app.phone.IntentSpec
import io.github.ccch1mneyyy.dsh_app.phone.SafetyGate
import io.github.ccch1mneyyy.dsh_app.phone.SafetyRules
import io.github.ccch1mneyyy.dsh_app.phone.PhonePolicies
import io.github.ccch1mneyyy.dsh_app.phone.PhoneTasks
import io.github.ccch1mneyyy.dsh_app.phone.SysResult
import io.github.ccch1mneyyy.dsh_app.phone.SystemApi
import io.github.ccch1mneyyy.dsh_app.phone.TargetInfo
import io.github.ccch1mneyyy.dsh_app.phone.UrlTarget
import io.github.ccch1mneyyy.dsh_app.phone.WriteKind
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ProtectedPages
import org.junit.Assert.*
import org.junit.Test

class PhoneHandTest {
    private val pura = "u:r:aoco_untrusted_app_27:s0:c93,c256,c512,c768"
    private val plain = "u:r:untrusted_app:s0:c12,c256,c512,c768"

    @Test fun autoPicksMacOnlyInsideTheContainer() {
        assertEquals(PhoneHand.MAC, PhoneHand.resolve(null, pura))
        assertEquals(PhoneHand.MAC, PhoneHand.resolve("auto", pura))
        assertEquals(PhoneHand.A11Y, PhoneHand.resolve(null, plain))
        assertEquals(PhoneHand.A11Y, PhoneHand.resolve("auto", null))
        assertEquals(PhoneHand.A11Y, PhoneHand.resolve("bogus", plain))
    }

    @Test fun anExplicitChoiceWinsEitherWay() {
        assertEquals(PhoneHand.A11Y, PhoneHand.resolve("a11y", pura))
        assertEquals(PhoneHand.MAC, PhoneHand.resolve("mac", plain))
    }
}

class HarmonySystemPackagesTest {
    private val bundles = listOf(
        "com.huawei.hmos.settings", "com.ohos.permissionmanager", "com.huawei.hmos.security.privacycenter",
        "com.huawei.hmos.securityguardassistant", "com.huawei.hmos.secureaccessfence", "com.ohos.systemui",
    )

    @Test fun everyHarmonySystemBundleIsASystemPage() {
        for (b in bundles) {
            assertTrue(b, SafetyRules.isSystemPackage(b))
            val gate = SafetyGate({ PhonePolicies() }, PhoneTasks(), { null })
            val t = TargetInfo("s1", 1, "按钮", "打开", b, null, false, false, false)
            assertEquals(b, "system-page", (gate.checkWrite(WriteKind.TAP, t, b, null) as SafetyGate.Verdict.Refuse).rule)
        }
        assertFalse(SafetyRules.isSystemPackage("com.huawei.hmos.calculator"))
        // 鸿蒙桌面不是敏感页：回桌面后点图标要能走。
        assertFalse(SafetyRules.isSystemPackage("com.ohos.sceneboard"))
    }

    private fun gate(unlocks: io.github.ccch1mneyyy.dsh_app.phone.PhoneUnlocks = io.github.ccch1mneyyy.dsh_app.phone.PhoneUnlocks(), strict: Boolean = true) =
        SafetyGate({ PhonePolicies(unlocks = unlocks) }, PhoneTasks(), { null }, strictScroll = { strict })

    @Test fun theAccessibilityHandKeepsScrollingOnSystemPages() {
        assertSame(SafetyGate.Verdict.Allow, gate(strict = false).checkWrite(WriteKind.SCROLL, null, "com.android.settings", null))
        val tap = gate(strict = false).checkWrite(WriteKind.TAP, null, "com.android.settings", null)
        assertEquals("system-page", (tap as SafetyGate.Verdict.Refuse).rule)
    }

    @Test fun scrollOnASystemPageIsRefusedUntilTheyAreUnlocked() {
        val v = gate().checkWrite(WriteKind.SCROLL, null, "com.ohos.systemui", null)
        assertEquals("system-page", (v as SafetyGate.Verdict.Refuse).rule)
        assertSame(SafetyGate.Verdict.Allow, gate().checkWrite(WriteKind.SCROLL, null, "com.example.app", null))
        val lifted = gate(io.github.ccch1mneyyy.dsh_app.phone.PhoneUnlocks(systemPages = true))
        assertNull((lifted.checkWrite(WriteKind.SCROLL, null, "com.ohos.systemui", null) as? SafetyGate.Verdict.Refuse))
    }

    @Test fun keysStayAWayOutEvenOnSystemPages() {
        assertSame(SafetyGate.Verdict.Allow, gate().checkWrite(WriteKind.KEY, null, "com.huawei.hmos.settings", null))
    }

    @Test fun thePhoneSidePaymentAndWalletBundlesAreOffLimits() {
        for (b in listOf("com.huawei.hmos.wallet", "com.alipay.mobile.client")) {
            assertTrue(b, b in SafetyRules.DEFAULT_DENY)
            assertEquals("deny-list", gate().checkRead(b, null)?.rule)
        }
        assertTrue("com.tencent.wechat" in SafetyRules.DEFAULT_CONFIRM_EVERY_WRITE)
    }

    @Test fun protectedPagesCoverTheHarmonyPermissionAndSecurityBundles() {
        for (b in listOf("com.ohos.permissionmanager", "com.huawei.hmos.security.privacycenter", "com.huawei.hmos.securityguardassistant")) {
            assertTrue(b, ProtectedPages.isProtected(b, emptyList()))
        }
        // 鸿蒙设置：只在标题命中时受保护，和其他设置应用一样。
        assertFalse(ProtectedPages.isProtected("com.huawei.hmos.settings", listOf("蓝牙")))
        assertTrue(ProtectedPages.isProtected("com.huawei.hmos.settings", listOf("无障碍")))
        assertFalse(ProtectedPages.isProtected("com.huawei.hmos.calculator", listOf("无障碍")))
    }
}

class RelaySystemApiTest {
    private class Container : SystemApi {
        val launched = mutableListOf<String>()
        var intents = 0
        override fun apps() = listOf(AppEntry("相机(容器)", "cam.android"))
        override fun isInstalled(pkg: String) = pkg == "cam.android"
        override fun launch(app: AppEntry): SysResult { launched += app.packageName; return SysResult(true, "ok") }
        override fun urlTarget(url: String) = UrlTarget()
        override fun openUrl(url: String, target: UrlTarget) = SysResult(true, "ok")
        override fun intent(spec: IntentSpec, shareTo: AppEntry?): SysResult { intents++; return SysResult(true, "delegated") }
        override fun notify(title: String, text: String) = SysResult(true, "ok")
        override fun clipboardSet(text: String) = SysResult(true, "ok")
        override fun clipboardGet() = SysResult(true, "ok")
    }

    private class Api : RelayApi {
        val launches = mutableListOf<String>()
        var appsCalls = 0
        var apps: RelayResult<List<RelayApp>> = RelayResult.Ok(listOf(RelayApp("com.huawei.hmos.settings", null, "设置"), RelayApp("cam.android", null, "重复"), RelayApp("com.huawei.hmos.x", null, null)))
        override fun configured() = true
        override fun device(maxAgeMs: Long): RelayResult<RelayDeviceInfo> = throw AssertionError()
        override fun observe(image: Boolean): RelayResult<Map<String, Any?>> = throw AssertionError()
        override fun act(body: Map<String, Any?>): RelayResult<Map<String, Any?>> = throw AssertionError()
        override fun launch(bundle: String, ability: String?, module: String?): RelayResult<Map<String, Any?>> { launches += bundle; return RelayResult.Ok(emptyMap()) }
        override fun apps(query: String?): RelayResult<List<RelayApp>> { appsCalls++; return apps }
        override fun target(addr: String): RelayResult<Map<String, Any?>> = throw AssertionError()
        override fun stop(task: Long): RelayResult<Int> = throw AssertionError()
        override fun halt(): RelayResult<Unit> = throw AssertionError()
    }

    private val container = Container()
    private val api = Api()
    private var now = 0L
    private val queued = mutableListOf<Runnable>()
    private val sys = RelaySystemApi(container, api, { now }, { queued += it })

    @Test fun appsMergeContainerAndPhoneByBundle() {
        val apps = sys.apps()
        assertEquals(listOf("cam.android", "com.huawei.hmos.settings", "com.huawei.hmos.x"), apps.map { it.packageName })
        assertEquals("设置", apps[1].label)
        assertEquals("com.huawei.hmos.x", apps[2].label)
        sys.apps(); sys.labelOf("com.huawei.hmos.settings")
        assertEquals("the list is asked once a minute, not per call", 1, api.appsCalls)
        now += 61_000
        sys.apps()
        assertEquals(2, api.appsCalls)
    }

    @Test fun launchGoesToTheContainerOrTheMacBySource() {
        assertTrue(sys.launch(AppEntry("相机", "cam.android")).ok)
        assertEquals(listOf("cam.android"), container.launched)
        assertTrue(api.launches.isEmpty())
        val r = sys.launch(AppEntry("设置", "com.huawei.hmos.settings"))
        assertTrue(r.ok)
        assertEquals(listOf("com.huawei.hmos.settings"), api.launches)
        assertEquals("com.huawei.hmos.settings", r.packageName)
    }

    @Test fun alarmTimerCalendarPointToTheUiOthersAreDelegated() {
        assertFalse(sys.intent(IntentSpec.Alarm(7, 0, null, emptyList()), null).ok)
        assertFalse(sys.intent(IntentSpec.Timer(60, null), null).ok)
        assertFalse(sys.intent(IntentSpec.Calendar("t", 0, 1, false, null, null), null).ok)
        assertEquals(0, container.intents)
        assertEquals("delegated", sys.intent(IntentSpec.Dial("10086"), null).message)
        assertEquals("delegated", sys.intent(IntentSpec.Settings("wifi", null), null).message)
    }

    @Test fun labelsComeFromTheFetchedListAndReadingAScreenNeverAsksTheMac() {
        assertNull("empty cache: answers at once", sys.labelOf("com.huawei.hmos.settings"))
        assertEquals("the read path itself never calls the Mac", 0, api.appsCalls)
        assertNull(sys.labelOf("com.huawei.hmos.settings"))
        assertEquals("one refresh is queued, not one per read", 1, queued.size)
        queued.removeAt(0).run()
        assertEquals(1, api.appsCalls)
        assertEquals("设置", sys.labelOf("com.huawei.hmos.settings"))
        assertTrue("fresh cache queues nothing", queued.isEmpty())
        now += 61_000
        assertEquals("设置", sys.labelOf("com.huawei.hmos.settings"))
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(2, api.appsCalls)
    }

    @Test fun aFailingAppsCallIsNotRetriedOnEveryCall() {
        api.apps = RelayResult.Err(RelayError(RelayCodes.DEVICE_TIMEOUT, "slow"))
        sys.apps(); sys.apps(); sys.apps()
        assertEquals(1, api.appsCalls)
        sys.labelOf("x")
        assertTrue("a failure is remembered for a minute: no refresh queued", queued.isEmpty())
        now += 61_000
        sys.apps()
        assertEquals(2, api.appsCalls)
    }

    @Test fun aFailingRelayListKeepsTheContainerAppsAndLastGoodList() {
        sys.apps()
        now += 61_000
        api.apps = RelayResult.Err(RelayError(RelayCodes.UNREACHABLE, "x"))
        assertEquals(3, sys.apps().size)
    }
}

package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import io.github.ccch1mneyyy.dsh_app.phone.PhoneUnlocks
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ImageGeometry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

private class FakeApi : RelayApi {
    var configured = true
    var device: RelayResult<RelayDeviceInfo> = RelayResult.Ok(RelayDeviceInfo(false, RelayDevice("ok", "usb", "t", "VDE-AL00", null, false, true), 0, "1"))
    var deviceSeq: ArrayDeque<RelayResult<RelayDeviceInfo>> = ArrayDeque()
    var deviceCalls = 0
    var unreadable = false
    var screens: ArrayDeque<Map<String, Any?>> = ArrayDeque()
    var last: Map<String, Any?> = emptyMap()
    var actResult: RelayResult<Map<String, Any?>> = RelayResult.Ok(mapOf("ok" to true))
    val acts = mutableListOf<Map<String, Any?>>()
    val launches = mutableListOf<String>()
    val stops = mutableListOf<Long>()
    var observes = 0

    override fun configured() = configured
    override fun storeUnreadable() = unreadable
    override fun device(maxAgeMs: Long): RelayResult<RelayDeviceInfo> { deviceCalls++; return if (deviceSeq.isNotEmpty()) deviceSeq.removeFirst() else device }
    override fun observe(image: Boolean): RelayResult<Map<String, Any?>> {
        observes++
        if (screens.isNotEmpty()) last = screens.removeFirst()
        return RelayResult.Ok(last)
    }
    override fun act(body: Map<String, Any?>): RelayResult<Map<String, Any?>> { acts += body; return actResult }
    override fun launch(bundle: String, ability: String?, module: String?): RelayResult<Map<String, Any?>> { launches += bundle; return RelayResult.Ok(mapOf("ok" to true)) }
    override fun apps(query: String?): RelayResult<List<RelayApp>> = RelayResult.Ok(emptyList())
    override fun target(addr: String): RelayResult<Map<String, Any?>> = RelayResult.Ok(mapOf("ok" to true))
    override fun stop(task: Long): RelayResult<Int> { stops += task; return RelayResult.Ok(0) }
    override fun halt(): RelayResult<Unit> = RelayResult.Ok(Unit)
}

class MacRelayDriverTest {
    private val api = FakeApi()
    private var unlocks = PhoneUnlocks()
    private var fakeShots = io.github.ccch1mneyyy.dsh_app.phone.relay.RelayShots { _, _, _, screen ->
        RelayComposed(byteArrayOf(9), ImageGeometry(0, 0, 0.5f, 0.5f, screen.width / 2, screen.height / 2), false, null)
    }
    private val driver by lazy {
        MacRelayDriver(api, "io.github.ccch1mneyyy.dsh_app", labelOf = { if (it == "com.huawei.hmos.settings") "设置" else null },
            unlocks = { unlocks }, shots = fakeShots, sleep = {})
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(s: String) = Json.parse(s) as Map<String, Any?>

    private fun settings(shiftY: Int = 0, noteText: String? = null): Map<String, Any?> {
        val f = File("../../../tooling/dsh-relay/contract/expected/synthetic-settings.observe.json")
        assumeTrue("contract sample missing", f.isFile)
        var text = f.readText()
        if (shiftY != 0) text = text.replace("[24,300,1296,420]", "[24,${300 + shiftY},1296,${420 + shiftY}]")
        if (noteText != null) text = text.replace(""""hint": "备注",""", """"hint": "备注", "text": "$noteText",""")
        return json(text)
    }

    private fun page(bundle: String, body: String) = json(
        """{"display":{"width":1000,"height":2000},"main":0,"device":{"locked":false},"windows":[{"bundle":"$bundle","ability":"A","kind":"app","bounds":[0,0,1000,2000],
           "root":{"cls":"Column","bounds":[0,0,1000,2000],"children":[$body]}}]}""",
    )

    private fun button(text: String, b: String = "[100,500,900,600]") =
        """{"cls":"android.widget.Button","text":"$text","bounds":$b,"flags":["click"]}"""

    private fun filler() = """{"cls":"android.widget.TextView","text":"标题","bounds":[0,100,400,160]},{"cls":"android.widget.TextView","text":"说明一","bounds":[0,200,400,260]},
        {"cls":"android.widget.TextView","text":"说明二","bounds":[0,300,400,360]},{"cls":"android.widget.TextView","text":"说明三","bounds":[0,400,400,460]},{"cls":"android.widget.TextView","text":"说明四","bounds":[0,1000,400,1060]}"""

    private fun idOf(snapText: String, label: String): Int =
        Regex("""\[(\d+)] [^\n]*$label""").find(snapText)!!.groupValues[1].toInt()

    @Before fun settle() { api.screens.clear() }

    @Test fun observeReadsTheSettingsPageWithBluetoothAsAnElement() {
        api.last = settings()
        val s = driver.observe(detail = false, image = false)
        assertTrue(s.text, s.text.startsWith("屏幕 s"))
        assertTrue(s.text, s.text.contains("蓝牙"))
        assertEquals("设置", s.appLabel)
        assertEquals("com.huawei.hmos.settings", s.packageName)
    }

    @Test fun tapRereadsFindsTheElementAndClicksItsCentre() {
        api.last = settings()
        val s = driver.observe(false, false)
        val id = idOf(s.text, "蓝牙")
        val r = driver.perform(PhoneAction.Tap(s.id, id))
        assertTrue(r.message, r.ok)
        val click = api.acts.single()
        assertEquals("click", click["op"])
        assertEquals(660, click["x"]) // [24,300,1296,420] 的中心
        assertEquals(360, click["y"])
        assertNotNull("the screen after the action comes back", r.screen)
    }

    @Test fun elementThatMovedSinceTheSnapshotIsNotTapped() {
        api.last = settings()
        val s = driver.observe(false, false)
        val id = idOf(s.text, "蓝牙")
        api.last = settings(shiftY = 100)
        val r = driver.perform(PhoneAction.Tap(s.id, id))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("变了"))
        assertTrue(api.acts.isEmpty())
    }

    @Test fun staleScreenIdIsRefusedWithoutAnyInput() {
        api.last = settings()
        driver.observe(false, false)
        val r = driver.perform(PhoneAction.Tap("s999999", 1))
        assertFalse(r.ok)
        assertTrue(api.acts.isEmpty())
    }

    @Test fun permissionManagerIsLookedAtButNeverTapped() {
        api.last = page("com.ohos.permissionmanager", filler() + "," + button("允许"))
        val s = driver.observe(false, false)
        assertTrue(s.text, s.text.contains("系统权限"))
        val r = driver.perform(PhoneAction.Tap(s.id, idOf(s.text, "允许")))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("系统权限"))
        assertTrue(api.acts.isEmpty())
        unlocks = PhoneUnlocks(systemPages = true)
        assertTrue(driver.perform(PhoneAction.Tap(s.id, idOf(s.text, "允许"))).ok)
        assertEquals(1, api.acts.size)
    }

    @Test fun passwordFieldIsNotFilledAndNoTextLeavesThePhone() {
        api.last = settings()
        val s = driver.observe(false, false)
        val id = Regex("""\[(\d+)] 输入框\(密码""").find(s.text)!!.groupValues[1].toInt()
        val r = driver.perform(PhoneAction.Type(s.id, id, "12345678"))
        assertFalse(r.ok)
        assertTrue(api.acts.isEmpty())
        val t = driver.target(s.id, id)
        assertTrue(t!!.isPassword)
    }

    @Test fun typeSendsReplaceTextThenReadsTheFieldBack() {
        api.last = settings()
        val s = driver.observe(false, false)
        val id = Regex("""\[(\d+)] 输入框 备注""").find(s.text)!!.groupValues[1].toInt()
        api.last = settings(noteText = "你好")
        val r = driver.perform(PhoneAction.Type(s.id, id, "你好"))
        assertTrue(r.message, r.ok)
        assertTrue(r.message, r.message.contains("回读一致"))
        val a = api.acts.single()
        assertEquals("replaceText", a["op"])
        assertEquals("你好", a["text"])
        assertEquals(660, a["x"])
    }

    @Test fun typeThatDidNotLandIsReportedAndNotSubmitted() {
        api.last = settings()
        val s = driver.observe(false, false)
        val id = Regex("""\[(\d+)] 输入框 备注""").find(s.text)!!.groupValues[1].toInt()
        api.last = settings(noteText = "别的")
        val r = driver.perform(PhoneAction.Type(s.id, id, "你好", submit = true))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("回读不一致"))
        // 组词条场景只补一次回车；回读仍不一致就不再提交（submit 的那次回车没发）。
        assertEquals(listOf("replaceText", "key"), api.acts.map { it["op"] })
        assertEquals("enter", api.acts[1]["name"])
        assertFalse(api.acts[0].containsKey("commit"))
    }

    @Test fun composingTextIsCommittedWithOneEnterWhenTheFirstReadBackDiffers() {
        api.last = settings()
        val s = driver.observe(false, false)
        val id = Regex("""\[(\d+)] 输入框 备注""").find(s.text)!!.groupValues[1].toInt()
        // resolve 的一次读屏 + 回读两次都还是空的，补回车后才读到文字
        api.screens.addAll(listOf(settings(), settings(), settings(), settings(noteText = "你好")))
        val r = driver.perform(PhoneAction.Type(s.id, id, "你好"))
        assertTrue(r.message, r.ok)
        assertEquals(listOf("replaceText", "key"), api.acts.map { it["op"] })
    }

    @Test fun submitSendsExactlyOneEnterAfterAConsistentReadBack() {
        api.last = settings()
        val s = driver.observe(false, false)
        val id = Regex("""\[(\d+)] 输入框 备注""").find(s.text)!!.groupValues[1].toInt()
        api.last = settings(noteText = "你好")
        val r = driver.perform(PhoneAction.Type(s.id, id, "你好", submit = true))
        assertTrue(r.message, r.ok)
        assertEquals(listOf("replaceText", "key"), api.acts.map { it["op"] })
        assertEquals("enter", api.acts[1]["name"])
    }

    @Test fun textThatTheRelayWouldRejectIsRefusedHere() {
        api.last = settings()
        val s = driver.observe(false, false)
        val id = Regex("""\[(\d+)] 输入框 备注""").find(s.text)!!.groupValues[1].toInt()
        assertFalse(driver.perform(PhoneAction.Type(s.id, id, "a\nb")).ok)
        assertFalse(driver.perform(PhoneAction.Type(s.id, id, "x".repeat(501))).ok)
        assertTrue(api.acts.isEmpty())
    }

    @Test fun secureKeyboardMakesEveryInputASecretTargetAndTypingIsRefused() {
        api.last = settings().toMutableMap().also { it["secureKeyboard"] = true }
        val s = driver.observe(false, false)
        val id = Regex("""\[(\d+)] 输入框 备注""").find(s.text)!!.groupValues[1].toInt()
        assertTrue("SafetyGate reads this and hands the field to the person", driver.target(s.id, id)!!.isSecretField)
        val r = driver.perform(PhoneAction.Type(s.id, id, "12345678"))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("安全键盘"))
        assertTrue(api.acts.isEmpty())
    }

    @Test fun withoutSecureKeyboardANormalInputIsNotASecretTarget() {
        api.last = settings()
        val s = driver.observe(false, false)
        val id = Regex("""\[(\d+)] 输入框 备注""").find(s.text)!!.groupValues[1].toInt()
        assertFalse(driver.target(s.id, id)!!.isSecretField)
    }

    @Test fun scrollOnASystemPageIsRefusedBothWays() {
        api.last = page("com.ohos.permissionmanager", filler() + "," + button("允许"))
        val s = driver.observe(false, false)
        val r = driver.perform(PhoneAction.Scroll(s.id, null, PhoneAction.Direction.DOWN))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("不在这里滚动"))
        assertTrue(api.acts.isEmpty())
        unlocks = PhoneUnlocks(systemPages = true)
        assertTrue(driver.perform(PhoneAction.Scroll(s.id, null, PhoneAction.Direction.DOWN)).ok)
    }

    @Test fun keysMapToRelayKeyNames() {
        api.last = settings()
        driver.observe(false, false)
        for ((k, n) in listOf(PhoneAction.GlobalKey.BACK to "back", PhoneAction.GlobalKey.HOME to "home", PhoneAction.GlobalKey.RECENTS to "recents", PhoneAction.GlobalKey.NOTIFICATIONS to "notifications")) {
            assertTrue(driver.perform(PhoneAction.Key("s1", k)).ok)
            assertEquals(n, api.acts.last()["name"])
        }
    }

    @Test fun scrollDownMovesTheFingerUp() {
        api.last = settings()
        val s = driver.observe(false, false)
        val r = driver.perform(PhoneAction.Scroll(s.id, null, PhoneAction.Direction.DOWN))
        assertTrue(r.message, r.ok)
        val a = api.acts.single()
        assertEquals("swipe", a["op"])
        assertTrue((a["y1"] as Int) > (a["y2"] as Int))
        assertEquals(a["x1"], a["x2"])
    }

    @Test fun tapXyUsesTheScreenshotScale() {
        api.last = settings() + ("image" to mapOf("mime" to "image/jpeg", "b64" to "AQ=="))
        val s = driver.observe(false, true)
        assertTrue(s.text, s.text.contains("tap_xy 用截图像素（660×1060）"))
        val r = driver.perform(PhoneAction.TapXY(s.id, 100, 200))
        assertTrue(r.message, r.ok)
        assertEquals(200, api.acts.single()["x"])
        assertEquals(400, api.acts.single()["y"])
    }

    @Test fun relayErrorsBecomeSentencesForTheModel() {
        api.last = settings()
        val s = driver.observe(false, false)
        api.actResult = RelayResult.Err(RelayError(RelayCodes.DEVICE_TIMEOUT, "slow"))
        val r = driver.perform(PhoneAction.Key(s.id, PhoneAction.GlobalKey.BACK))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("先用 screen 看一下"))
    }

    private fun info(state: String, locked: Boolean? = false, halted: Boolean = false) =
        RelayResult.Ok(RelayDeviceInfo(halted, RelayDevice(state, "usb", null, null, null, locked, true), 0, null))

    @Test fun halted_unreachable_and_unpaired_relays_block_every_call_without_touching_the_device() {
        api.last = settings()
        api.device = info("ok", halted = true)
        val a = driver.availability()
        assertFalse(a.ok)
        assertTrue(a.reason!!, a.reason.contains("dsh-relay resume"))
        assertTrue(driver.observe(false, false).text.startsWith("屏幕读不到"))
        api.device = RelayResult.Err(RelayError(RelayCodes.UNREACHABLE, "x"))
        assertEquals(RelayStatus.UNREACHABLE, RelayStatus.gate(api).state)
        api.device = RelayResult.Err(RelayError(RelayCodes.UNAUTHORIZED, "x"))
        assertEquals(RelayStatus.UNAUTHORIZED, RelayStatus.gate(api).state)
        api.device = info("unauthorized")
        assertEquals(RelayStatus.DEVICE_UNAUTHORIZED, RelayStatus.gate(api).state)
        api.configured = false
        assertEquals(RelayStatus.UNCONFIGURED, RelayStatus.gate(api).state)
        api.unreadable = true
        assertEquals(RelayStatus.STORE_UNREADABLE, RelayStatus.gate(api).state)
        assertFalse(driver.availability().ok)
        assertEquals(0, api.observes)
        assertTrue(api.acts.isEmpty())
    }

    /** F1：中继缓存里的 offline 不能拦住第一个会让中继去连手机的请求。 */
    @Test fun a_cached_offline_state_does_not_block_the_read_that_makes_the_relay_connect() {
        api.last = settings()
        api.device = info("offline", locked = null)
        assertTrue(driver.availability().ok)
        assertEquals(RelayStatus.DEVICE_OFFLINE, RelayStatus.of(api).state)
        val s = driver.observe(false, false)
        assertFalse(s.text, s.text.startsWith("屏幕读不到"))
        assertEquals(1, api.observes)
    }

    /** F2：中继缓存里的 locked 只用于展示；observe 回来的 locked 才拦。 */
    @Test fun the_lock_flag_of_the_device_cache_does_not_block_but_a_locked_observe_does() {
        api.last = settings()
        api.device = info("ok", locked = true)
        assertTrue(driver.availability().ok)
        assertEquals(RelayStatus.LOCKED, RelayStatus.of(api).state)
        assertFalse(driver.observe(false, false).text.startsWith("屏幕读不到"))
        api.last = settings().toMutableMap().also { it["device"] = mapOf("locked" to true) }
        assertTrue(driver.observe(false, false).text.contains("锁屏"))
    }

    @Test fun testConnectionAsksAgainWhileTheRelayConnectsButNotWhenTheAnswerWillNotChange() {
        val waits = mutableListOf<Long>()
        api.deviceSeq.addAll(listOf(info("offline"), info("connecting"), info("ok")))
        assertEquals(RelayStatus.OK, RelayStatus.settle(api, pause = { waits += it }).state)
        assertEquals(listOf(2_000L, 2_000L), waits)
        waits.clear()
        api.deviceSeq.addAll(List(5) { info("offline") })
        assertEquals(RelayStatus.DEVICE_OFFLINE, RelayStatus.settle(api, pause = { waits += it }).state)
        assertEquals("three more asks at most", 3, waits.size)
        waits.clear(); api.deviceSeq.clear()
        api.device = info("ok", halted = true)
        assertEquals(RelayStatus.HALTED, RelayStatus.settle(api, pause = { waits += it }).state)
        api.device = info("unauthorized")
        assertEquals(RelayStatus.DEVICE_UNAUTHORIZED, RelayStatus.settle(api, pause = { waits += it }).state)
        assertTrue(waits.isEmpty())
    }

    @Test fun stopIsPassedToTheMacAndEndsWhatWasRunning() {
        driver.stop(7)
        assertEquals(listOf(7L), api.stops)
    }

    @Test fun openLaunchesThenWaitsForTheAppToBeInFront() {
        api.screens.addAll(listOf(page("com.other", button("x")), settings()))
        val r = driver.open("com.huawei.hmos.settings")
        assertTrue(r.message, r.ok)
        assertEquals(listOf("com.huawei.hmos.settings"), api.launches)
    }

    @Test fun ownAppIsNotOpenedOrOperated() {
        assertFalse(driver.open("io.github.ccch1mneyyy.dsh_app").ok)
        assertTrue(api.launches.isEmpty())
    }
}

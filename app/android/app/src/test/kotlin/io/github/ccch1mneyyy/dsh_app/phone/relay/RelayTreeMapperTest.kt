package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ElementKind
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenFormat
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenInput
import io.github.ccch1mneyyy.dsh_app.phone.a11y.UiNode
import io.github.ccch1mneyyy.dsh_app.phone.a11y.WindowKind
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RelayTreeMapperTest {
    /** P2-a's contract samples (`tooling/dsh-relay/contract/expected`): the one source both ends read. */
    private fun contract(name: String): Map<String, Any?> {
        val f = File("../../../tooling/dsh-relay/contract/expected/$name")
        assumeTrue("contract sample missing: ${f.absolutePath}", f.isFile)
        @Suppress("UNCHECKED_CAST")
        return Json.parse(f.readText()) as Map<String, Any?>
    }

    /** The element line whose label is exactly [label] (the page title "星闪和蓝牙" does not match). */
    private fun lineOf(text: String, label: String) =
        text.lines().first { Regex("\\[\\d+] \\S+ " + Regex.escape(label) + "( ·|$)").containsMatchIn(it) }

    private fun switchOn(line: String) = line.contains("· 开") || line.contains("· ✓")

    private fun format(s: RelayScreen) = ScreenFormat.format(
        ScreenInput("s1", "设置", s.packageName, s.screen.width, s.screen.height, null, s.windows),
    )

    @Test fun settingsSampleReadsBluetoothAsATappableElement() {
        val screen = RelayTreeMapper.map(contract("synthetic-settings.observe.json"))
        assertEquals("com.huawei.hmos.settings", screen.packageName)
        val out = format(screen)
        assertTrue(out.text, out.text.contains("蓝牙"))
        val bluetooth = out.elements.first { it.label.contains("蓝牙") }
        assertTrue("蓝牙 must be clickable", bluetooth.clickable)
        assertEquals(ElementKind.ITEM, bluetooth.kind)
        // 开关状态可读：「开关 … · 开」或「勾选框 … · ✓」。
        assertTrue(out.text, switchOn(lineOf(out.text, "蓝牙")))
        // 灰掉的行读成不可用。
        assertTrue(out.text, out.text.lines().first { it.contains("飞行模式") }.contains("不可用"))
    }

    @Test fun statusBarWindowIsNotContentAndMainComesLast() {
        val screen = RelayTreeMapper.map(contract("synthetic-settings.observe.json"))
        assertEquals(1, screen.windows.size)
        assertEquals(WindowKind.APP, screen.windows.last().kind)
        assertEquals(1320, screen.screen.width)
        assertEquals(2120, screen.screen.height)
    }

    @Test fun passwordFlagReachesTheFormatterAsAnUnfillableInput() {
        val out = format(RelayTreeMapper.map(contract("synthetic-settings.observe.json")))
        val pw = out.elements.first { it.kind == ElementKind.INPUT && it.password }
        assertTrue(pw.role.contains("不可代填"))
        assertFalse("a note field is not a secret", out.elements.first { it.label == "备注" }.password)
    }

    @Test fun listsAreVerticalAndTextOfAListRowIsItsLabel() {
        val out = format(RelayTreeMapper.map(contract("synthetic-settings.observe.json")))
        assertTrue(out.text, out.text.contains("列表") && out.text.contains("可上下滑"))
        assertTrue(out.text, out.text.contains("WLAN"))
    }

    @Test fun realProbeAppSampleMapsAndFormats() {
        val screen = RelayTreeMapper.map(contract("04-container-probe-home.observe.json"))
        assertEquals("probe.zyt", screen.packageName)
        assertTrue(format(screen).elements.isNotEmpty())
    }

    @Test fun realSettingsHomeReadsBluetoothEntryAsATappableRow() {
        val screen = RelayTreeMapper.map(contract("01-settings-home.observe.json"))
        assertEquals("com.huawei.hmos.settings", screen.packageName)
        val out = format(screen)
        val entry = out.elements.first { it.label.contains("星闪和蓝牙") }
        assertTrue(out.text, entry.clickable)
        // 状态栏（sceneboard）窗口不进内容。
        assertEquals(1, screen.windows.size)
    }

    @Test fun realBluetoothPageHasBluetoothAsATappableRowWithReadableSwitchState() {
        val screen = RelayTreeMapper.map(contract("02-settings-bluetooth.observe.json"))
        val out = format(screen)
        val row = out.elements.first { it.label == "蓝牙" }
        assertTrue(out.text, row.clickable)
        assertTrue(out.text, row.checkable)
        assertTrue(lineOf(out.text, "蓝牙"), switchOn(lineOf(out.text, "蓝牙")))
        // 同一个开关不出现两次。
        assertEquals(out.text, 1, out.elements.count { it.label == "蓝牙" })
    }

    @Test fun realPasswordDialogMarksTheInputAsPasswordAndKeyboardWindowIsNotContent() {
        val screen = RelayTreeMapper.map(contract("03-wifi-password-dialog.observe.json"))
        val out = format(screen)
        assertTrue(out.text, out.elements.any { it.kind == ElementKind.INPUT && it.password })
        assertTrue(screen.windows.none { it.packageName == "com.huawei.hmos.inputmethod" })
    }

    @Test fun secureKeyboardIsRecognisedFromTheInputMethodWindowOrTheRelaysFlag() {
        assertTrue(RelayTreeMapper.map(contract("03-wifi-password-dialog.observe.json")).secureKeyboard)
        assertFalse(RelayTreeMapper.map(contract("02-settings-bluetooth.observe.json")).secureKeyboard)
        val flagged = obs(win("app.main", "app", "[0,0,1000,2000]")).toMutableMap().also { it["secureKeyboard"] = true }
        assertTrue(RelayTreeMapper.map(flagged).secureKeyboard)
        // 别的窗口里碰巧有这四个字不算。
        assertFalse(RelayTreeMapper.map(obs(win("app.main", "app", "[0,0,1000,2000]", """{"cls":"android.widget.TextView","text":"安全键盘","bounds":[0,0,10,10]}"""))).secureKeyboard)
    }

    @Test fun anInputLabelledByANeighbouringTextIsAPasswordInput() {
        val row = """{"cls":"Row","bounds":[0,0,1000,100],"children":[
            {"cls":"android.widget.TextView","text":"登录密码","bounds":[0,0,200,100]},
            {"cls":"android.widget.EditText","bounds":[200,0,1000,100],"flags":["click","editable"]}]}"""
        val s = RelayTreeMapper.map(obs(win("app.main", "app", "[0,0,1000,2000]", row)))
        val out = format(s)
        assertTrue(out.text, out.elements.single { it.kind == ElementKind.INPUT }.password)
        assertTrue(out.text, out.text.contains("不可代填"))
    }

    @Test fun aNeighbouringLabelThatIsNotAPasswordLabelChangesNothing() {
        val row = """{"cls":"Row","bounds":[0,0,1000,100],"children":[
            {"cls":"android.widget.TextView","text":"备注","bounds":[0,0,200,100]},
            {"cls":"android.widget.TextView","text":"忘记密码","bounds":[0,200,300,300],"flags":["click"]},
            {"cls":"android.widget.EditText","bounds":[200,0,1000,100],"flags":["click","editable"]}]}"""
        val out = format(RelayTreeMapper.map(obs(win("app.main", "app", "[0,0,1000,2000]", row))))
        assertFalse(out.elements.single { it.kind == ElementKind.INPUT }.password)
    }

    @Test fun realLockscreenHasNoApplicationWindow() {
        val screen = RelayTreeMapper.map(contract("05-lockscreen.observe.json"))
        assertTrue(screen.windows.none { it.kind == WindowKind.APP })
    }

    @Test fun windowIdsStayTheSameBetweenReads() {
        val a = RelayTreeMapper.map(contract("synthetic-settings.observe.json"))
        val b = RelayTreeMapper.map(contract("synthetic-settings.observe.json"))
        assertEquals(a.windows.map { it.id }, b.windows.map { it.id })
    }

    private fun obs(vararg windows: String, main: Int = 0) =
        Json.parse("""{"display":{"width":1000,"height":2000},"main":$main,"device":{"locked":true,"screenOn":false},"windows":[${windows.joinToString(",")}]}""") as Map<String, Any?>

    private fun win(bundle: String, kind: String, bounds: String, root: String = """{"cls":"android.widget.TextView","text":"x","bounds":$bounds}""") =
        """{"bundle":"$bundle","ability":"A","kind":"$kind","bounds":$bounds,"root":$root}"""

    @Test fun aPopupWindowComesBeforeTheMainOneAndLockedIsReported() {
        val s = RelayTreeMapper.map(obs(win("app.main", "app", "[0,0,1000,2000]"), win("app.dialog", "app", "[100,500,900,1500]"), main = 0))
        assertEquals(listOf("app.dialog", "app.main"), s.windows.map { it.packageName })
        assertEquals(true, s.locked)
        assertEquals(false, s.screenOn)
    }

    @Test fun mainWindowWithoutATreeIsUnreadableNotEmptyAndNotFormatted() {
        val s = RelayTreeMapper.map(obs("""{"bundle":"app.main","kind":"app","bounds":[0,0,1000,2000]}"""))
        assertTrue(s.mainUnreadable)
        assertTrue(s.windows.isEmpty())
        assertEquals("app.main", s.packageName)
    }

    @Test fun anImageIsDecoded() {
        val m = obs(win("app.main", "app", "[0,0,1000,2000]")).toMutableMap()
        m["image"] = mapOf("mime" to "image/jpeg", "b64" to java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), RelayTreeMapper.map(m).imageJpeg)
    }

    private fun nested(levels: Int): String {
        var s = """{"cls":"android.widget.TextView","text":"leaf","bounds":[0,0,10,10]}"""
        repeat(levels) { s = """{"cls":"Column","bounds":[0,0,10,10],"children":[$s]}""" }
        return s
    }

    @Test fun aTreeWithinTheJsonDepthLimitIsMappedWhole() {
        // 解析器上限 64 级；每个节点层占 2 级（对象 + children 数组），28 层节点树安全。
        val ok = RelayTreeMapper.map(obs(win("app.main", "app", "[0,0,1000,2000]", nested(28))))
        assertFalse(ok.truncated)
        var n: UiNode? = ok.windows.last().root
        var depth = 0
        while (n != null) { depth++; n = n.children.firstOrNull() }
        assertEquals(29, depth)
    }
}

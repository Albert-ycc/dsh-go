package io.github.ccch1mneyyy.dsh_app.runtime

import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestPathsTest {
    private val root = Files.createTempDirectory("guest-paths").toFile().resolve("rootfs").also { it.mkdirs() }
    private val bionic = GuestPaths.bionic(root)
    private val phys = root.canonicalPath

    /** Guest-rooted paths left in [text] (not already behind a physical prefix). */
    private fun leftover(text: String): List<String> =
        Regex("(?<![\\w./~-])/(?:root|tmp|opt/dsh|opt/dsh-app|opt/dsh-go|opt/pnpm|etc/dsh-app|var/lib/dsh-app)(?![\\w-])").findAll(text)
            .map { it.value }.toList()

    private fun assertMapped(name: String, text: String) {
        assertEquals("$name still has guest paths", emptyList<String>(), leftover(bionic.text(text)))
        assertEquals("$name is not idempotent", bionic.text(text), bionic.text(bionic.text(text)))
    }

    @Test fun identityLeavesEverythingAlone() {
        val s = "a /root/x /opt/dsh-app/y /tmp/z\n"
        assertSame(s, GuestPaths.IDENTITY.text(s))
        assertTrue(GuestPaths.IDENTITY.isIdentity)
        assertFalse(bionic.isIdentity)
    }

    @Test fun mapsEachPrefixAndKeepsTheLongestMatch() {
        assertEquals("$phys/root/.dsh", bionic.path("/root/.dsh"))
        assertEquals("$phys/opt/dsh-app/phone/relay.cjs", bionic.path("/opt/dsh-app/phone/relay.cjs"))
        assertEquals("$phys/opt/dsh-go/dsh-go-bridge", bionic.path("/opt/dsh-go/dsh-go-bridge"))
        assertEquals("$phys/opt/dsh/node_modules", bionic.path("/opt/dsh/node_modules"))
        assertEquals("$phys/etc/dsh-app/dsh-web.patch.yml", bionic.path("/etc/dsh-app/dsh-web.patch.yml"))
        assertEquals("$phys/var/lib/dsh-app/phone/bridge.json", bionic.path("/var/lib/dsh-app/phone/bridge.json"))
        assertEquals("$phys/tmp/dsh-web.pid", bionic.path("/tmp/dsh-web.pid"))
        assertEquals("$phys/root", bionic.path("/root"))
    }

    @Test fun leavesLookalikesAndOtherPathsAlone() {
        for (s in listOf("/usr/local/bin/dsh", "/sdcard/Download", "/rooted", "/opt/dshx", "/opt/dsh-appx/a", "/tmpfs", "x/root/y", "~/root", "https://h/root/a", "/system/bin/sh")) {
            assertEquals(s, bionic.text(s))
        }
        assertEquals("`$phys/root` and ($phys/tmp)", bionic.text("`/root` and (/tmp)"))
    }

    @Test fun isIdempotentEvenWhenTheRootfsStartsWithAMappedPrefix() {
        for (dir in listOf("/tmp/zy/rootfs", "/root/r", "/opt/dsh-app/rootfs")) {
            val p = GuestPaths.bionic(File(dir))
            val once = p.text("cd /root/workspace; cat /tmp/a /opt/dsh/b")
            assertEquals(p.text("cd /root/workspace; cat /tmp/a /opt/dsh/b"), once)
            assertEquals(once, p.text(once))
            assertTrue(once.startsWith("cd ${p.rootfs}/root/workspace"))
        }
    }

    @Test fun rootfsPathWithRegexMetacharactersIsTakenLiterally() {
        val p = GuestPaths.bionic(File("/data/user/0/a.b+c(1)/rootfs"))
        assertEquals("/data/user/0/a.b+c(1)/rootfs/root/x", p.text("/root/x"))
        assertEquals("/data/user/0/a.b+c(1)/rootfs/root/x", p.text(p.text("/root/x")))
    }

    // ── 各 guest 文本 ─────────────────────────────────────────────────────────────

    @Test fun phoneOverlayAndRelayUsePhysicalPaths() {
        val yaml = PhoneGuest.patchYaml(bionic)
        assertTrue(yaml.contains("- $phys/opt/dsh-app/phone/relay.cjs\n"))
        assertTrue(yaml.contains("- $phys/var/lib/dsh-app/phone/bridge.json\n"))
        assertMapped("phone yaml", PhoneGuest.PATCH_YAML)
        assertMapped("relay", PhoneGuest.RELAY_JS)
        assertEquals(PhoneGuest.PATCH_YAML, PhoneGuest.patchYaml())
        val rootfs = Files.createTempDirectory("phone-guest").toFile()
        try {
            PhoneGuest.writeFiles(rootfs, bionic)
            assertEquals(yaml, File(rootfs, "opt/dsh-app/phone/phone.patch.yml").readText())
            assertTrue(File(rootfs, "opt/dsh-app/phone/relay.cjs").readText().contains("'$phys/var/lib/dsh-app/phone/bridge.json'"))
            PhoneGuest.writeFiles(rootfs)
            assertEquals(PhoneGuest.PATCH_YAML, File(rootfs, "opt/dsh-app/phone/phone.patch.yml").readText())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test fun searchOverlayUsesPhysicalPaths() {
        assertTrue(SessionSearchGuest.patchYaml(bionic).contains("    path: $phys/root/.dsh/session-search.sqlite\n"))
        assertMapped("search yaml", SessionSearchGuest.PATCH_YAML)
        val rootfs = Files.createTempDirectory("search-guest").toFile()
        try {
            assertTrue(SessionSearchGuest.writeFiles(rootfs, bionic))
            assertEquals(SessionSearchGuest.patchYaml(bionic), SessionSearchGuest.file(rootfs).readText())
            assertTrue(SessionSearchGuest.writeFiles(rootfs))
            assertEquals(SessionSearchGuest.PATCH_YAML, SessionSearchGuest.file(rootfs).readText())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test fun authOverlayUsesPhysicalPaths() {
        assertTrue(AppAuthGuest.patchYaml(bionic).contains("      name: $phys/opt/dsh-app/auth/index.mjs\n"))
        assertMapped("auth yaml", AppAuthGuest.PATCH_YAML)
        assertEquals(AppAuthGuest.PATCH_YAML, AppAuthGuest.patchYaml())
    }

    @Test fun speechOverlayHasNoGuestPathAndStaysIdentical() {
        assertEquals(SpeechGuest.PATCH_YAML, SpeechGuest.patchYaml(bionic))
    }

    @Test fun safeModeWritesPhysicalPathsEverywhere() {
        val args = SafeMode.profileArg("x ${SafeMode.PROFILE_ARGS_PLACEHOLDER} y", safe = true, paths = bionic)!!
        assertEquals("x --profile dsh-app-safe --from-default-profile web --patch $phys/var/lib/dsh-app/safe-mode/overlay.patch.yml y", args)
        assertEquals("x web y", SafeMode.profileArg("x ${SafeMode.PROFILE_ARGS_PLACEHOLDER} y", safe = false, paths = bionic))
        assertEquals(SafeMode.GUEST_ENV, SafeMode.guestEnv())
        assertEquals("$phys/var/lib/dsh-app/safe-mode/agents", SafeMode.guestEnv(bionic)["DSH_AGENTS_HOME"])

        val rootfs = Files.createTempDirectory("safe-guest").toFile()
        val p = GuestPaths.bionic(rootfs)
        try {
            val briefing = SafeMode.Briefing(null, SafePolicy.REASON_MANUAL, listOf("/root/.dsh/.env → /root/.dsh/.env.off-1"), null, emptyList(), emptyMap(), emptyList())
            SafeMode.prepare(rootfs, briefing, p)
            val dir = File(rootfs, "var/lib/dsh-app/safe-mode")
            for (name in listOf("agents/AGENTS.md", "bin/dsh-app-plugins", "overlay.patch.yml")) {
                val text = File(dir, name).readText()
                assertEquals("$name still has guest paths", emptyList<String>(), leftoverIn(text, p))
            }
            val tool = File(dir, "bin/dsh-app-plugins").readText()
            assertTrue(tool.contains("process.env.DSH_HOME || '${p.rootfs}/root/.dsh'"))
            assertTrue(tool.contains("process.env.DSH_APP_SAFE_DIR || '${p.rootfs}/var/lib/dsh-app/safe-mode'"))
            assertTrue(tool.contains("process.env.DSH_APP_PATCH || '${p.rootfs}/etc/dsh-app/dsh-web.patch.yml'"))
            val agents = File(dir, "agents/AGENTS.md").readText()
            assertTrue(agents.contains("${p.rootfs}/root/.dsh/profiles/web/package.json"))
            assertTrue(agents.contains("${p.rootfs}/tmp/dsh-web.pid"))
            SafeMode.prepare(rootfs, briefing)
            assertEquals(SafeMode.PLUGINS_SCRIPT, File(dir, "bin/dsh-app-plugins").readText())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    /** Guest paths in [text] that are not inside the physical prefix of [p]. */
    private fun leftoverIn(text: String, p: GuestPaths): List<String> = leftover(text.replace(p.rootfs!!, "ROOTFS"))

    @Test fun bionicAgentsMdUsesPhysicalPathsAndIsIdempotent() {
        val rootfs = Files.createTempDirectory("agents-bionic").toFile()
        try {
            val p = GuestPaths.bionic(rootfs)
            val file = File(rootfs, "root/.dsh/AGENTS.md")
            GuestSetup.writeAgentsMd(rootfs, phoneStorage = true, phoneControl = true, bionic = true, paths = p)
            val text = file.readText()
            assertTrue(text.contains("`${p.rootfs}/root/workspace`"))
            assertTrue(text.contains("`${p.rootfs}/root/.dsh/dsh-app/diagnostics.md`"))
            assertEquals(emptyList<String>(), leftoverIn(text, p))
            assertEquals(text, p.text(text))
            val mtime = file.lastModified()
            GuestSetup.writeAgentsMd(rootfs, phoneStorage = true, phoneControl = true, bionic = true, paths = p)
            assertEquals(text, file.readText())
            assertEquals(mtime, file.lastModified())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    // ── bionic AGENTS_MD 与降级矩阵（P1 第 6 节）─────────────────────────────────────

    @Test fun bionicAgentsMdMatchesTheDegradationMatrix() {
        val text = GuestSetup.BIONIC_AGENTS_MD
        // 没有这些工具，且明确告知模型
        for (tool in listOf("git", "python3", "curl", "wget", "perl", "sqlite3")) assertTrue(tool, text.contains(tool))
        assertTrue(text.contains("没有 git、python / python3、curl"))
        // 联网替代、shell、沙箱
        assertTrue(text.contains("node -e \"fetch("))
        assertTrue(text.contains("/system/bin/sh"))
        assertTrue(text.contains("sandbox_permissions: \"danger-full-access\""))
        // rg 可能缺席时的退路
        assertTrue(text.contains("rg") && text.contains("grep -r"))
        // 插件只收 npm 来源
        assertTrue(text.contains("插件只接受 npm 来源"))
        // 不得再声称 proot / Ubuntu 环境里的东西可用
        assertFalse(text.contains("已预装"))
        assertFalse(text.contains("apt-get install"))
        assertFalse(text.contains("Ubuntu 24.04"))
        // 共用的界面与技能段保留
        assertTrue(text.contains("## 界面与技能"))
        assertTrue(text.contains("dsh-go-support"))
        assertTrue(text.startsWith("<!-- dsh-app:managed"))
        assertTrue(text.contains("{STORAGE_LINE}"))
    }
}

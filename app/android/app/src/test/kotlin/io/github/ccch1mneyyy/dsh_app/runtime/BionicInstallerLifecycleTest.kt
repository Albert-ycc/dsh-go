package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** bionic 安装生命周期：升级保留 /root、解包中途失败后再装、pid 包装器、占位符报错、安全模式脚本 shebang、只打 bionic 包的族决策。 */
class BionicInstallerLifecycleTest {
    private val oldVersion = "bionic-r1-dsh0.2.1-rc.4-node24.22.1-b25d9c08"
    private val newVersion = "bionic-r1-dsh0.2.1-rc.4-node24.22.1-b61cbc5e2152"

    private fun tar(vararg files: Pair<String, String>, truncateAt: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        fun octal(v: Long, len: Int) = v.toString(8).padStart(len - 1, '0') + "\u0000"
        for ((name, body) in files) {
            val h = ByteArray(512)
            fun put(off: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(h, off)
            put(0, name)
            put(100, octal(0x1A4, 8)); put(108, octal(0, 8)); put(116, octal(0, 8))
            put(124, octal(body.length.toLong(), 12)); put(136, octal(0, 12))
            put(148, "        "); h[156] = '0'.code.toByte(); put(257, "ustar\u000000")
            put(148, octal(h.sumOf { it.toInt() and 0xFF }.toLong(), 7).dropLast(1) + "\u0000 ")
            out.write(h)
            val data = body.toByteArray()
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        val all = out.toByteArray()
        return if (truncateAt != null) all.copyOf(truncateAt) else all
    }

    private fun installed(base: File, version: String): Boolean =
        File(base, "rootfs").isDirectory && File(base, "rootfs.version").let { it.isFile && it.readText().trim() == version }

    /** install() 的骨架：清 staging → 解包 → activateRootfs（与 RuntimeInstaller.install 同序）。 */
    private fun install(base: File, archive: ByteArray, version: String) {
        val staging = File(base, "staging")
        staging.deleteRecursivelySafely()
        staging.mkdirs()
        TarExtractor(staging) {}.extractDecoded(ByteArrayInputStream(archive))
        activateRootfs(base, version, sync = {}, bionic = true)
    }

    @Test fun upgradeKeepsRootAndSwapsRootfs() {
        val base = Files.createTempDirectory("review-upgrade").toFile()
        try {
            File(base, "rootfs/opt/dsh/old.js").apply { parentFile.mkdirs() }.writeText("old")
            File(base, "rootfs/root/.dsh/profiles/web/package.json").apply { parentFile.mkdirs() }.writeText("{}")
            File(base, "rootfs/root/workspace/notes.md").apply { parentFile.mkdirs() }.writeText("keep me")
            File(base, "rootfs.version").writeText(oldVersion)
            assertTrue(installed(base, oldVersion))

            install(base, tar("opt/dsh/new.js" to "new", "root/seed.txt" to "shipped seed"), newVersion)

            assertTrue(installed(base, newVersion))
            assertEquals("keep me", File(base, "rootfs/root/workspace/notes.md").readText())
            assertEquals("{}", File(base, "rootfs/root/.dsh/profiles/web/package.json").readText())
            assertTrue(File(base, "rootfs/opt/dsh/new.js").isFile)
            assertFalse(File(base, "rootfs/opt/dsh/old.js").exists())
            // 新包里 root/ 下的内容在升级时被丢弃（home 整个替换成旧的）：当前 tar 不带 root/，记录事实。
            assertFalse(File(base, "rootfs/root/seed.txt").exists())
            for (gone in listOf("staging", "home.carry", "rootfs.old")) assertFalse(gone, File(base, gone).exists())
            assertTrue(File(base, "rootfs/tmp").isDirectory)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun extractionKilledMidwayThenInstallAgainSucceeds() {
        val base = Files.createTempDirectory("review-midway").toFile()
        try {
            File(base, "rootfs/opt/dsh/old.js").apply { parentFile.mkdirs() }.writeText("old")
            File(base, "rootfs/root/workspace/notes.md").apply { parentFile.mkdirs() }.writeText("keep me")
            File(base, "rootfs.version").writeText(oldVersion)

            val full = tar("opt/dsh/a.js" to "A".repeat(600), "opt/dsh/b.js" to "B".repeat(600))
            // 第二个文件的正文中间被截断：与解包时磁盘满 / 进程被杀等价（流在条目中途终止）
            try {
                install(base, full.copyOf(512 + 1024 + 512 + 100), newVersion)
                fail("truncated archive must throw")
            } catch (_: java.io.IOException) {
            }
            // 半套 staging 留在盘上，但旧 rootfs 与旧版本标记原样，不会被当成已装好新版本
            assertTrue(File(base, "staging/opt/dsh/a.js").isFile)
            assertFalse(installed(base, newVersion))
            assertTrue(installed(base, oldVersion))
            assertEquals("keep me", File(base, "rootfs/root/workspace/notes.md").readText())

            install(base, full, newVersion)
            assertTrue(installed(base, newVersion))
            assertEquals("B".repeat(600), File(base, "rootfs/opt/dsh/b.js").readText())
            assertEquals("keep me", File(base, "rootfs/root/workspace/notes.md").readText())
            assertFalse(File(base, "staging").exists())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun activationKilledAfterSwapButBeforeVersionMarkerReadsAsNotInstalled() {
        val base = Files.createTempDirectory("review-nomarker").toFile()
        try {
            // activateRootfs 已删版本标记、换好 rootfs，写标记前被杀：新 rootfs 在位，标记缺失
            File(base, "rootfs/opt/dsh/new.js").apply { parentFile.mkdirs() }.writeText("new")
            File(base, "rootfs/root/workspace/notes.md").apply { parentFile.mkdirs() }.writeText("keep me")
            assertFalse(installed(base, newVersion))
            install(base, tar("opt/dsh/new.js" to "new"), newVersion)
            assertTrue(installed(base, newVersion))
            assertEquals("keep me", File(base, "rootfs/root/workspace/notes.md").readText())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun pidWrapperPidIsTheExecdProcess() {
        val sh = File("/bin/sh")
        if (!sh.canExecute()) return
        val root = Files.createTempDirectory("review-pid").toFile().resolve("rootfs").also { File(it, "tmp").mkdirs() }
        val plan = BionicLaunch.plan(
            root, "/nowhere", listOf("/bin/sleep", "30"), emptyMap(), emptyMap(), 3080, false, emptyList(),
            androidRoot = "/system", androidData = "/data",
        )
        val argv = plan.argv.toMutableList().also { it[0] = "/bin/sh" }
        val p = ProcessBuilder(argv).directory(File(root.canonicalPath)).start()
        val pid = Process::class.java.getMethod("pid").invoke(p) as Long
        try {
            val pidFile = File(root, BionicLaunch.PID_FILE)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!(pidFile.isFile && pidFile.length() > 0) && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(pid, pidFile.readText().trim().toLong())
            // exec 后命令行是 sleep 而不是 sh
            val comm = ProcessBuilder("/bin/ps", "-o", "comm=", "-p", pid.toString()).start()
                .inputStream.bufferedReader().readText().trim()
            assertTrue(comm, comm.endsWith("sleep"))
        } finally {
            p.destroyForcibly()
        }
    }

    @Test fun unknownPlaceholdersFailWithAStableError() {
        val root = Files.createTempDirectory("review-ph").toFile().resolve("rootfs").also { it.mkdirs() }
        for (bad in listOf("{TMP}", "{FILES_DIR}", "{TYPO}")) {
            try {
                BionicLaunch.plan(root, "/lib", listOf("{NATIVE_LIB_DIR}/n", "--x", bad), emptyMap(), emptyMap(), 3080, false, emptyList())
                fail("$bad must be rejected")
            } catch (e: IllegalStateException) {
                assertTrue(e.message, e.message!!.contains("unknown placeholder"))
            }
        }
    }

    @Test fun prepareCreatesTmpForThePidFile() {
        val root = Files.createTempDirectory("review-tmp").toFile().resolve("rootfs").also { it.mkdirs() }
        BionicLaunch.prepare(root, "/lib/libdsh_node.so")
        assertTrue(File(root, "tmp").isDirectory)
    }

    @Test fun safeModeHelperShebangIsNotUsrBinEnvUnderBionic() {
        val root = Files.createTempDirectory("review-shebang").toFile().resolve("rootfs").also { it.mkdirs() }
        SafeMode.prepare(root, SafeMode.Briefing(null, null, emptyList(), null, emptyList(), emptyMap(), emptyList(), nodePath = "/lib/libdsh_node.so"), GuestPaths.bionic(root))
        val tool = File(root, "var/lib/dsh-app/safe-mode/bin/dsh-app-plugins")
        val first = tool.readLines().first()
        assertFalse(first, first.startsWith("#!/usr/bin/env"))
        assertEquals("#!${root.canonicalPath}/${BionicLaunch.BIN_DIR}/node", first)
    }

    @Test fun aBionicOnlyApkNeverDecidesGlibc() {
        for (domain in listOf(null, "u:r:untrusted_app:s0")) for (probe in listOf(null, EngineFamily.Probe.OPEN, EngineFamily.Probe.UNKNOWN)) {
            for (choice in listOf(null, "glibc")) {
                val d = EngineFamily.decide(choice, null, null, true, domain, probe, glibcBundled = false)
                assertEquals(EngineFamily.Decision(EngineFamily.Family.BIONIC, "onlyBundled"), d)
            }
        }
        assertEquals(EngineFamily.Family.GLIBC, EngineFamily.decide(null, null, "rt", true, null, null, glibcBundled = true).family)
    }
}

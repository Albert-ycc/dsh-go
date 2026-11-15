package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pins P1 3.3-3.5: what the bionic launch runs, with which environment, and from where. */
class BionicLaunchTest {
    private val root = Files.createTempDirectory("bionic-launch").toFile().resolve("rootfs").also { it.mkdirs() }
    private val r = root.canonicalPath
    private val lib = "/data/app/~~x/io.github.ccch1mneyyy.dsh_app-y/lib/arm64"

    /** `launch.argv` / `launch.env` exactly as P1 3.3 lists them. */
    private val argv = listOf(
        "{NATIVE_LIB_DIR}/libdsh_node.so",
        "--expose-internals",
        "--import", "{ROOTFS}/opt/dsh-go/bionic/preload.mjs",
        "{ROOTFS}/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "{PROFILE_ARGS}",
        "--patch", "{ROOTFS}/etc/dsh-app/dsh-web.patch.yml",
        "{EXTRA_PATCHES}",
        "--host", "127.0.0.1", "--port", "{PORT}", "--no-open",
    )
    private val launchEnv = linkedMapOf(
        "NODE_OPTIONS" to "--dns-result-order=ipv4first --import {ROOTFS}/opt/dsh-go/bionic/link-fallback.mjs",
        "pnpm_config_registry" to "https://registry.npmmirror.com/",
        "pnpm_config_package_import_method" to "copy",
        "npm_config_package_import_method" to "copy",
        "LANG" to "C.UTF-8",
        "TERM" to "xterm-256color",
    )

    private fun plan(
        safe: Boolean = false,
        extra: List<String> = emptyList(),
        caller: Map<String, String> = mapOf("TZ" to "Asia/Shanghai", "UV_THREADPOOL_SIZE" to "4"),
        launch: List<String> = argv,
    ) = BionicLaunch.plan(root, lib, launch, launchEnv, caller, 3080, safe, extra, androidRoot = "/system", androidData = "/data")

    @Test fun argvIsTheShellPidWrapperThenNodeWithExposeInternalsFirst() {
        val expected = listOf(
            "/system/bin/sh", "-c", "echo \$\$ > \"\$0\" && exec \"\$@\"", "$r/tmp/dsh-web.pid",
            "$lib/libdsh_node.so",
            "--expose-internals",
            "--import", "$r/opt/dsh-go/bionic/preload.mjs",
            "$r/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js",
            "web",
            "--patch", "$r/etc/dsh-app/dsh-web.patch.yml",
            "--host", "127.0.0.1", "--port", "3080", "--no-open",
        )
        assertEquals(expected, plan().argv)
    }

    @Test fun exposeInternalsIsInArgvAndNeverInNodeOptions() {
        val p = plan()
        assertTrue(p.argv.indexOf("--expose-internals") < p.argv.indexOf("web"))
        assertFalse(p.environment.getValue("NODE_OPTIONS").contains("expose-internals"))
    }

    @Test fun environmentIsExactlyTheFixedSetThenLaunchEnvThenCallerEnv() {
        val expected = linkedMapOf(
            "HOME" to "$r/root",
            "DSH_HOME" to "$r/root/.dsh",
            "TMPDIR" to "$r/tmp",
            "PATH" to "$r/opt/dsh-app/bionic/bin:/system/bin:/system/xbin",
            "SHELL" to "/system/bin/sh",
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
            "UV_USE_IO_URING" to "0",
            "NODE_OPTIONS" to "--dns-result-order=ipv4first --import $r/opt/dsh-go/bionic/link-fallback.mjs",
            "pnpm_config_registry" to "https://registry.npmmirror.com/",
            "pnpm_config_package_import_method" to "copy",
            "npm_config_package_import_method" to "copy",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "TZ" to "Asia/Shanghai",
            "UV_THREADPOOL_SIZE" to "4",
            "DSH_GO_RUNTIME_ROOT" to r,
        )
        assertEquals(expected, plan().environment)
    }

    /** PerformanceRules builds NODE_OPTIONS from the raw launch.env value plus a heap limit; the placeholder must still expand. */
    @Test fun callerEnvDerivedFromLaunchEnvGetsItsPlaceholdersExpanded() {
        val raw = launchEnv.getValue("NODE_OPTIONS") + " --max-old-space-size=2048"
        val env = plan(caller = mapOf("NODE_OPTIONS" to raw)).environment
        assertEquals("--dns-result-order=ipv4first --import $r/opt/dsh-go/bionic/link-fallback.mjs --max-old-space-size=2048", env["NODE_OPTIONS"])
        assertFalse(env.values.any { it.contains("{ROOTFS}") })
    }

    @Test fun noLibraryPathsAndNoProotVariablesLeakIn() {
        val keys = plan().environment.keys
        for (bad in listOf("LD_LIBRARY_PATH", "LD_PRELOAD", "NARB_DISABLE_NATIVE_CACHE", "PROOT_TMP_DIR", "PROOT_LOADER")) {
            assertFalse("$bad must not be set", bad in keys)
        }
    }

    @Test fun callerEnvironmentWinsOverLaunchEnvButNotOverTheOwnershipMarker() {
        val p = plan(caller = mapOf("NODE_OPTIONS" to "--dns-result-order=ipv4first --max-old-space-size=1024", "DSH_GO_RUNTIME_ROOT" to "/elsewhere"))
        assertEquals("--dns-result-order=ipv4first --max-old-space-size=1024", p.environment["NODE_OPTIONS"])
        assertEquals(r, p.environment["DSH_GO_RUNTIME_ROOT"])
    }

    @Test fun cwdIsRootUnderTheRootfs() {
        assertEquals(File(r, "root"), plan().cwd)
    }

    @Test fun extraPatchesExpandInPlaceAsPatchPairs() {
        val p = plan(extra = listOf("$r/opt/dsh-app/speech.patch.yml", "$r/opt/dsh-app/phone/phone.patch.yml"))
        val web = p.argv.indexOf("$r/etc/dsh-app/dsh-web.patch.yml")
        assertEquals(
            listOf("--patch", "$r/opt/dsh-app/speech.patch.yml", "--patch", "$r/opt/dsh-app/phone/phone.patch.yml", "--host"),
            p.argv.subList(web + 1, web + 6),
        )
    }

    @Test fun safeModeSelectsTheRescueProfileWithPhysicalOverlayAndNoExtraPatches() {
        val p = plan(safe = true)
        val at = p.argv.indexOf("--profile")
        assertEquals(
            listOf("--profile", "dsh-app-safe", "--from-default-profile", "web", "--patch", "$r/var/lib/dsh-app/safe-mode/overlay.patch.yml",
                "--patch", "$r/etc/dsh-app/dsh-web.patch.yml"),
            p.argv.subList(at, at + 8),
        )
        assertFalse(p.argv.contains("web") && p.argv.indexOf("web") < at)
    }

    @Test fun safeModeRefusesATemplateWithoutTheProfilePlaceholder() {
        try {
            plan(safe = true, launch = argv - "{PROFILE_ARGS}")
            fail("expected a refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("safe mode"))
        }
    }

    @Test fun overlaysAreChosenInSpeechAuthSearchPhoneOrderAndOnlyWhenTheirFilesExist() {
        val paths = GuestPaths.bionic(root)
        assertEquals(emptyList<String>(), BionicLaunch.extraPatches(root, safe = false, phone = false, paths = paths))
        for (guest in listOf(SessionSearchGuest.PATCH, AppAuthGuest.PATCH, SpeechGuest.PATCH)) {
            val f = File(root, guest.removePrefix("/")); f.parentFile.mkdirs(); f.writeText("- id: x\n")
        }
        assertEquals(
            listOf("$r/opt/dsh-app/speech.patch.yml", "$r/opt/dsh-app/auth/auth.patch.yml", "$r/opt/dsh-app/search.patch.yml", "$r/opt/dsh-app/phone/phone.patch.yml"),
            BionicLaunch.extraPatches(root, safe = false, phone = true, paths = paths),
        )
        assertEquals(emptyList<String>(), BionicLaunch.extraPatches(root, safe = true, phone = true, paths = paths))
    }

    @Test fun prepareWritesWrappersAndTheWebOverlayAndIsIdempotent() {
        val node = "$lib/libdsh_node.so"
        BionicLaunch.prepare(root, node)
        val bin = File(r, "opt/dsh-app/bionic/bin")
        assertEquals("#!/system/bin/sh\nexec '$node' \"\$@\"\n", File(bin, "node").readText())
        assertEquals("#!/system/bin/sh\nexec /system/bin/sh \"\$@\"\n", File(bin, "bash").readText())
        assertEquals("#!/system/bin/sh\nexec node '$r/opt/pnpm/node_modules/pnpm/bin/pnpm.mjs' \"\$@\"\n", File(bin, "pnpm").readText())
        assertEquals("#!/system/bin/sh\nexec node '$r/opt/pnpm/node_modules/pnpm/bin/pnpx.mjs' \"\$@\"\n", File(bin, "pnpx").readText())
        assertEquals("#!/system/bin/sh\nexec node --expose-internals '$r/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js' \"\$@\"\n", File(bin, "dsh").readText())
        for (name in listOf("node", "bash", "pnpm", "pnpx", "dsh")) assertTrue("$name executable", File(bin, name).canExecute())
        val yaml = File(r, "etc/dsh-app/dsh-web.patch.yml").readText()
        assertTrue(yaml.contains("      name: $r/opt/dsh-go/dsh-go-bridge/lib/index.js\n"))
        assertTrue(yaml.contains("- id: plugin-manager"))
        val before = File(bin, "node").lastModified()
        BionicLaunch.prepare(root, node)
        assertEquals(yaml, File(r, "etc/dsh-app/dsh-web.patch.yml").readText())
        assertEquals(before, File(bin, "node").lastModified())
        // An App update moves nativeLibraryDir: the wrapper follows.
        BionicLaunch.prepare(root, "/data/app/~~z/lib/arm64/libdsh_node.so")
        assertTrue(File(bin, "node").readText().contains("/data/app/~~z/"))
    }

    @Test fun webOverlayLeavesNoGuestPaths() {
        val text = BionicLaunch.webPatchYaml(GuestPaths.bionic(root))
        assertNull(Regex("(?<![\\w./~-])/opt/dsh-go").find(text))
    }

    @Test fun webOverlayShortensModelStreamIdle() {
        val text = BionicLaunch.webPatchYaml(GuestPaths.bionic(root))
        for (id in listOf("llm-deepseek", "llm-deepseek-account")) {
            assertTrue(id, text.contains("- id: $id\n  config:\n    streamIdleTimeoutMs: 30000\n"))
        }
    }

    @Test fun bionicInstallKeepsTheHomeAndHasNoProotMountPoints() {
        val base = Files.createTempDirectory("bionic-install").toFile()
        try {
            File(base, "rootfs/root/workspace/notes").apply { parentFile!!.mkdirs() }.writeText("notes")
            File(base, "staging/opt/dsh").mkdirs()
            activateRootfs(base, "bionic-r1", sync = {}, bionic = true)
            assertEquals("notes", File(base, "rootfs/root/workspace/notes").readText())
            assertEquals("bionic-r1", File(base, "rootfs.version").readText())
            assertTrue(File(base, "rootfs/tmp").isDirectory)
            for (mount in listOf("dev", "proc", "sys")) assertFalse(mount, File(base, "rootfs/$mount").exists())
            // glibc keeps them.
            File(base, "staging").mkdirs()
            activateRootfs(base, "r2", sync = {})
            for (mount in listOf("dev", "proc", "sys")) assertTrue(mount, File(base, "rootfs/$mount").isDirectory)
        } finally {
            base.deleteRecursively()
        }
    }
}

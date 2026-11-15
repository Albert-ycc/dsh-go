package io.github.ccch1mneyyy.dsh_app.runtime

import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest
import java.io.File

/**
 * Launch plan for the bionic engine: the Node executable from the APK's native library directory,
 * run as a plain child process of the app in a physical-path world (no `/root`, no PRoot).
 * Pure functions over files and strings, so the JVM tests can pin argv, env and cwd (P1 3.3-3.5).
 */
object BionicLaunch {
    data class Plan(val argv: List<String>, val environment: Map<String, String>, val cwd: File)

    const val PID_FILE = "tmp/dsh-web.pid"
    const val BIN_DIR = "opt/dsh-app/bionic/bin"
    const val WEB_PATCH = "/etc/dsh-app/dsh-web.patch.yml"
    const val DSH_GO_BRIDGE = "/opt/dsh-go/dsh-go-bridge"
    const val PROFILE_ARGS = "{PROFILE_ARGS}"
    const val EXTRA_PATCHES = "{EXTRA_PATCHES}"
    private val LEFTOVER_PLACEHOLDER = Regex("\\{[A-Z_]+\\}")
    private const val SHELL = "/system/bin/sh"

    /** Idle limit for DeepSeek model streams (see [webPatchYaml]). */
    const val MODEL_STREAM_IDLE_MS = 30_000

    /** `$0` is the pid file, `$@` the engine command: no shell text is built from paths, so nothing needs escaping. */
    private const val PID_WRAPPER = "echo \$\$ > \"\$0\" && exec \"\$@\""

    /**
     * [launchArgv] / [launchEnv] are the manifest's `launch` section. [callerEnv] is what the controller
     * composed (time zone, the Dart caller's variables, the performance policy's heap and thread-pool
     * values, safe mode's extras). Order, later wins: fixed variables, [launchEnv], [callerEnv]; only
     * the process-ownership marker is set last, because the cleanup sweep finds the engine by it.
     */
    fun plan(
        rootfs: File,
        nativeLibDir: String,
        launchArgv: List<String>,
        launchEnv: Map<String, String>,
        callerEnv: Map<String, String>,
        port: Int,
        safe: Boolean,
        extraPatches: List<String>,
        androidRoot: String = System.getenv("ANDROID_ROOT") ?: "/system",
        androidData: String = System.getenv("ANDROID_DATA") ?: "/data",
    ): Plan {
        val root = rootfs.canonicalPath
        val paths = GuestPaths.bionic(rootfs)
        val vars = mapOf("{NATIVE_LIB_DIR}" to nativeLibDir, "{ROOTFS}" to root, "{PORT}" to port.toString())
        fun expand(s: String) = vars.entries.fold(s) { acc, (k, v) -> acc.replace(k, v) }

        check(launchArgv.isNotEmpty()) { "runtime manifest has no launch.argv" }
        check(!safe || PROFILE_ARGS in launchArgv) { "safe mode: this runtime's launch command has no profile placeholder" }
        val engine = ArrayList<String>()
        for (arg in launchArgv) when (arg) {
            PROFILE_ARGS -> engine += if (safe) paths.text(SafeMode.SAFE_PROFILE_ARGS).split(' ') else listOf(SafeMode.NORMAL_PROFILE_ARGS)
            EXTRA_PATCHES -> extraPatches.forEach { engine += "--patch"; engine += it }
            else -> engine += expand(arg)
        }
        engine.firstOrNull { LEFTOVER_PLACEHOLDER.containsMatchIn(it) }?.let { error("runtime manifest: unknown placeholder in launch.argv: $it") }
        val argv = listOf(SHELL, "-c", PID_WRAPPER, File(root, PID_FILE).path) + engine

        val env = LinkedHashMap<String, String>()
        env["HOME"] = "$root/root"
        env["DSH_HOME"] = "$root/root/.dsh"
        env["TMPDIR"] = "$root/tmp"
        env["PATH"] = "$root/$BIN_DIR:/system/bin:/system/xbin"
        env["SHELL"] = SHELL
        env["ANDROID_ROOT"] = androidRoot
        env["ANDROID_DATA"] = androidData
        // libuv 1.52 already skips io_uring in Android builds; kept so a Node upgrade cannot change that.
        env["UV_USE_IO_URING"] = "0"
        launchEnv.forEach { (k, v) ->
            env[k] = expand(v).also { check(!LEFTOVER_PLACEHOLDER.containsMatchIn(it)) { "runtime manifest: unknown placeholder in launch.env $k" } }
        }
        // callerEnv 里有从 launch.env 派生的值（PerformanceRules 在 NODE_OPTIONS 后面加堆上限），同样展开占位符。
        callerEnv.forEach { (k, v) -> env[k] = expand(v) }
        env["DSH_GO_RUNTIME_ROOT"] = root
        return Plan(argv, env, File(root, "root"))
    }

    /** The `--patch` arguments for the app's optional overlays, in the order the PRoot template ends up with. */
    fun extraPatches(rootfs: File, safe: Boolean, phone: Boolean, paths: GuestPaths): List<String> {
        if (safe) return emptyList()
        return buildList {
            if (SpeechGuest.file(rootfs).isFile) add(paths.path(SpeechGuest.PATCH))
            if (AppAuthGuest.file(rootfs).isFile) add(paths.path(AppAuthGuest.PATCH))
            if (SessionSearchGuest.file(rootfs).isFile) add(paths.path(SessionSearchGuest.PATCH))
            if (phone) add(paths.path(PhoneGuest.PATCH))
        }
    }

    /**
     * Idempotently writes what the plan refers to: the PATH wrappers (`nativeLibraryDir` carries a
     * random segment that changes with the APK, so every start rewrites them) and the web overlay.
     */
    fun prepare(rootfs: File, nodePath: String) {
        val root = rootfs.canonicalPath
        val bin = File(root, BIN_DIR)
        check(bin.isDirectory || bin.mkdirs()) { "cannot create $BIN_DIR" }
        File(root, "tmp").let { check(it.isDirectory || it.mkdirs()) { "cannot create tmp" } } // the pid file and TMPDIR live there
        fun q(s: String) = DirectLaunch.quote(s)
        fun script(name: String, body: String) = DirectLaunch.write(File(bin, name), "#!$SHELL\n$body\n", executable = true)
        script("node", "exec ${q(nodePath)} \"\$@\"")
        script("bash", "exec $SHELL \"\$@\"")
        for (name in listOf("pnpm", "pnpx")) script(name, "exec node ${q("$root/opt/pnpm/node_modules/pnpm/bin/$name.mjs")} \"\$@\"")
        script("dsh", "exec node --expose-internals ${q("$root/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js")} \"\$@\"")
        val patch = File(root, WEB_PATCH.removePrefix("/"))
        patch.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "cannot create ${it.path}" } }
        DirectLaunch.write(patch, webPatchYaml(GuestPaths.bionic(rootfs)))
    }

    /** The glibc package bakes the same overlay into its archive; here the paths are physical, so it is written per start. */
    fun webPatchYaml(paths: GuestPaths): String = paths.text(
        "# dsh-app: overlay for `dsh web`, rewritten by the app on every start (edits here do not survive).\n" +
            "#\n" +
            "# Plugin installs ask pnpm's own registry first; the launch environment points it at npmmirror.\n" +
            "# Listing npmmirror here keeps it in the public set, so npm's own registry is asked next when the\n" +
            "# mirror is unreachable or has not synced a version yet.\n" +
            "- id: plugin-manager\n" +
            "  config:\n" +
            "    fallbackRegistries:\n" +
            "      - https://registry.npmmirror.com/\n" +
            "      - https://registry.npmjs.org/\n" +
            "#\n" +
            "# Model streams give up after $MODEL_STREAM_IDLE_MS ms without a byte or heartbeat, not dsh's 300 s:\n" +
            "# in the Zhuoyitong container a TCP flow now and then stops delivering with no FIN or RST\n" +
            "# (seen on fresh connections, before the headers and mid-stream), and the retry on a new\n" +
            "# connection answers at once. A value set in the model settings still wins.\n" +
            "- id: llm-deepseek\n" +
            "  config:\n" +
            "    streamIdleTimeoutMs: $MODEL_STREAM_IDLE_MS\n" +
            "- id: llm-deepseek-account\n" +
            "  config:\n" +
            "    streamIdleTimeoutMs: $MODEL_STREAM_IDLE_MS\n" +
            "#\n" +
            "# dsh-go-bridge: plugin inventory for the app (GET /dsh-go/v1/inventory), loaded by path.\n" +
            "- insert:\n" +
            "    - id: dsh-go-bridge\n" +
            "      name: $DSH_GO_BRIDGE/lib/index.js\n",
    )
}

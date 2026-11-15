package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Which engine world the next start uses: `glibc` (PRoot / Direct, an Ubuntu rootfs) or `bionic`
 * (a Node executable on the phone's own libc). Pure decisions plus the two device probes; the
 * state (saved choice, fallback marker, probe cache) lives in [RuntimeController]'s preferences.
 *
 * Order, first hit wins: the developer option, the fallback marker, the automatic check, glibc.
 * `Build.FINGERPRINT` is diagnostics only and takes no part in the decision.
 */
object EngineFamily {
    enum class Family(val wire: String) {
        GLIBC("glibc"), BIONIC("bionic");

        companion object {
            fun parse(wire: String?): Family? = entries.firstOrNull { it.wire == wire }
        }
    }

    const val AUTO = "auto"

    /** [reason] is one of `override`, `fallback`, `seLinux`, `syscallProbe`, `onlyBundled`, `default`. */
    data class Decision(val family: Family, val reason: String)

    /** What `libdsh_sysprobe.so` reported. */
    enum class Probe(val wire: String) {
        /** The probe's raw `set_robust_list` call was allowed. */
        OPEN("open"),

        /** The call was killed with SIGSYS: glibc cannot start here. */
        CONFINED("confined"),
        UNKNOWN("unknown"),
    }

    /** The saved developer choice: `auto`, `glibc` or `bionic`; anything else reads as `auto`. */
    fun normalize(wire: String?): String = if (wire == "glibc" || wire == "bionic") wire else AUTO

    /** The SELinux domain the Pura X container shows its apps in (`u:r:aoco_untrusted_app_27:...`, P0-B). */
    fun confinedDomain(context: String?): Boolean =
        context?.trim { it.isWhitespace() || it == '\u0000' }?.startsWith("u:r:aoco_untrusted_app") == true

    fun decide(
        choice: String?,
        /** `engine_family_auto_bionic`: the glibc runtime version a SIGSYS start fell back from. */
        fallbackFrom: String?,
        glibcRuntime: String?,
        bionicAvailable: Boolean,
        seLinuxDomain: String?,
        probe: Probe?,
        /** The APK carries the glibc runtime; a bionic-only APK has no glibc world to fall into. */
        glibcBundled: Boolean = true,
    ): Decision = when {
        !glibcBundled && bionicAvailable -> Decision(Family.BIONIC, "onlyBundled")
        choice == Family.GLIBC.wire -> Decision(Family.GLIBC, "override")
        !bionicAvailable -> Decision(Family.GLIBC, "default")
        choice == Family.BIONIC.wire -> Decision(Family.BIONIC, "override")
        // Same rule as the Direct failure mark: only a changed glibc runtime earns glibc another try.
        fallbackFrom != null && fallbackFrom == glibcRuntime -> Decision(Family.BIONIC, "fallback")
        confinedDomain(seLinuxDomain) -> Decision(Family.BIONIC, "seLinux")
        probe == Probe.CONFINED -> Decision(Family.BIONIC, "syscallProbe")
        else -> Decision(Family.GLIBC, "default")
    }

    /**
     * A glibc start killed by SIGSYS before it reported ready: switch to bionic without a failure
     * card, a strike or safe mode. Only on `auto`: a person who chose glibc keeps the failure.
     */
    fun shouldFallBack(running: Family, choice: String?, bionicAvailable: Boolean, exitCode: Int, readyAt: Long?): Boolean =
        running == Family.GLIBC && normalize(choice) == AUTO && bionicAvailable &&
            exitCode == CrashClassifier.SIGSYS_EXIT && readyAt == null

    /**
     * The saved choice and the fallback marker over a key-value [Store] (the controller's preferences),
     * so the whole fallback path can be run on the JVM: a glibc start killed by SIGSYS before ready
     * writes the marker and the next [decision] is bionic.
     */
    class State(
        private val store: Store,
        private val glibcRuntime: () -> String?,
        private val bionicAvailable: Boolean,
        private val seLinuxDomain: () -> String?,
        private val probe: () -> Probe?,
        private val glibcBundled: Boolean = true,
    ) {
        interface Store {
            fun get(key: String): String?
            fun put(key: String, value: String?)
        }

        @Volatile var decision: Decision = compute()
            private set

        /** `auto`, `glibc` or `bionic`. */
        val choice: String get() = normalize(store.get(KEY_OVERRIDE))

        fun refresh(): Decision {
            decision = compute()
            return decision
        }

        /** An explicit choice; it also forgets an automatic fallback, so `auto` looks at the device afresh. */
        fun choose(wire: String): Decision {
            require(wire == AUTO || Family.parse(wire) != null) { "unknown engine family: $wire" }
            check(wire != Family.BIONIC.wire || bionicAvailable) { "this app does not bundle the bionic engine" }
            store.put(KEY_OVERRIDE, if (wire == AUTO) null else wire)
            store.put(KEY_FALLBACK, null)
            return refresh()
        }

        /** A start of [running] ended with [exitCode]; true when it was the SIGSYS case and the family switched. */
        fun fellBack(running: Family, exitCode: Int, readyAt: Long?): Boolean {
            val from = glibcRuntime() ?: return false
            if (!shouldFallBack(running, store.get(KEY_OVERRIDE), bionicAvailable, exitCode, readyAt)) return false
            store.put(KEY_FALLBACK, from)
            refresh()
            return true
        }

        private fun compute() = decide(
            store.get(KEY_OVERRIDE), store.get(KEY_FALLBACK), glibcRuntime(), bionicAvailable, seLinuxDomain(), probe(), glibcBundled,
        )

        companion object {
            /** Developer option `glibc` / `bionic`; absent is `auto`. */
            const val KEY_OVERRIDE = "engine_family_override"

            /** The glibc `runtimeVersion` whose start was killed by SIGSYS: bionic until that version changes. */
            const val KEY_FALLBACK = "engine_family_auto_bionic"
        }
    }

    // ── syscall probe ───────────────────────────────────────────────────────────────

    /** Exit code contract of `libdsh_sysprobe.so`: 0 allowed, 42 the child got SIGSYS, else unknown. */
    const val PROBE_EXIT_OPEN = 0
    const val PROBE_EXIT_SIGSYS = 42

    fun probeOf(exitCode: Int?): Probe = when (exitCode) {
        PROBE_EXIT_OPEN -> Probe.OPEN
        // The probe itself killed by the filter says the same thing as its child being killed.
        PROBE_EXIT_SIGSYS, CrashClassifier.SIGSYS_EXIT -> Probe.CONFINED
        else -> Probe.UNKNOWN
    }

    /** A cached result is valid only for the system build that produced it. */
    fun cachedProbe(resultWire: String?, cachedFingerprint: String?, fingerprint: String?): Probe? {
        if (fingerprint == null || cachedFingerprint != fingerprint) return null
        return Probe.entries.firstOrNull { it.wire == resultWire && it != Probe.UNKNOWN }
    }

    data class ProbeRun(val probe: Probe, val exitCode: Int?, val line: String?)

    /** Execs the probe from the native library directory; a missing file or a hang reads as [Probe.UNKNOWN]. */
    fun runProbe(nativeLibDir: String, timeoutMs: Long = 5_000): ProbeRun {
        val exe = File(nativeLibDir, "libdsh_sysprobe.so")
        if (!exe.isFile) return ProbeRun(Probe.UNKNOWN, null, "missing")
        return try {
            val p = ProcessBuilder(exe.path).redirectErrorStream(true).start()
            var line: String? = null
            val reader = Thread { line = runCatching { p.inputStream.bufferedReader().readLine() }.getOrNull() }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return ProbeRun(Probe.UNKNOWN, null, "timeout")
            }
            reader.join(500)
            val code = p.exitValue()
            ProbeRun(probeOf(code), code, line?.take(200))
        } catch (e: Exception) {
            ProbeRun(Probe.UNKNOWN, null, e.javaClass.simpleName)
        }
    }

    /** The app's own SELinux context, or null when unreadable. */
    fun readSeLinuxDomain(): String? = runCatching { File("/proc/self/attr/current").readText() }.getOrNull()
}

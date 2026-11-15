package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.*
import org.junit.Test

class CrashClassifierTest {
    private val fatal = listOf("dsh: startup failed: required row missing")

    @Test fun classifiesAllFiveFailureKinds() {
        fun classify(ready: Long?, now: Long, tail: List<String>) =
            CrashClassifier.classify(137, ready, now, 123L, tail, false).kind
        assertEquals(FailureKind.KILLED, classify(1L, 180_000L, listOf("normal output")))
        assertEquals(FailureKind.STARTUP_FAILED, classify(null, 1L, fatal))
        assertEquals(FailureKind.CRASHED_EARLY, classify(1L, 120_001L, fatal))
        assertEquals(FailureKind.CRASHED_LATE, classify(1L, 120_002L, fatal))
        assertEquals(FailureKind.NO_SPACE, classify(null, 1L, fatal + "ENOSPC"))
    }

    @Test fun summariesNeverContainLaunchTokens() {
        val failure = CrashClassifier.classify(1, null, 1L, 1L,
            listOf("dsh: startup failed: http://localhost/?token=private-value"), false)
        assertFalse(failure.summary!!.contains("private-value"))
        assertTrue(failure.summary!!.contains("token="))
    }

    private fun bionic(code: Int, ready: Long?, tail: List<String>, now: Long = 10_000L) =
        CrashClassifier.classify(code, ready, now, 123L, tail, false, bionic = true)

    @Test fun bionicSigsysBeforeReadyIsALaunchFailureThatSaysWhatKilledIt() {
        val f = bionic(CrashClassifier.SIGSYS_EXIT, null, emptyList())
        assertEquals(FailureKind.LAUNCH_FAILED, f.kind)
        assertEquals("killed by the system's syscall filter (SIGSYS)", f.summary)
    }

    @Test fun bionicSigsysAfterReadyStaysKilledButNamesTheFilter() {
        val f = bionic(CrashClassifier.SIGSYS_EXIT, 1_000L, listOf("normal output"))
        assertEquals(FailureKind.KILLED, f.kind)
        assertEquals("killed by the system's syscall filter (SIGSYS)", f.summary)
        // Another signal after ready has no summary.
        assertNull(bionic(137, 1_000L, listOf("normal output")).summary)
    }

    @Test fun bionicLinkerAndShellExecFailuresAreLaunchFailures() {
        for (line in listOf(
            "CANNOT LINK EXECUTABLE \"/data/app/x/lib/arm64/libdsh_node.so\": library \"libfoo.so\" not found",
            "/system/bin/sh: /data/app/x/lib/arm64/libdsh_node.so: inaccessible or not found",
            "sh: /data/app/x/lib/arm64/libdsh_node.so: Permission denied",
            "/system/bin/sh: /data/app/x/lib/arm64/libdsh_node.so: Exec format error",
        )) {
            val f = bionic(127, null, listOf(line))
            assertEquals(line, FailureKind.LAUNCH_FAILED, f.kind)
            assertEquals(line, CrashClassifier.redact(line).take(240), f.summary)
        }
    }

    @Test fun bionicIgnoresTheGlibcLaunchEvidenceAndPluginErrors() {
        val proot = "proot error: execve(\"/usr/bin/env\"): Permission denied"
        assertEquals(FailureKind.KILLED, bionic(1, null, listOf(proot)).kind)
        // A plugin's generic message is not launch evidence.
        assertEquals(FailureKind.KILLED, bionic(1, null, listOf("Error: EACCES: Permission denied, open '/x'")).kind)
        assertEquals(FailureKind.KILLED, bionic(1, 1_000L, listOf("/system/bin/sh: x: Permission denied")).kind)
    }

    @Test fun bionicStillRecognisesDshFatalMarkers() {
        assertEquals(FailureKind.STARTUP_FAILED, bionic(1, null, fatal).kind)
        assertEquals(FailureKind.CRASHED_EARLY, bionic(1, 1_000L, fatal, now = 2_000L).kind)
    }

    @Test fun glibcClassificationIsUnchangedByTheBionicFlagDefault() {
        // PRoot SIGSYS before ready stays KILLED: the fallback is decided from the exit code, not from the classifier.
        assertEquals(FailureKind.KILLED, CrashClassifier.classify(CrashClassifier.SIGSYS_EXIT, null, 1L, 1L, emptyList(), false).kind)
    }
}

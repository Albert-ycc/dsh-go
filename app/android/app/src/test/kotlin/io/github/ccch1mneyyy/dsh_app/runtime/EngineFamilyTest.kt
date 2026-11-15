package io.github.ccch1mneyyy.dsh_app.runtime

import io.github.ccch1mneyyy.dsh_app.runtime.EngineFamily.Family
import io.github.ccch1mneyyy.dsh_app.runtime.EngineFamily.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EngineFamilyTest {
    private val confined = "u:r:aoco_untrusted_app_27:s0:c93,c256,c512,c768"
    private val ordinary = "u:r:untrusted_app:s0:c93,c256,c512,c768"

    private fun decide(
        choice: String? = null, fallback: String? = null, glibc: String? = "rt-1", bionic: Boolean = true,
        domain: String? = ordinary, probe: Probe? = null,
    ) = EngineFamily.decide(choice, fallback, glibc, bionic, domain, probe)

    @Test fun theDeveloperOptionBeatsEverythingElse() {
        assertEquals(EngineFamily.Decision(Family.GLIBC, "override"), decide("glibc", fallback = "rt-1", domain = confined, probe = Probe.CONFINED))
        assertEquals(EngineFamily.Decision(Family.BIONIC, "override"), decide("bionic", domain = ordinary, probe = Probe.OPEN))
    }

    @Test fun theFallbackMarkerBeatsTheAutomaticCheckWhileTheGlibcRuntimeIsTheSame() {
        assertEquals(EngineFamily.Decision(Family.BIONIC, "fallback"), decide(fallback = "rt-1", probe = Probe.OPEN))
        // A new glibc runtime earns glibc another try.
        assertEquals(EngineFamily.Decision(Family.GLIBC, "default"), decide(fallback = "rt-0", glibc = "rt-1"))
        assertEquals(EngineFamily.Decision(Family.BIONIC, "seLinux"), decide(fallback = "rt-0", glibc = "rt-1", domain = confined))
    }

    @Test fun autoUsesTheSeLinuxDomainThenTheProbeThenGlibc() {
        assertEquals(EngineFamily.Decision(Family.BIONIC, "seLinux"), decide(domain = confined, probe = Probe.OPEN))
        assertEquals(EngineFamily.Decision(Family.BIONIC, "syscallProbe"), decide(probe = Probe.CONFINED))
        assertEquals(EngineFamily.Decision(Family.GLIBC, "default"), decide(probe = Probe.OPEN))
        assertEquals(EngineFamily.Decision(Family.GLIBC, "default"), decide(probe = Probe.UNKNOWN, domain = null))
        assertEquals(EngineFamily.Decision(Family.GLIBC, "default"), decide(probe = null))
    }

    @Test fun withoutABionicPackageEverythingIsGlibc() {
        assertEquals(Family.GLIBC, decide("bionic", bionic = false, domain = confined, probe = Probe.CONFINED).family)
        assertEquals(Family.GLIBC, decide(fallback = "rt-1", bionic = false).family)
    }

    @Test fun onlyTheSeLinuxDomainPrefixCounts() {
        assertTrue(EngineFamily.confinedDomain(confined))
        assertTrue(EngineFamily.confinedDomain("$confined\u0000"))
        assertTrue(EngineFamily.confinedDomain(" $confined\n"))
        assertFalse(EngineFamily.confinedDomain(ordinary))
        assertFalse(EngineFamily.confinedDomain(null))
        assertFalse(EngineFamily.confinedDomain(""))
    }

    @Test fun probeExitCodesFollowTheContract() {
        assertEquals(Probe.OPEN, EngineFamily.probeOf(0))
        assertEquals(Probe.CONFINED, EngineFamily.probeOf(42))
        assertEquals(Probe.CONFINED, EngineFamily.probeOf(159))
        assertEquals(Probe.UNKNOWN, EngineFamily.probeOf(1))
        assertEquals(Probe.UNKNOWN, EngineFamily.probeOf(null))
    }

    @Test fun aCachedProbeAnswersOnlyForTheSameSystemBuild() {
        assertEquals(Probe.CONFINED, EngineFamily.cachedProbe("confined", "fp-a", "fp-a"))
        assertEquals(Probe.OPEN, EngineFamily.cachedProbe("open", "fp-a", "fp-a"))
        assertNull(EngineFamily.cachedProbe("confined", "fp-a", "fp-b"))
        assertNull(EngineFamily.cachedProbe("unknown", "fp-a", "fp-a"))
        assertNull(EngineFamily.cachedProbe(null, null, "fp-a"))
        assertNull(EngineFamily.cachedProbe("open", "fp-a", null))
    }

    @Test fun theDeviceFingerprintTakesNoPartInTheDecision() {
        // decide() has no fingerprint input at all; this pins that the HWVDE build string is not smuggled in via the domain either.
        assertEquals(Family.GLIBC, decide(domain = "HUAWEI/VDE-AL00/HWVDE:16/HUAWEIVDE-AL00/104.5.5.105DHSP16:user/release-keys").family)
    }

    // ── fallback state machine ──────────────────────────────────────────────────────

    private class MemoryStore : EngineFamily.State.Store {
        val map = linkedMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private fun state(store: MemoryStore = MemoryStore(), glibc: String? = "rt-1", bionic: Boolean = true, domain: String? = ordinary) =
        EngineFamily.State(store, { glibc }, bionic, { domain }, { null })

    @Test fun aGlibcStartKilledBySigsysBeforeReadyWritesTheMarkerAndSwitchesTheFamily() {
        val store = MemoryStore()
        val state = state(store)
        assertEquals(EngineFamily.Decision(Family.GLIBC, "default"), state.decision)

        val switched = state.fellBack(Family.GLIBC, exitCode = 159, readyAt = null)

        assertTrue(switched)
        assertEquals("rt-1", store.map[EngineFamily.State.KEY_FALLBACK])
        assertEquals(EngineFamily.Decision(Family.BIONIC, "fallback"), state.decision)
    }

    @Test fun theMarkerSurvivesAProcessRestartAndFallsAwayWithANewGlibcRuntime() {
        val store = MemoryStore()
        state(store).fellBack(Family.GLIBC, 159, null)
        assertEquals(Family.BIONIC, state(store).decision.family)
        assertEquals(Family.GLIBC, state(store, glibc = "rt-2").decision.family)
    }

    @Test fun otherExitsLeaveTheFamilyAlone() {
        for ((running, code, ready) in listOf(
            Triple(Family.GLIBC, 159, 5_000L), // it had been up: a plain kill
            Triple(Family.GLIBC, 137, null),
            Triple(Family.GLIBC, 1, null),
            Triple(Family.BIONIC, 159, null), // never ping-pong back
        )) {
            val store = MemoryStore()
            assertFalse("$running $code $ready", state(store).fellBack(running, code, ready))
            assertNull(store.map[EngineFamily.State.KEY_FALLBACK])
        }
    }

    @Test fun anExplicitGlibcChoiceKeepsItsFailureAndAutoNeedsABionicPackage() {
        val store = MemoryStore()
        val state = state(store)
        state.choose("glibc")
        assertFalse(state.fellBack(Family.GLIBC, 159, null))
        assertEquals("glibc", state.choice)
        assertFalse(state(MemoryStore(), bionic = false).fellBack(Family.GLIBC, 159, null))
    }

    @Test fun anExplicitChoiceForgetsTheMarker() {
        val store = MemoryStore()
        val state = state(store)
        state.fellBack(Family.GLIBC, 159, null)
        state.choose("auto")
        assertNull(store.map[EngineFamily.State.KEY_FALLBACK])
        assertEquals(EngineFamily.Decision(Family.GLIBC, "default"), state.decision)
        assertEquals(EngineFamily.Decision(Family.BIONIC, "override"), state.choose("bionic"))
        assertEquals("bionic", store.map[EngineFamily.State.KEY_OVERRIDE])
        state.choose("auto")
        assertNull(store.map[EngineFamily.State.KEY_OVERRIDE])
    }

    @Test fun chooseRejectsUnknownValuesAndAMissingBionicPackage() {
        try { state().choose("proot"); fail() } catch (_: IllegalArgumentException) {}
        try { state(bionic = false).choose("bionic"); fail() } catch (_: IllegalStateException) {}
    }

    @Test fun withoutAGlibcManifestThereIsNothingToMark() {
        val store = MemoryStore()
        assertFalse(state(store, glibc = null).fellBack(Family.GLIBC, 159, null))
        assertTrue(store.map.isEmpty())
    }
}

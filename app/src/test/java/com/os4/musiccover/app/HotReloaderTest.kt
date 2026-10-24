package com.os4.musiccover.app

import io.github.libxposed.service.HookedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets a hot reload and who is restarted instead. The list is empty today (Step 4), which
 * is the one case that deliberately tries every process so the refusal path gets exercised.
 */
class HotReloaderTest {

    private fun plan(
        state: HookedTarget.State,
        name: String = "com.example",
        version: Long = 100,
        supported: Set<String> = setOf("com.example"),
        broken: Set<Int> = emptySet(),
        first: Int = 0,
    ) = HotReloader.plan(state, name, version, supported, broken, first)

    @Test
    fun onlyStaleAndFailedAreCandidates() {
        assertEquals(HotReloader.Plan.SKIP, plan(HookedTarget.State.UP_TO_DATE))
        assertEquals(HotReloader.Plan.SKIP, plan(HookedTarget.State.RELOADING))
        assertEquals(HotReloader.Plan.HOT_RELOAD, plan(HookedTarget.State.STALE))
        assertEquals(HotReloader.Plan.HOT_RELOAD, plan(HookedTarget.State.FAILED))
    }

    @Test
    fun withNothingSupportedEveryCandidateIsTried() {
        assertEquals(
            HotReloader.Plan.HOT_RELOAD,
            plan(HookedTarget.State.STALE, name = "com.anyone", supported = emptySet()),
        )
    }

    @Test
    fun aProcessNotOnTheListIsRestartedWithoutTheRoundTrip() {
        assertEquals(
            HotReloader.Plan.RESTART,
            plan(HookedTarget.State.STALE, name = "com.other", supported = setOf("com.example")),
        )
    }

    @Test
    fun aBuildWithBrokenRetirementIsRestarted() {
        assertEquals(
            HotReloader.Plan.RESTART,
            plan(HookedTarget.State.STALE, version = 7, broken = setOf(7)),
        )
    }

    @Test
    fun aBuildFromBeforeHotReloadExistedIsRestarted() {
        assertEquals(
            HotReloader.Plan.RESTART,
            plan(HookedTarget.State.STALE, version = 3, first = 4),
        )
    }

    @Test
    fun aGoodReloadIsNotCompleteWhileAnotherProcessStillNeedsRestart() {
        val report = HotReloader.Report(
            attempted = listOf("com.apple.android.music"),
            succeeded = listOf("com.apple.android.music"),
            failed = emptyList(),
            needsRestart = listOf("com.android.systemui"),
        )
        assertFalse(report.allSucceeded)
        assertTrue(report.copy(needsRestart = emptyList()).allSucceeded)
    }
}

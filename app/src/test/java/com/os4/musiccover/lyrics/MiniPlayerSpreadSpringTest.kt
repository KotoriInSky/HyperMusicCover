package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class MiniPlayerSpreadSpringTest {
    @Test fun fastReleaseContinuesFromTheFingerInsteadOfSnappingToTheList() {
        val spring = MiniPlayerSpreadSpring(0.35f, 3f, true)
        assertEquals(0.35f, spring.step(0f), 0f)
        val first = spring.step(1f / 120f)
        assertTrue(first > 0.35f && first < 0.5f)
        var overshot = false
        repeat(240) { overshot = spring.step(1f / 120f) > 1f || overshot }
        assertTrue(overshot)
        assertTrue(spring.atRest)
        assertEquals(1f, spring.step(0f), 0f)
    }

    @Test fun reversingAndCancelledPullsReturnHomeWithBoundedOvershoot() {
        val spring = MiniPlayerSpreadSpring(0.65f, -30f, false)
        repeat(240) {
            assertTrue(spring.step(1f / 120f) in -0.025f..1.025f)
        }
        assertTrue(spring.atRest)
        assertEquals(0f, spring.step(0f), 0f)
    }

    @Test fun differentRefreshRatesReachTheSamePoseAtTheSameTime() {
        fun pose(fps: Int): Float {
            val spring = MiniPlayerSpreadSpring(0.4f, 2f, true)
            var value = 0.4f
            repeat(fps / 5) { value = spring.step(1f / fps) }
            return value
        }
        assertEquals(pose(60), pose(120), 0.0001f)
    }
}

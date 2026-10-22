package com.os4.musiccover.island

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandOpeningTest {
    @Test fun cardTravelFollowsFingerRegardlessOfNativeScrollRange() {
        for (nativeSpan in listOf(120f, -120f, 600f)) {
            val travel = 480f
            val p = MiniPlayerStackGesture.openingProgress(120f, travel)
            val nativeScroll = nativeSpan * p
            assertEquals(120f, travel * (nativeScroll / nativeSpan), 0.001f)
        }
    }

    @Test fun reversalAndOvershootStayWithinEndpoints() {
        assertEquals(0f, MiniPlayerStackGesture.openingProgress(-10f, 480f), 0f)
        assertEquals(1f, MiniPlayerStackGesture.openingProgress(900f, 480f), 0f)
        assertEquals(0.125f, MiniPlayerStackGesture.openingProgress(60f, 480f), 0f)
        assertEquals(0f, MiniPlayerStackGesture.openingProgress(0f, 0f), 0f)
    }
}

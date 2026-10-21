package com.os4.musiccover.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricGlowTest {
    @Test public void radiusCrossfadeHasNoBrightnessDipAtLayerBoundaries() {
        for (int step = 334; step <= 1000; step++) {
            float glow = step / 1000f;
            float sum = 0f;
            int drawn = 0;
            for (int layer = 0; layer < LyricGlow.LAYERS; layer++) {
                float w = LyricGlow.weight(glow, layer);
                assertTrue(w >= 0f && w <= 1f);
                sum += w;
                if (w > 0f) drawn++;
                float previous = LyricGlow.weight(glow - 0.001f, layer);
                assertTrue(Math.abs(w - previous) <= 0.00301f);
            }
            assertEquals(1f, sum, 0.00001f);
            assertTrue(drawn <= 2);
        }
    }

    @Test public void glowStartsInvisibleAndReachesTheFullRadius() {
        for (int layer = 0; layer < LyricGlow.LAYERS; layer++) {
            assertEquals(0f, LyricGlow.weight(0f, layer), 0f);
            assertEquals(layer == 2 ? 1f : 0f, LyricGlow.weight(1f, layer), 0f);
        }
    }

    @Test public void hdrArmsBeforeTheFirstHeldSyllableAndIncludesItsTail() {
        LyricLine line = new LyricLine("held", null, 1000, 2400, false,
                new int[]{1000}, new int[]{2400}, new int[]{4});
        assertFalse(LyricGlow.arm(line, 749, 1000, 250, 380));
        assertTrue(LyricGlow.arm(line, 750, 1000, 250, 380));
        assertTrue(LyricGlow.arm(line, 2779, 1000, 250, 380));
        assertFalse(LyricGlow.arm(line, 2780, 1000, 250, 380));
    }

    @Test public void shortAndUntimedWordsNeverRequestHdr() {
        LyricLine shortNote = new LyricLine("a", null, 0, 900, false,
                new int[]{0}, new int[]{900}, new int[]{1});
        LyricLine untimed = new LyricLine("line", null, 0, 3000, false, null, null, null);
        assertFalse(LyricGlow.arm(shortNote, 100, 1000, 250, 380));
        assertFalse(LyricGlow.arm(untimed, 100, 1000, 250, 380));
    }
}

package com.os4.musiccover.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricSourcePolicyTest {
    @Test public void defaultStartsWithMetadataAndDirectory() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(false, true, "qq,local", null);
        assertEquals("metadata", config.sources()[0]);
        assertEquals("local", config.sources()[1]);
        assertEquals("lyricon", config.sources()[2]);
    }

    @Test public void customOrderIsDeduplicatedAndCompleted() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(true, true, "QQ,qq,invalid, local ", null);
        assertEquals(10, config.sources().length);
        assertEquals("qq", config.sources()[0]);
        assertEquals("local", config.sources()[1]);
        assertEquals(LyricSourcePolicy.DEFAULT_FORMATS, config.formatOrder);
    }

    @Test public void formatsAreWhitelistedAndKeepRequestedPriority() {
        assertEquals("lrc,ttml,yrc,krc,lys", LyricSourcePolicy.normalizeFormats("LRC,lrc,../ttml,ttml"));
        assertEquals(LyricSourcePolicy.DEFAULT_SOURCES, LyricSourcePolicy.normalizeSources(null));
    }

    @Test public void lowerPriorityCannotOverwriteOriginalLyrics() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(false, true, null, null);
        assertFalse(config.mayReplace("metadata", "qq"));
        assertFalse(config.mayReplace("metadata", "local"));
        assertFalse(config.mayReplace("local", "lyricon"));
        assertTrue(config.mayReplace("qq", "metadata"));
        assertTrue(config.mayReplace("metadata", "metadata"));
    }

    @Test public void disablingNetworkPreservesLocalAndProviderRoutes() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(true, false, "qq,metadata", null);
        for (String source : config.sources()) assertEquals(!LyricSourcePolicy.network(source), config.permits(source));
        assertTrue(config.permits("lyricon"));
        assertFalse(config.mayReplace("none", "qq"));
        assertTrue(config.mayReplace("qq", "metadata"));
    }

    @Test public void requestedOnlinePriorityIsHonoredWhenEnabled() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(true, true, "qq,local,metadata", null);
        assertTrue(config.mayReplace("metadata", "qq"));
        assertFalse(config.mayReplace("qq", "metadata"));
    }
}

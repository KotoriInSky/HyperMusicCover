package com.os4.musiccover.lyrics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The brackets around a background vocal (#102): LyricParse.unwrapBackground. */
class BackgroundVocalTest {
    private fun timed(text: String, vararg ends: Int) = LyricLine(text, null, 0, 1000, false,
        IntArray(ends.size) { it * 100 }, IntArray(ends.size) { it * 100 + 100 }, ends)

    @Test
    fun bracketsInsideTheFirstAndLastWordsComeOff() {
        val b = LyricParse.unwrapBackground(timed("(oh yeah)", 4, 9))
        assertEquals("oh yeah", b.text)
        assertArrayEquals(intArrayOf(3, 7), b.charEnd)
        assertArrayEquals(intArrayOf(0, 100), b.sylStart)
    }

    @Test
    fun aSyllableThatIsOnlyABracketGoes() {
        val b = LyricParse.unwrapBackground(timed("（和声）", 1, 2, 3, 4))
        assertEquals("和声", b.text)
        assertArrayEquals(intArrayOf(1, 2), b.charEnd)
        assertArrayEquals(intArrayOf(100, 200), b.sylStart)
    }

    @Test
    fun aLineTimedOneLosesThemToo() {
        val b = LyricParse.unwrapBackground(LyricLine(" ( oh ) ", null, 0, 1000, false,
            null, null, null))
        assertEquals("oh", b.text)
        assertFalse(b.hasWords())
    }

    @Test
    fun bracketsThatDoNotWrapTheLineStay() {
        val l = timed("(oh) yeah (oh)", 4, 9, 14)
        assertSame(l, LyricParse.unwrapBackground(l))
        val u = timed("(oh yeah", 3, 8)
        assertSame(u, LyricParse.unwrapBackground(u))
        val m = timed("(a)(b)", 3, 6)
        assertSame(m, LyricParse.unwrapBackground(m))
    }

    @Test
    fun nestedBracketsKeepTheInnerPair() {
        val b = LyricParse.unwrapBackground(timed("((oh))", 6))
        assertEquals("(oh)", b.text)
        assertTrue(b.hasWords())
        assertArrayEquals(intArrayOf(4), b.charEnd)
    }
}

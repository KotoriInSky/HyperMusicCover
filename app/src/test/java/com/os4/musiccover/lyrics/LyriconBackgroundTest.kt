package com.os4.musiccover.lyrics

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge's backing vocal as LyriconSource.background builds it (from PR #103's suite). */
class LyriconBackgroundTest {
    private fun word(text: String, begin: Long, end: Long) =
        LyricWord(text = text, begin = begin, end = end)

    private fun line(text: String?, vararg words: LyricWord) = RichLyricLine(
        begin = 1000, end = 2000, text = "主唱", secondary = text,
        secondaryWords = words.toList())

    private fun convert(line: RichLyricLine): LyricLine =
        requireNotNull(LyriconSource.background(line, 6000))

    private fun assertTimed(bg: LyricLine, text: String, starts: IntArray, ends: IntArray) {
        assertEquals(text, bg.text)
        assertTrue(bg.hasWords())
        assertArrayEquals(starts, bg.sylStart)
        assertArrayEquals(ends, bg.sylEnd)
        assertEquals(text.length, bg.charEnd.last())
        assertTrue(bg.charEnd.toList().zipWithNext().all { (a, b) -> a < b })
        assertEquals(0f, bg.sungChars(starts.first() - 1), 0f)
        assertEquals(text.length.toFloat(), bg.sungChars(ends.last()), 0f)
    }

    @Test
    fun bracketsAroundThePublishedTextKeepTheWordTimes() {
        val source = line("(为什么 还相信)", word("为什么", 1200, 1500), word("还相信", 1500, 2400))
        val bg = convert(source)
        assertTimed(bg, "为什么 还相信", intArrayOf(1200, 1500), intArrayOf(1500, 2400))
        assertEquals("(为什么 还相信)", source.secondary)
        assertEquals(1200, bg.start)
        // Past the lead's 2000: the line stays lit until the backing vocal is sung.
        assertEquals(2400, bg.end)
    }

    @Test
    fun bracketsOnTheTimedWordsComeOffWithoutMovingTheTimes() {
        val bg = convert(line("(为什么 还相信)",
            word("(为什么 ", 1200, 1500), word("还相信)", 1500, 2400)))
        assertTimed(bg, "为什么 还相信", intArrayOf(1200, 1500), intArrayOf(1500, 2400))
    }

    @Test
    fun bracketsSentAsWordsOfTheirOwnAreNotSyllables() {
        val bg = convert(line("（ 啊 ）", word("（", 1000, 1100),
            word(" 啊 ", 1200, 2400), word("）", 2500, 2600)))
        assertTimed(bg, "啊", intArrayOf(1200), intArrayOf(2400))
    }

    @Test
    fun flamingosDoubledTextUsesTheWordsOnce() {
        val bg = convert(line("(为什么 还相信)为什么 还相信",
            word("为什么 ", 1200, 1500), word("还相信", 1500, 2400)))
        assertTimed(bg, "为什么 还相信", intArrayOf(1200, 1500), intArrayOf(1500, 2400))
    }

    @Test
    fun wordsWithNoPublishedTextAreStillDrawn() {
        val bg = convert(line(null, word("(啊)", 900, 3200)))
        assertTimed(bg, "啊", intArrayOf(900), intArrayOf(3200))
    }

    @Test
    fun englishKeepsTheSpacesTheWordsLeaveOut() {
        val bg = convert(line("(Fifty, fifty feet up underground)",
            word("Fifty,", 1200, 1400), word("fifty", 1400, 1600),
            word("feet", 1600, 1800), word("up", 1800, 2000), word("underground", 2000, 3200)))
        assertEquals("Fifty, fifty feet up underground", bg.text)
        assertTrue(bg.hasWords())
        assertEquals(bg.text.length, bg.charEnd.last())
    }

    @Test
    fun innerBracketsAndRepeatsStay() {
        val bg = convert(line("(Oh (echo) Oh)",
            word("Oh ", 1200, 1500), word("(echo) ", 1500, 1900), word("Oh", 1900, 2400)))
        assertTimed(bg, "Oh (echo) Oh", intArrayOf(1200, 1500, 1900), intArrayOf(1500, 1900, 2400))
        val separate = convert(line("(Oh) (Oh)", word("(Oh) ", 1200, 1500), word("(Oh)", 1500, 2400)))
        assertEquals("(Oh) (Oh)", separate.text)
    }

    @Test
    fun anUnclosedBracketStays() {
        assertEquals("(Oh", convert(line("(Oh", word("(Oh", 1200, 2400))).text)
    }

    @Test
    fun aLineTimedOneLosesItsBracketsToo() {
        val bg = convert(line("(和声)"))
        assertEquals("和声", bg.text)
        assertFalse(bg.hasWords())
        assertNull(bg.sylStart)
        assertNull(LyriconSource.background(line(null), 6000))
    }

    @Test
    fun timesPastAnIntLeaveItLineTimed() {
        val bg = convert(line("(和声)", word("和声", Int.MAX_VALUE.toLong() + 1, 9000000000)))
        assertEquals("和声", bg.text)
        assertFalse(bg.hasWords())
    }

    @Test
    fun wordsGoingBackwardsLeaveItLineTimed() {
        val bg = convert(line(null, word("和", 1800, 2400), word("声", 1200, 1800)))
        assertEquals("和声", bg.text)
        assertFalse(bg.hasWords())
    }

    @Test
    fun aWordWithNoEndIsClosedByTheLine() {
        val bg = convert(line("(啊)", word("啊", 1200, 0)))
        assertTimed(bg, "啊", intArrayOf(1200), intArrayOf(2000))
    }

    @Test
    fun theProtocolModelKeepsTheWordsThroughSerialisation() {
        val source = line("(和声)", word("和", 1200, 1800), word("声", 1800, 2400))
        val encoded = Json.encodeToString(RichLyricLine.serializer(), source)
        val received = Json.decodeFromString(RichLyricLine.serializer(), encoded)
        assertTimed(convert(received), "和声", intArrayOf(1200, 1800), intArrayOf(1800, 2400))
    }
}

package com.os4.musiccover.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word timings in the line's own brackets (#64): LyricParse.bracketWords. */
class BracketWordsTest {
    private fun parse(vararg rows: String) = LyricParse.parse(rows.joinToString("\n"))

    @Test
    fun theFirstWordIsKept() {
        val lines = parse("[00:01.000]日[00:01.100]本[00:01.200]語[00:01.500]",
            "[00:02.000]Let's [00:02.300]just [00:02.600]forget[00:03.000]")
        assertEquals("日本語", lines[0].text)
        assertEquals("Let's just forget", lines[1].text)
        assertTrue(lines[0].hasWords())
        assertEquals(1000, lines[0].start)
    }

    @Test
    fun aClosingTimeIsNotShown() {
        assertEquals("Goodbyes", parse("[01:12.392]Goodbyes[01:15.342]").single().text)
        assertEquals("Goodbyes", parse("[01:15.342]Goodbyes[01:15.342]").single().text)
    }

    @Test
    fun aTranslationAtItsLinesStartIsItsTranslation() {
        val lines = parse("[tool:LDDC v0.9.2]",
            "[00:01.001]日[00:01.101]本[00:01.201]語",
            "[00:01.000]日本语翻译[00:01.000]",
            "[00:02.000]次[00:02.100]の[00:02.500]",
            "[00:02.000]下一个[00:02.500]")
        assertEquals(2, lines.size)
        assertEquals("日本語", lines[0].text)
        assertEquals("日本语翻译", lines[0].translation)
        assertEquals("次の", lines[1].text)
        assertEquals("下一个", lines[1].translation)
    }

    @Test
    fun aTranslationJustBeforeTheNextLineIsTheLastLines() {
        val lines = parse("[00:52.232]Let's [00:52.464]just [00:53.176]forget[00:56.352]",
            "[00:57.263]让我们就此遗忘[00:57.263]",
            "[00:57.264]Everything [00:58.440]said[01:00.024]")
        assertEquals(2, lines.size)
        assertEquals("Let's just forget", lines[0].text)
        assertEquals("让我们就此遗忘", lines[0].translation)
        assertEquals("Everything said", lines[1].text)
        assertNull(lines[1].translation)
    }

    @Test
    fun aTranslationWrittenAboveItsLineIsItsLines() {
        val lines = parse("[00:01.000]你孤身一人[00:01.000]",
            "[00:01.001]君[00:01.300]は[00:01.600]ひとり[00:02.500]",
            "[00:03.000]听到了什么[00:03.000]",
            "[00:03.001]何[00:03.300]を[00:03.600]聴いてた[00:04.500]")
        assertEquals(listOf("君はひとり", "何を聴いてた"), lines.map { it.text })
        assertEquals(listOf("你孤身一人", "听到了什么"), lines.map { it.translation })
    }

    @Test
    fun aPlainTranslationWrittenFirstIsStillTheTranslation() {
        val lines = parse("[00:01.00]你孤身一人", "[00:01.00]君はひとり",
            "[00:03.00]听到了什么", "[00:03.00]何を聴いてた")
        assertEquals(listOf("君はひとり", "何を聴いてた"), lines.map { it.text })
        assertEquals(listOf("你孤身一人", "听到了什么"), lines.map { it.translation })
        // Written the usual way round, nothing moves.
        val usual = parse("[00:01.00]君はひとり", "[00:01.00]你孤身一人")
        assertEquals("君はひとり", usual[0].text)
        assertEquals("你孤身一人", usual[0].translation)
        // A Chinese song with a Chinese second row is left alone.
        assertEquals("第一行", parse("[00:01.00]第一行", "[00:01.00]第二行")[0].text)
    }

    @Test
    fun aPlainTranslationOfAWordTimedLineGoesUnderIt() {
        val lines = parse("[00:01.000]你孤身一人", "[00:01.000]<00:01.000>君<00:01.300>は<00:01.600>ひとり")
        assertEquals("君はひとり", lines.single().text)
        assertEquals("你孤身一人", lines.single().translation)
    }

    @Test
    fun aDuetPrefixHoldsUntilTheNextOne() {
        val lines = parse("[00:01.000]女：[00:01.100]镜[00:01.250]中[00:01.400]",
            "[00:05.000]偶尔[00:05.300]红妆[00:05.600]",
            "[00:09.000]男：[00:09.100]只是[00:09.300]路过[00:09.500]",
            "[00:13.000]安静[00:13.300]离开[00:13.600]",
            "[00:17.000]女：[00:17.100]再见[00:17.400]",
            "[00:21.000]男：[00:21.100]好的[00:21.400]")
        assertEquals(listOf("镜中", "偶尔红妆", "只是路过", "安静离开", "再见", "好的"),
            lines.map { it.text })
        assertEquals(listOf(false, false, true, true, false, true), lines.map { it.opposite })
    }

    @Test
    fun aLineSungTwiceIsOneRowPerTime() {
        val lines = parse("[ti:歌]", "[00:01.00][00:05.00]副歌", "[00:03.00]主歌",
            "[00:07.00] [00:07.00]歌词")
        assertEquals(listOf("副歌", "主歌", "副歌", "歌词"), lines.map { it.text })
        assertEquals(listOf(1000, 3000, 5000, 7000), lines.map { it.start })
    }

    /** NetEase 2057709543 (Pasión): one row out of place, and the whole lyric was lost. */
    @Test
    fun aRowOutOfPlaceIsPutBack() {
        val lines = parse("[00:00.00] 作词 : G.E.M.", "[02:10.00]before",
            "[02:20.02]Cuz Father, my heart aches", "[02:14.44]¿Alguien escucha mi llanto?",
            "[02:30.00]after")
        assertEquals(listOf(0, 130000, 134440, 140020, 150000), lines.map { it.start })
    }

    @Test
    fun aLineSungTwiceKeepsItsTranslation() {
        val lines = parse("[00:01.00][00:05.00]副歌", "[00:01.00][00:05.00]chorus",
            "[00:03.00]主歌", "[00:03.00]verse")
        assertEquals(listOf("副歌", "主歌", "副歌"), lines.map { it.text })
        assertEquals(listOf("chorus", "verse", "chorus"), lines.map { it.translation })
    }

    @Test
    fun otherFormatsAreUntouched() {
        val enhanced = "[00:01.000]<00:01.000>日<00:01.100>本<00:01.200>語<00:01.500>"
        assertEquals(enhanced, LyricParse.bracketWords(enhanced).body)
        val plain = "[ti:歌]\n[00:01.00]日本語\n[00:01.00]日本语翻译"
        assertEquals(plain, LyricParse.bracketWords(plain).body)
        assertFalse(LyricParse.bracketWords("[00:01.000]x[00:01.500]").body.contains("01.500"))
    }

    @Test
    fun creditsCrowdedTogetherAreLinesNotTranslations() {
        // Lorde - Royals, embedded (#113).
        val lines = parse(
            "[00:00.060]Lyrics[00:00.065] [00:00.070]by[00:00.075]: [00:00.080]Ella[00:00.085] [00:00.090]Yelich[00:00.095] [00:00.100]O'Connor[00:00.105]/[00:00.110]Joel[00:00.115] [00:00.120]Little[00:00.130]",
            "[00:00.130]Composed[00:00.135] [00:00.140]by[00:00.145]: [00:00.150]Ella[00:00.155] [00:00.160]Yelich[00:00.165] [00:00.170]O'Connor[00:00.175]/[00:00.180]Joel[00:00.185] [00:00.190]Little[00:00.200]",
            "[00:00.200]Produced[00:00.208] [00:00.216]by[00:00.224]: [00:00.232]Joel[00:00.240] [00:00.248]Little[00:00.260]",
            "[00:00.271]I've [00:00.423]never [00:00.527]seen [00:00.713]a [00:00.976]diamond [00:01.740]in [00:02.019]the [00:02.218]flesh[00:05.710]",
            "[00:00.271]从未见过谁佩戴着珠宝",
            "[00:05.710]I [00:05.952]cut [00:06.285]my [00:06.629]teeth [00:06.920]on [00:07.256]wedding [00:07.752]rings [00:09.087]in [00:09.266]the [00:09.470]movies[00:11.605]",
            "[00:05.710]在电影里才见到过婚戒")
        assertEquals(listOf("Lyrics by: Ella Yelich O'Connor/Joel Little",
            "Composed by: Ella Yelich O'Connor/Joel Little", "Produced by: Joel Little",
            "I've never seen a diamond in the flesh", "I cut my teeth on wedding rings in the movies"),
            lines.map { it.text })
        assertNull(lines[0].translation)
        assertNull(lines[1].translation)
        assertNull(lines[2].translation)
        assertEquals("从未见过谁佩戴着珠宝", lines[3].translation)
        assertEquals("在电影里才见到过婚戒", lines[4].translation)
        assertEquals(130, lines[1].start)
        assertEquals(271, lines[3].start)
        assertTrue(lines[3].hasWords())
    }

    @Test
    fun plainLinesCrowdedTogetherAreLinesToo() {
        val lines = parse("[00:00.00]作词：甲", "[00:00.10]作曲：乙", "[00:00.10]Composer: B",
            "[00:03.00]第一句")
        assertEquals(listOf("作词：甲", "作曲：乙", "第一句"), lines.map { it.text })
        assertNull(lines[0].translation)
        assertEquals("Composer: B", lines[1].translation)
        assertEquals(100, lines[1].start)
        assertTrue(lines[0].end <= 100)
    }
}

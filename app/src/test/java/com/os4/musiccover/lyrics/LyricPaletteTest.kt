package com.os4.musiccover.lyrics

import com.materialkolor.hct.Hct
import org.junit.Assert.*
import org.junit.Test

class LyricPaletteTest {
    @Test fun cachedPaletteKeepsTheExistingFourMonetColors() {
        for (seed in listOf(0xff9432ac.toInt(), 0xff2c7364.toInt(), 0xff808080.toInt())) {
            val palette = LyricPalette.colors(seed)
            val source = Hct.fromInt(seed)
            fun expected(backing: Boolean, active: Boolean): Int = Hct.from(
                source.hue, minOf(source.chroma, if (backing) 24.0 else 36.0),
                if (active) { if (backing) 85.0 else 90.0 } else { if (backing) 72.0 else 78.0 },
            ).toInt()
            assertEquals(expected(false, false), palette.mainDim)
            assertEquals(expected(false, true), palette.mainLit)
            assertEquals(expected(true, false), palette.bgDim)
            assertEquals(expected(true, true), palette.bgLit)
            LyricPalette.colors(0xff445566.toInt())
            assertSame(palette, LyricPalette.colors(seed))
        }
    }

    @Test fun missingCoverKeepsNeutralColors() {
        val palette = LyricPalette.colors(0)
        assertEquals(-1, palette.mainDim)
        assertEquals(-1, palette.mainLit)
        assertEquals(-1, palette.bgDim)
        assertEquals(-1, palette.bgLit)
    }
}

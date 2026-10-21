package com.os4.musiccover.lyrics

import com.materialkolor.hct.Hct

/** The HCT engine already used by miuix, seeded from the current album cover. */
internal object LyricPalette {
    @JvmStatic
    fun coverColor(seed: Int, backing: Boolean, active: Boolean): Int {
        if (seed == 0) return -1
        val source = Hct.fromInt(seed)
        val chroma = minOf(source.chroma, if (backing) 24.0 else 36.0)
        val tone = if (active) { if (backing) 85.0 else 90.0 }
            else { if (backing) 72.0 else 78.0 }
        return Hct.from(source.hue, chroma, tone).toInt()
    }
}

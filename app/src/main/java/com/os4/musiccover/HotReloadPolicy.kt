package com.os4.musiccover

/**
 * What the module and the app have to agree on for a hot reload: the protocol number in the
 * extras, the process list both sides work from, and the remote preferences that switch it off.
 *
 * The app picks a plan per running target from [SUPPORTED] and [BROKEN_RETIRE]; the module's
 * retirement checks the same [SUPPORTED] before it lets go of anything. One file on purpose -
 * two copies would let the app ask a process the module refuses, or the other way round.
 */
object HotReloadPolicy {
    /** Bumped when the extras or the saved state change shape; the app sends the same number. */
    const val PROTOCOL = 1

    const val KEY_PROTOCOL = "hr_protocol"
    const val KEY_BUILD = "hr_build_mark"
    /** Debug only: which step to fail on purpose. See HotReload. */
    const val KEY_FAULT = "hr_fi"

    /**
     * Processes (not packages) whose retirement and adoption are implemented and have passed
     * the on-device matrix: Apple Music, 背屏, 高德, AOD, 小爱, SystemUI and the wallpaper
     * process. The module matches the exact process name - 高德's :locationservice and
     * :widgetProvider report the same package and carry their own copies of the hooks, but have
     * no installer of their own.
     *
     * SystemUI is the one with views to put back: its retirement runs SystemUiRetire's groups
     * on the main thread, and its adoption boots and re-attaches the keyguard container the old
     * generation handed over. The wallpaper process hands over the cover it is drawing instead
     * of putting it back: the texture is the OEM's and stays as it is. A failed adoption leaves
     * that generation inert and the app's existing 「需要重启」 path restarts the process.
     */
    @JvmField
    val SUPPORTED: Set<String> = setOf(
        "com.apple.android.music",
        "com.xiaomi.subscreencenter",
        "com.autonavi.minimap",
        "com.miui.aod",
        "com.miui.personalassistant",
        "com.android.systemui",
        "com.miui.miwallpaper",
    )

    /**
     * Loaded version codes whose retirement is known to be broken, so the app restarts them
     * without trying. Each entry gets a comment saying what went wrong.
     */
    @JvmField
    val BROKEN_RETIRE: Set<Int> = emptySet()

    /**
     * The first release that shipped `onHotReloading`; older builds refuse by default, so the
     * app restarts them without a round trip. Zero until that release exists - Step 4 is not it.
     */
    const val FIRST_HOT_RELOAD_VERSION = 0
}

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
     * Processes whose retirement and adoption are implemented and have passed the on-device
     * matrix. Apple Music first (three hooks, no threads), then the two that own receivers or
     * an observer, which the ledger's sweep takes back.
     *
     * <p>高德 and AOD have installers and join this list once their process can be reloaded on
     * the test phone: LSPosed's automatic pass never targeted 高德's main process (only its
     * :widgetProvider, which died), and the AOD process did not run outside the editor.
     */
    @JvmField
    val SUPPORTED: Set<String> = setOf(
        "com.apple.android.music",
        "com.xiaomi.subscreencenter",
        "com.miui.personalassistant",
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

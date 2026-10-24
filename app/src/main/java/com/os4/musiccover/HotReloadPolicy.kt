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

    /** The remote preferences group both sides already share (see [LogLevel]). */
    const val GROUP = "module"

    /** The user's switch for hot reload; only an explicit false refuses. */
    const val PREF_ENABLED = "hot_reload"

    /** A second switch for SystemUI alone, so it can be turned off by itself (Step 7). */
    const val PREF_SYSUI = "hot_reload_sysui"

    const val KEY_PROTOCOL = "hr_protocol"
    const val KEY_BUILD = "hr_build_mark"
    /** Debug only: which step to fail on purpose. See HotReload. */
    const val KEY_FAULT = "hr_fi"

    /**
     * Processes whose retirement and adoption are implemented and have passed the on-device
     * matrix. Empty until Step 5: with nothing here every process refuses, which is what makes
     * the home page's new action fall back to restarting.
     */
    @JvmField
    val SUPPORTED: Set<String> = emptySet()

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

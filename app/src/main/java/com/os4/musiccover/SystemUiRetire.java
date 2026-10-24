package com.os4.musiccover;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;

import com.os4.musiccover.immersive.ImmersiveHost;
import com.os4.musiccover.island.DateStatus;
import com.os4.musiccover.island.LockIslands;
import com.os4.musiccover.island.MiniPlayerRuntime;
import com.os4.musiccover.island.NumState;
import com.os4.musiccover.lyrics.LockLyrics;
import com.os4.musiccover.lyrics.LyriconSource;
import com.os4.musiccover.pickup.PickupIsland;
import com.os4.musiccover.shade.ShadeLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * What a retiring SystemUI generation takes back, feature by feature.
 *
 * <p>Step 6 only tears down. {@link #dryRun} is the debug probe's entry: it runs every group's
 * off path on the main thread, sweeps the ledger of everything registered since the install
 * step, and then puts the same generation back up through {@code boot} + the container attach
 * path. Hooks are deliberately untouched - this is a rehearsal of the teardown, not a reload.
 *
 * <p>The order is the reverse of a cold start: the things that depend on the clock's views go
 * before the clock, the clock before the media card it was squeezed for, and the session
 * listeners last, because the other groups still ask the controller questions as they let go.
 *
 * <p>Step 7's {@code save()/adopt()} pairs belong beside each group here, so that "what this
 * looks like when it goes" and "what the next generation needs to know" stay one decision.
 */
final class SystemUiRetire {

    private SystemUiRetire() {
    }

    /**
     * Takes the module down and puts it back up in the same generation. Only the dry run is
     * allowed to call this: a real retirement is HotReload.retire.
     */
    static String dryRun(Context ctx) {
        String proc = processName();
        if (!"com.android.systemui".equals(proc)) return "not SystemUI (" + proc + ")";
        if (Looper.myLooper() != Looper.getMainLooper()) return "not on the main thread";
        String busy = HotReload.busy();
        if (busy != null) {
            Xp.w(HotReload.TAG + "dry run deferred: " + busy);
            return "deferred: " + busy;
        }

        String before = Gen.report();
        List<String> warnings = new ArrayList<>();
        long started = SystemClock.uptimeMillis();
        // New work is refused while the teardown runs, so a feature's own "off" path cannot
        // post a refresh back into the tree we are about to sweep.
        Gen.quiescing = true;
        try {
            retireAll(warnings);
            if (!Gen.sweepForDryRun(2000L)) warnings.add("sweep timed out");
        } catch (Throwable t) {
            warnings.add("sweep: " + t);
        } finally {
            Gen.quiescing = false;
        }
        try {
            Main.reenterAfterDryRun(ctx);
        } catch (Throwable t) {
            warnings.add("reenter: " + t);
        }

        String out = "retire_dryrun ms=" + (SystemClock.uptimeMillis() - started)
                + " warnings=" + warnings
                + " before{" + before + "}"
                + " after{" + Gen.report() + "}";
        Xp.w(HotReload.TAG + "dry run: " + out);
        return out;
    }

    private static void retireAll(List<String> warnings) {
        group(warnings, "lyrics", SystemUiRetire::retireLyrics);
        group(warnings, "cover", SystemUiRetire::retireCover);
        group(warnings, "clock", SystemUiRetire::retireClock);
        group(warnings, "notifications", SystemUiRetire::retireNotifications);
        group(warnings, "fingerprint", SystemUiRetire::retireFingerprint);
        group(warnings, "depth", SystemUiRetire::retireDepth);
        group(warnings, "media card", SystemUiRetire::retireMediaCard);
        group(warnings, "islands", SystemUiRetire::retireIslands);
        group(warnings, "shade", SystemUiRetire::retireShade);
        group(warnings, "immersive", SystemUiRetire::retireImmersive);
        group(warnings, "sessions", Main::retireSessions);
    }

    /** One group's failure must not leave the rest of the teardown undone. */
    private static void group(List<String> warnings, String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            warnings.add(name + ": " + t);
        }
    }

    private static String processName() {
        try {
            return Application.getProcessName();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- the groups

    /** 1. The lyric page, its window, and the bridge whose callbacks point into this loader. */
    private static void retireLyrics() {
        LockLyrics.retire();
        LyriconSource.detach();
    }

    /** 2. The card layer, the morph copy, and the legacy cover view with its guard. */
    private static void retireCover() {
        CoverMorphLayer.cancel();
        CoverCardLayer.retire();
        CoverPush.releaseCoverGuard();
        Main.detachCover();
    }

    /** 3. The clock: squeeze given back, glass arrays restored, and the rows' room returned. */
    private static void retireClock() {
        ClockMove.detach();
        ClockCollapse.release("hot reload");
        ClockRoom.reassertClockRoom();
        ClockGlass.restoreGlass();
        ClockViews.sClockTargets.clear();
        ClockViews.forgetGlyphBox();
        Main.forgetClockRoots();
    }

    // Filled in with their features: batches 2 and 3.

    /**
     * 4. The default notification state the islands moved, the 8-element arrays the sink put in
     * the OEM's combine, and the presence answer (which the OEM takes back by itself once its
     * hook passes through).
     */
    private static void retireNotifications() {
        NumState.release();
        Main.sNotificationSink.restore();
        NotifPresence.recheck(true);
    }

    /** 5. The hidden print, its replayed frames and the quick-open bubbles. */
    private static void retireFingerprint() {
        Fingerprint.retire();
        FingerprintQuick.retire();
    }

    /**
     * 6. The cut-out back where the wallpaper put it. setDepthHidden(false) is the same door
     * cover mode uses, and it also releases the guard and the live wallpaper's surfaces.
     */
    private static void retireDepth() {
        Main.setDepthHidden(false);
        Main.releaseDepthGuard();
    }

    /** 7. The card listener out of the OEM's set, the guard off the card, the video released. */
    private static void retireMediaCard() {
        MediaCard.retire();
    }

    /**
     * 8. Every mini player controller's own teardown, then the island state they were registered
     * with, then the date line's segments and the indications it withheld from the OEM.
     */
    private static void retireIslands() {
        MiniPlayerRuntime.retire();
        LockIslands.retire();
        DateStatus.retire();
    }

    /** 9. The shade's GL backdrop, its window and thread, and both panels' progress. */
    private static void retireShade() {
        ShadeLayer.retire();
    }

    /**
     * 10. 高德's page and the slot it is drawn in, and the pickup island's receiver guard. The
     * ride cards are hooks and Gen-posted work, which the ledger has.
     */
    private static void retireImmersive() {
        ImmersiveHost.retire();
        PickupIsland.retire();
    }
}

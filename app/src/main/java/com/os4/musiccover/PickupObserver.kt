package com.os4.musiccover

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock

/**
 * An order waited on while the user is elsewhere - ColorOS's observe task for a pickup code
 * (Gleaner's `PickupCodeExecutor`, 取餐码-OPPO逆向/02), round for round:
 *
 *   gates     the task ends when it has run [DURATION] or the phone is at [MAX_TEMP]; a round is
 *             skipped while the phone is locked, offline, filming, or low on battery with the saver
 *             on and no charger (ColorOS's `ce.a.e()` gates, the ones this process can see).
 *   onNavi    the task is on the hidden display (PickupPark) - ColorOS moves it there for the round.
 *   onCollect the page is reloaded and read [REFRESH_WAIT] later.
 *   onExtract the front page's code, by the same recognizer the island uses.
 *   onDispatch the order's state, only ever forward; [EMPTY_MAX] rounds in a row without the order
 *             on the front page end the task (ColorOS's reason 3), and so does the order being over.
 *   interval  ColorOS's `c.f()`: ready → ready; the first round → first-after; making → making,
 *             anything else → ordered; a low battery stretches either to at least the low-battery gap.
 *
 * Two of ColorOS's moves are JS that it runs in 微信's WebView through its own framework, and are not
 * done here: `wx.reLaunch` back to the page the task was made on, and `wx.redirectTo` to reload it.
 * The first is why a task is only made from a page that shows the order ([PickupCodeIsland] checks
 * that before calling [start]) and why a page that stops showing it ends the task rather than being
 * sent back. The second is [PickupPark.cycleFrom], which puts the page through a hide and a show.
 *
 * And one is not done the same way: ColorOS borrows the task onto its display for each round and puts
 * it back at the *bottom* of the phone's display after (`moveTaskToDisplay(task, 0, false)`). The
 * platform has no bottom-of-the-display move - moveRootTaskToDisplay always lands on top - so the task
 * stays on the hidden display between rounds, and goes back once, when the task ends.
 *
 * All on [PickupCodeIsland.bg].
 */
internal object PickupObserver {

    private const val TAG = "MCPickupObs: "

    /** ColorOS's `execution_duration` for a pickup code (observe-config.json). */
    private const val DURATION = 30 * 60_000L
    /** ColorOS's `max_execute_temperature` (observe-config.json): at or over it, the task ends. */
    private const val MAX_TEMP = 43f
    /** After the task is moved: ColorOS's `E()` waits this long before the round goes on. */
    private const val NAVI_WAIT = 1_000L
    /** After the reload: ColorOS's `C()` waits this long before the page is read. */
    private const val REFRESH_WAIT = 6_000L
    /** Rounds in a row without the order on the page before the task ends (ColorOS's `resultEmptyCount`). */
    private const val EMPTY_MAX = 5

    private val ROUND = Any()
    private val bg get() = PickupCodeIsland.bg

    private var task = -1
    private var page: PickupIsland.Page? = null
    private var code = ""
    /** The furthest state the order has reached; ColorOS's `lastOrderStatus`, which only moves forward. */
    private var status: String? = null
    private var startedAt = 0L
    /** Rounds finished, ColorOS's `completedObserveCount`: the first one is followed by the first-after gap. */
    private var rounds = 0
    private var empty = 0
    private var gen = 0
    private var last = ""

    fun observing(t: Int) = t >= 0 && t == task

    /**
     * The order [code] on [p]'s task waited on, its state as of now [now]. The task is moved onto the
     * hidden display and the first round runs once it is there - ColorOS schedules its first at 0.
     */
    fun start(p: PickupIsland.Page, code: String, now: String?) {
        if (task >= 0) return
        task = p.task
        page = p
        this.code = code
        status = now
        startedAt = SystemClock.uptimeMillis()
        rounds = 0
        empty = 0
        val g = ++gen
        Xp.log(TAG + "observing task $task for $code ($now)")
        PickupPark.parkFrom(task)
        bg.postDelayed({ round(g) }, ROUND, NAVI_WAIT)
    }

    /**
     * The task over, for [why]: the page goes back to the phone's display - over what the user is in
     * only when [front] (the island was tapped) - and [then] runs after. Ending nothing still
     * releases whatever is parked, so this is the one way anything here asks for a release.
     */
    fun end(why: String, front: Boolean = false, then: (() -> Unit)? = null) {
        val t = task
        if (t >= 0) {
            Xp.log(TAG + "task $t ended: $why ($rounds rounds, ${(SystemClock.uptimeMillis() - startedAt) / 1000}s)")
            task = -1
            gen++
            bg.removeCallbacksAndMessages(ROUND)
        }
        PickupPark.releaseFrom(why, front, then)
        if (t >= 0) PickupCodeIsland.observeEnded(t)
    }

    fun describe(): String = if (task < 0) "obs: -" else
        "obs: task=$task code=$code status=$status rounds=$rounds empty=$empty " +
            "at=${(SystemClock.uptimeMillis() - startedAt) / 1000}s last=$last"

    private fun round(g: Int) {
        if (g != gen) return
        val ctx = Main.appContext() ?: return next(g)
        // Released by something that did not go through [end] - the probe, SystemUI's own display
        // going away: there is no page left to read.
        if (PickupPark.parked() != task) return end("the page is no longer parked")
        if (SystemClock.uptimeMillis() - startedAt >= DURATION) return end("${DURATION / 60_000} min")
        val temp = temperature(ctx)
        if (temp >= MAX_TEMP) return end("too hot: $temp°C")
        skip(ctx)?.let { why ->
            last = "skipped: $why"
            return next(g)
        }
        PickupPark.cycleFrom {
            bg.postDelayed({ extract(g) }, ROUND, REFRESH_WAIT)
        }
    }

    private fun extract(g: Int) {
        if (g != gen) return
        PickupCodeIsland.readOnce(task, frontOnly = true) { r, texts ->
            if (g == gen) dispatch(g, r, texts)
        }
    }

    private fun dispatch(g: Int, r: PickupParse.Result?, texts: Int) {
        rounds++
        if (r == null || r.code != code) {
            empty++
            last = if (r == null) "no code in $texts texts" else "another code: ${r.code}"
            if (empty >= EMPTY_MAX) return end("the order was not on the page $EMPTY_MAX times")
            return next(g)
        }
        empty = 0
        // Forward only: a page that shows a step bar can read a step behind, and a state the order
        // has passed is not one to show again.
        val shown = if (rank(r.status) >= rank(status)) r.status else status
        status = shown
        last = "$code ${shown ?: "-"}"
        page?.let { PickupIsland.show(PickupCodeIsland.enrich(r.copy(status = shown)), it) }
        if (shown != null && shown in PickupParse.DONE) return end("the order reads $shown")
        next(g)
    }

    private fun next(g: Int) {
        if (g != gen) return
        bg.postDelayed({ round(g) }, ROUND, interval())
    }

    /** ColorOS's `c.f(completedObserveCount, orderStatus, isLowBattery, resolved)`. */
    private fun interval(): Long {
        val cfg = PickupObserve.intervals
        if (PickupParse.isReady(status)) return cfg.ready
        if (rounds <= 1) return cfg.firstAfter
        val gap = if (PickupParse.isMaking(status)) cfg.making else cfg.ordered
        val ctx = Main.appContext() ?: return gap
        return if (lowBattery(ctx, cfg.lowBatteryPercent)) maxOf(gap, cfg.lowBattery) else gap
    }

    private fun rank(s: String?): Int = when {
        s == null -> 0
        s in PickupParse.DONE -> 4
        s in PickupParse.READY -> 3
        s in PickupParse.MAKING -> 2
        else -> 1
    }

    // ------------------------------------------------------------------ gates

    /** Why this round is skipped, or null. ColorOS's order: network, lock, camera, battery. */
    private fun skip(ctx: Context): String? {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val online = cm?.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!online) return "offline"
        // Locked, and only locked: a screen that is merely off is read on, as ColorOS does.
        if (ctx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return "locked"
        if (PickupCamera.inUse(ctx)) return "camera in use"
        if (lowBattery(ctx, PickupObserve.intervals.skipPercent)) return "battery"
        return null
    }

    /**
     * ColorOS's `StatusUtils.i(context, minBatteryLevel)`: under [percent], not charging, *and* the
     * battery saver on - all three, or it is not low.
     */
    private fun lowBattery(ctx: Context, percent: Int): Boolean {
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java) ?: return false
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val saver = ctx.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        return level in 0 until percent && !bm.isCharging && saver
    }

    /**
     * The battery's temperature, in °C. ColorOS reads its own thermal service
     * (`OplusHoraeThermalHelper.getCurrentThermal`), which is not on this phone; the battery's is the
     * one every Android phone reports. NaN when it cannot be read, which, as there, never trips the gate.
     */
    private fun temperature(ctx: Context): Float = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t == Int.MIN_VALUE) Float.NaN else t / 10f
    }.getOrDefault(Float.NaN)
}

/**
 * Whether a camera is open - ColorOS's "video call" gate (`ee.c.b()`, failure code -13), which it keeps
 * with an availability callback. Registered on first use and kept: the callback is how the platform
 * says which cameras are taken, and it says it once for each camera as soon as it is registered.
 */
private object PickupCamera {
    private val taken = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var watching = false

    fun inUse(ctx: Context): Boolean {
        if (!watching) {
            watching = true
            runCatching {
                ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
                    ?.registerAvailabilityCallback(object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
                        override fun onCameraAvailable(id: String) { taken.remove(id) }
                        override fun onCameraUnavailable(id: String) { taken.add(id) }
                    }, PickupCodeIsland.bg)
            }.onFailure { Xp.log("MCPickupObs: camera not watched: $it") }
        }
        return taken.isNotEmpty()
    }
}

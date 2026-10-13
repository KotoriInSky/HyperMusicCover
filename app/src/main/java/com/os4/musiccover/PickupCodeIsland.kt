package com.os4.musiccover

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * A mini program order page's pickup code, as a focus island - ColorOS's 取餐码 (Gleaner's
 * observeagent), the part of it that needs no background display: the code is read while the page
 * is open.
 *
 * ColorOS reads the page with ViewExtract, its own framework code inside the app's process. Here
 * it is the assist structure, asked for from SystemUI (which holds GET_TOP_ACTIVITY_INFO) by task:
 * IActivityTaskManager.requestAssistDataForTask. Nothing is hooked in 微信 or 支付宝 and no
 * accessibility service is used, and both platforms hand the page over - 微信's mini programs are
 * XWeb WebViews, whose DOM text comes back in the structure (755 and 823 nodes on two real order
 * pages, 5-17 ms), and 支付宝's are drawn with real views, which come back as text nodes
 * (697 nodes / 132 texts on a real order list, 12 ms). [PickupParse] finds the code in either.
 *
 * Only the mini programs [BRANDS] names are read, by the task's own label (the mini program's
 * name, 「霸王茶姬」); ColorOS's list is cloud config by appId, which SystemUI is not handed. Both
 * platforms give a mini program a task of its own, labelled with it, which is what makes the label
 * the brand; which activities those tasks are is asked of the rules XML
 * (`wx_mini_activity` / `ali_mini_activity`), not assumed. While one is in front it is read a
 * second after it arrives and every [EVERY] ms after, as the order list and the order are pages of
 * one task and moving between them changes nothing SystemUI hears. A code found is shown whatever
 * the order's state.
 *
 * This file is the tracker: which task is read, when, and for how long, the hidden-display park
 * ([PickupPark]) included. The page is read by [PickupAssist], the code found in it by
 * [PickupParse] (with [PickupOem]'s answer weighed in), and what is found is posted by
 * [PickupIsland], which owns everything about the card from there on.
 */
internal object PickupCodeIsland {

    const val TAG = "MCPickup: "
    const val SYSUI = "com.android.systemui"
    /** The app a tap falls back to, and the icon when the task carries none: it is the common case. */
    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    private const val TOP_OBSERVER = "com.miui.systemui.functions.MiuiTopActivityObserver"
    /**
     * The brand tag ColorOS hands its recognizer. Every brand in its config has one; a tag it does
     * not know is answered as `common`, which is what the plugin falls back to anyway - and the 47
     * brands' tags live in that config, which is not ours to read yet, so this is the honest empty
     * one rather than a guess at a name.
     */
    private const val OEM_APP = "common"

    private const val FIRST = 1_000L
    /** Reads after the first that come quickly, a page just brought back may still be filling. */
    private const val QUICK = 2
    private const val QUICK_GAP = 1_000L
    private const val RETRIES = 10
    private const val RETRY_GAP = 300L
    private const val EVERY = 5_000L
    /** How often the page is read while the mini program is not the front activity. */
    private const val SLOW = 30_000L
    /** How long a task is read on after the mini program leaves the front before it is let go. */
    private const val LEFT = 2 * 60_000L
    /** A parked task is let go after this, as ColorOS's observeagent does. */
    private const val PARK_MAX = 30 * 60_000L
    /** How long a task stays unparked after the island was tapped. */
    private const val NO_PARK = 5 * 60_000L
    /** Reads of one stay in front, at most: an hour at [EVERY]. */
    private const val MAX_READS = 720
    private const val TIMEOUT = 3_000L
    /** How long after the top-activity observer appears its state is read, for the front activity. */
    private const val STARTUP_LOOK = 2_000L

    /**
     * Tokens for what [bg] has queued: [READ] the read loop, [TRACK] the rest of the tracking (the
     * label retries, the [LEFT] timer). [stop] takes both away and nothing else - a card's fade
     * steps and a tap's work ([PickupIsland]'s, on the same thread) are not the tracker's to cancel.
     */
    private val READ = Any()
    private val TRACK = Any()

    /** The mini programs read, matched in the task label. */
    private val BRANDS = listOf(
        // ColorOS's own (Gleaner, assets/observeAgent/applet-wechat-observe-config.json, v5) ...
        "麦当劳", "肯德基", "瑞幸", "奈雪", "霸王茶姬", "CoCo", "茶百道", "古茗", "沪上阿姨", "书亦",
        "益禾堂", "1点点", "幸运咖", "挪瓦", "NOWWA", "库迪", "喜茶", "甜啦啦", "M Stand", "Manner",
        "太平洋咖啡", "星巴克", "去茶山", "kuddo", "阿嬷手作", "peet", "混果汁", "konomi", "丘大叔", "华莱士",
        // ... and more of the same kind.
        "luckin", "蜜雪冰城", "一点点", "都可", "七分甜", "柠季", "茶颜悦色", "Tims", "汉堡王", "塔斯汀",
        "必胜客", "德克士", "老乡鸡", "袁记",
    )

    /** The settings switch, kept with Main's state. On unless turned off. */
    @Volatile @JvmField var sOn = true

    /**
     * Whether the card is attached to the notification.
     *
     * **On, and it is the point of the card now**: this is HyperOS's own pickup card, taken from
     * 小爱同学's layout (`PickupCard`), so there is no longer a second card style to prefer - the
     * reason it was off was that the old one was ColorOS's and the system's own template read
     * better beside the system's island. Turned off, the template is back and the code is read
     * from the param alone (probe: `op pickup --ez card false`).
     */
    @Volatile @JvmField var sCard = true

    val bg: Handler by lazy { Handler(HandlerThread("mc-pickup").apply { start() }.looper) }

    // All below on [bg].
    private var lastTop: ComponentName? = null
    private var taskId = -1
    /** The app the tracked task belongs to - 微信 or 支付宝 - which is resumed behind its pages. */
    private var host = WECHAT
    /** Whether the tracked mini program is the front activity, which sets how often it is read. */
    private var inFront = false
    /** Until when the task is not parked again, set when the island is tapped (see [left]). */
    private var noParkUntil = 0L
    private var brand = ""
    /** The mini program the page being read belongs to, out of its own launch intent. */
    private var program: PickupAssist.Program? = null
    /** This page's entry in ColorOS's rules - its colours, pictures and brand tag, or null. */
    private var oemRule: PickupRule? = null
    /** What ColorOS's recognizer last answered, for the probe's `describe()`. Not shown to anyone. */
    private var lastOem = ""
    private var reads = 0
    /** The read waiting on its answer, so a late answer after its timeout is not a second loop. */
    private var readSeq = 0
    private var inFlight = false
    /** Reads finished since this task was parked, which is what OPPO's first-after interval counts. */
    private var parkedReads = 0
    /** The task [parkedReads] belongs to, so a new park starts the count over. */
    private var parkedTask = -1
    private var gen = 0
    private var lastRead = ""
    /** The front page's first texts when no code was found, for the probe only (not logged). */
    private var lastHead = ""

    fun install(cl: ClassLoader) {
        runCatching {
            Xp.hookAll(Xp.findClass(TOP_OBSERVER, cl), "updateTopActivity") { chain ->
                val out = chain.proceed()
                runCatching {
                    val state = Xp.getObjectField(chain.thisObject, "mState")
                    val top = Xp.getObjectField(state, "topActivity") as? ComponentName
                    bg.post { front(top) }
                }
                out
            }
            Xp.log(TAG + "watching the front activity")
        }.onFailure { Xp.log(TAG + "front activity not watched: $it") }

        // And whatever is in front *now*, because the observer above only speaks when the front
        // activity changes. A SystemUI restart - which is how a new build is picked up - leaves the
        // mini program sitting in front with nothing changing, so the tracker would stay empty until
        // the user moved away and came back, which reads as "the island stopped working".
        //
        // Asked of ActivityManager rather than of the observer's own state: that state is only
        // filled in on the way through an update, and the observer may well have been built before
        // this module's hooks went in. Only the mini program is handed on - `front` is what decides
        // what is in front, and a wrong name there would stick.
        bg.postDelayed({
            runCatching {
                val ctx = Main.appContext() ?: return@runCatching
                @Suppress("DEPRECATION")
                val top = ctx.getSystemService(ActivityManager::class.java)
                    .getRunningTasks(1).firstOrNull()?.topActivity
                if (top != null && miniHost(ctx, top) != null) {
                    Xp.log(TAG + "starting on " + top.shortClassName)
                    front(top)
                }
            }
        }, STARTUP_LOOK)

        PickupIsland.install()
    }

    fun setOn(on: Boolean) {
        sOn = on
        bg.post {
            if (!on) {
                stop()
                PickupIsland.takeDown("switched off")
                PickupPark.releaseFrom("switched off")
            }
        }
    }

    /** The task the front mini program is being read from, -1 for none (the probe's `do vdtest`). */
    fun trackedTask(): Int = taskId

    /** The island was tapped: the user is going into the mini program, so it is not parked for [NO_PARK]. */
    fun tapped() {
        noParkUntil = SystemClock.uptimeMillis() + NO_PARK
    }

    /** The card's tap receiver, in place as soon as this process has a Context ([PickupIsland.wake]). */
    fun wake(ctx: Context) = PickupIsland.wake(ctx)

    /**
     * One read of an arbitrary task, for the park experiment (the probe's `do vdtest`): the assist
     * request a tracked read makes, handed back parsed and counted rather than shown. [done] runs on
     * [bg]; the island's own state is not touched.
     */
    fun readOnce(task: Int, done: (PickupParse.Result?, Int) -> Unit) {
        // Answered once: the assist reply, or [TIMEOUT]. A request that is never answered would
        // otherwise park the caller's loop for good.
        val said = java.util.concurrent.atomic.AtomicBoolean(false)
        fun once(r: PickupParse.Result?, n: Int) {
            if (said.compareAndSet(false, true)) bg.post { done(r, n) }
        }
        val timeout = Runnable { once(null, 0) }
        bg.postDelayed(timeout, TIMEOUT)
        val asked = PickupAssist.request(task) { st, ex ->
            bg.post {
                bg.removeCallbacks(timeout)
                // Read for the experiment only: the tracked page's appId and brand stay as they are.
                val nodes = if (st != null) runCatching { PickupAssist.nodes(st) }.getOrNull() else null
                once(nodes?.let { recognize(it, oemRule) }, nodes?.size ?: 0)
            }
        }
        if (!asked) {
            bg.removeCallbacks(timeout)
            once(null, 0)
        }
    }

    // Built rather than concatenated: an `if` in the middle of a `+` chain swallows what follows
    // it into its own else branch, which is how the oem= segment went missing from this line while
    // the recognizer behind it was answering all along.
    fun describe(): String = buildString {
        append("on=$sOn task=$taskId brand=$brand reads=$reads shown=${PickupIsland.showing}")
        program?.let { append(" appId=${it.appId}") }
        program?.name?.takeIf { it.isNotEmpty() }?.let { append(" name=$it") }
        append(" up=${Main.appContext()?.let { PickupIsland.up(it) }}")
        append(" last=$lastRead")
        if (lastHead.isNotEmpty()) append(" head=[$lastHead]")
        if (lastOem.isNotEmpty()) append(" oem=[$lastOem]")
    }

    /**
     * The probe's `do=read`: one read now, of whatever is tracked. It takes the place of the read
     * that was queued rather than starting a loop beside it; with one already waiting on its answer,
     * that answer is the read.
     */
    fun readNow() = bg.post {
        if (taskId < 0 || inFlight) return@post
        bg.removeCallbacksAndMessages(READ)
        read(taskId, gen)
    }

    /**
     * The app whose mini program [top] is, or null when it is not one.
     *
     * Asked of the rules rather than of the one class name this file used to carry:
     * `wx_mini_activity` and `ali_mini_activity` are the containers ColorOS follows, and 支付宝
     * spreads its mini programs over twenty of them (`XRiverActivity$App01` and its siblings),
     * so a name written here would be wrong on the other platform and stale on this one.
     */
    private fun miniHost(ctx: Context, top: ComponentName): String? {
        val policy = runCatching { PickupRules.get(ctx).recognitionPolicy() }.getOrNull() ?: return null
        return if (policy.isMiniProgramActivity(top.packageName, top.className)) top.packageName else null
    }

    /**
     * [top] is MiuiTopActivityObserver's, which can be ahead of the task list: the task it names
     * may not be listed yet, or not carry its label yet (微信 sets a mini program's label once
     * the mini program is up). Looked for again then, [RETRIES] times.
     */
    private fun front(top: ComponentName?, retry: Int = 0) {
        if (retry == 0) {
            if (top == lastTop) return
            lastTop = top
        } else if (top != lastTop) {
            return
        }
        // No context is read as "not a mini program" rather than as a reason to stop looking: the
        // top must still be handed on, or a task being kept is never let go.
        val ctx = Main.appContext()
        val pkg = if (sOn && ctx != null && top != null) miniHost(ctx, top) else null
        if (ctx == null || top == null || pkg == null) {
            // A parked task must not stay on the hidden display once its own app is in front again:
            // the mini program tapped from its list would come back on a display nobody can see.
            if (top != null && top.packageName == host) PickupPark.releaseFrom("its app is in front again")
            left(top)
            return
        }
        @Suppress("DEPRECATION")
        val info = runCatching {
            ctx.getSystemService(ActivityManager::class.java).getRunningTasks(8)
                .firstOrNull { it.topActivity == top }
        }.getOrNull()
        val label = info?.taskDescription?.label.orEmpty()
        // Both platforms give a mini program a task of its own, and normally name it - but not
        // always: 支付宝 runs one inside the app's own task, which carries no description at all
        // (measured 2026-10-09, 蜜雪冰城's page sitting in task 44419), so on 支付宝 the task is
        // taken without a label and the page itself is asked for its appId on the first read.
        if (info == null || (label.isEmpty() && pkg != ALIPAY)) {
            if (retry < RETRIES) bg.postDelayed({ front(top, retry + 1) }, TRACK, RETRY_GAP)
            else Xp.log(TAG + "no labelled task for ${top.shortClassName}")
            return
        }
        // The list still scopes 微信, whose pages are a whole app's - a chat or a search result can
        // name a brand without being one. 支付宝's scope is the appId its page carries, which is
        // part of the page rather than of the task, and is checked where the page is read.
        if (pkg == WECHAT && BRANDS.none { label.contains(it, ignoreCase = true) }) {
            Xp.d(TAG + "mini program not on the list: $label")
            stop()
            return
        }
        // Both platforms give a mini program a task of its own, so the same app can be tracking a
        // different one than it did before - which is why this is set before the task is compared.
        host = pkg
        if (info.taskId == taskId) {
            inFront = true
            return
        }
        stop()
        taskId = info.taskId
        brand = label
        reads = 0
        inFront = true
        val g = gen
        Xp.log(TAG + "tracking task $taskId ($label)")
        bg.postDelayed({ read(taskId, g) }, READ, FIRST)
    }

    /**
     * The front activity is not the mini program, or not any more. Being in front is how a task is
     * found, not what it is read for: the app's own pages, and whatever comes over the mini program
     * while the user is still in it, arrive as a top that is not it - and with the top settling back
     * on the mini program no further event comes, so a read loop killed here never came back. A
     * second order in the same mini program stopped updating the island that way (2026-10-08: the
     * task was dropped and its reads froze at 7 while the user sat in the mini program). So the task
     * is kept and read on, slower; only [LEFT] spent without being in front ends it - the app's own
     * pages are worth being read over, the phone in a pocket is not.
     */
    private fun left(top: ComponentName?) {
        if (taskId < 0) {
            stop()
            return
        }
        if (!inFront) return
        inFront = false
        Xp.log(TAG + "left the mini program: top=" + (top?.flattenToShortString() ?: "none") +
            ", reading task $taskId on")
        // Whether the page is kept off-screen is the one thing about this path that the card cannot
        // show, and it decides whether the order is read at all from here on: unparked, the task is
        // let go after [LEFT] and the card freezes at whatever it last read - the state a drink that
        // was still being made then never leaves (2026-10-10).
        val parkable = top?.packageName != host && waiting() && observes() &&
            SystemClock.uptimeMillis() >= noParkUntil
        Xp.log(TAG + "left: park=" + parkable + " (waiting=" + waiting() + " observes=" + observes()
            + " noParkFor=" + ((noParkUntil - SystemClock.uptimeMillis()).coerceAtLeast(0) / 1000) + "s)")
        // Out of the app altogether: the order is worth waiting on, so the task goes onto the hidden
        // display where the mini program keeps its own page current (PickupPark, §6). Not while the
        // app itself is in front: a mini program started from its own list would come back on a
        // display nobody can see, and the app coming to the front releases it for the same reason.
        // Not right after the island was tapped either: the user is in the mini program, and the
        // front flickers through the launcher as they move about it - parking on that flicker took
        // the mini program away from them mid-use (2026-10-08). And not at all unless the rules ask
        // for it: `use_observer` is ColorOS's own switch for the polling this park is.
        if (parkable) {
            PickupPark.parkFrom(taskId)
            return
        }
        bg.postDelayed({ if (!inFront && taskId >= 0 && PickupPark.parked() != taskId) stop() }, TRACK, LEFT)
    }

    /**
     * Whether the rules allow a page to be kept off-screen at all - ColorOS's `use_observer`,
     * 「启用帮看轮询服务刷新订单状态」. Their observeagent is that polling; the park here is the same
     * thing done with the framework's own hidden display, so it is what the key gates. Off, or
     * unreadable, the task is still read while it is in front and dropped when it is left.
     */
    private fun observes(): Boolean {
        val ctx = Main.appContext() ?: return false
        return runCatching { PickupRules.get(ctx).recognitionPolicy().useObserver }.getOrDefault(false)
    }

    /**
     * Whether the order being shown is one whose state is still worth waiting for - a code is up and
     * the order is not over. A park is what the wait costs, so it is not made for nothing.
     */
    private fun waiting(): Boolean =
        PickupIsland.showing && !PickupParse.isOver(PickupIsland.status)

    private fun battery(ctx: Context): Int =
        ctx.getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    /**
     * A battery saver on, or a phone running hot. Neither is something ColorOS's observe config
     * has a key for - its two battery keys are in [PickupObserve] - and neither is a level to wait
     * out, so a read is skipped rather than slowed. ColorOS also skips while a game is being played
     * or the camera is open; neither is something this process can see for nothing, so neither is
     * here.
     */
    private fun tired(ctx: Context): Boolean {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isPowerSaveMode ||
            pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE
    }

    private fun stop() {
        gen++
        taskId = -1
        inFront = false
        inFlight = false
        PickupPark.releaseFrom("the tracked task was let go")
        bg.removeCallbacksAndMessages(READ)
        bg.removeCallbacksAndMessages(TRACK)
    }

    private fun read(task: Int, g: Int) {
        if (g != gen || task != taskId || inFlight) return
        val ctx = Main.appContext() ?: return
        val parked = PickupPark.parked() == task
        if (parked && parkedTask != task) {
            parkedTask = task
            parkedReads = 0
        }
        if (!parked) parkedTask = -1
        if (parked) {
            // §6.3's gates, in the order the doc has them: below 10% the wait is over, and a low
            // battery, a battery saver or a phone running hot skip a read. The park's own clock is
            // asked, not one kept here: the probe parks tasks too, and one of those would look like
            // a park that had already lasted [PARK_MAX].
            val level = battery(ctx)
            val since = PickupPark.sinceMs()
            when {
                since > 0 && SystemClock.uptimeMillis() - since > PARK_MAX -> {
                    Xp.log(TAG + "parked for ${PARK_MAX / 60_000} min")
                    PickupPark.releaseFrom("parked long enough")
                }
                level in 0..PickupObserve.intervals.skipPercent -> {
                    Xp.log(TAG + "battery $level%: the wait is over")
                    PickupPark.releaseFrom("battery $level%")
                }
                tired(ctx) -> {
                    next(task, g)
                    return
                }
            }
            parkedReads++
        }
        // Not while the screen is off or the phone is locked, parked or not: the page is not being
        // looked at, and a parked order waiting behind a locked screen is where these reads spent
        // most of their time. ColorOS skips them there too. The timer is left running, so the first
        // read after the phone is picked up is at most one interval away - the lock screen's island
        // shows the state as of the last read until then.
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        val keyguard = ctx.getSystemService(android.app.KeyguardManager::class.java)
        if (power?.isInteractive == false || keyguard?.isKeyguardLocked == true) {
            next(task, g)
            return
        }
        // Counted here, where a read is really made: the skipped ones above are the phone in a
        // pocket, and an hour of those used to spend the whole allowance. Spent, the task is let go
        // out loud, and [lastTop] is forgotten so that coming back to the mini program finds it
        // again - it used to stay tracked and never be read, which looked like a frozen island.
        if (++reads > MAX_READS) {
            Xp.log(TAG + "$MAX_READS reads of task $task: letting it go")
            stop()
            lastTop = null
            return
        }
        val t0 = SystemClock.uptimeMillis()
        // One answer per read, whichever comes first: the reply, or the timeout. A reply that came
        // after its timeout had already moved the loop on used to start a second loop beside it.
        val seq = ++readSeq
        inFlight = true
        val timeout = Runnable {
            if (g == gen && seq == readSeq && inFlight) {
                inFlight = false
                lastRead = "timeout"
                next(task, g)
            }
        }
        bg.postDelayed(timeout, TIMEOUT)
        val asked = PickupAssist.request(task) { st, ex ->
            bg.post {
                bg.removeCallbacks(timeout)
                if (g != gen || seq != readSeq || !inFlight) return@post
                inFlight = false
                program = PickupAssist.programOf(ex)
                // 支付宝's mini program pages carry their appId in their own launch intent; a page
                // in the same container without one is not a mini program page - an H5 or a service
                // page reached the same way, which is where reading every XRiverActivity would end
                // up. Asked here rather than up front because the appId is part of the page, not of
                // the task, and only exists once there is a page to read. A page whose text did
                // come back but whose appId did not is left alone rather than tracked on nothing.
                if (host == ALIPAY && st != null && program == null) {
                    Xp.d(TAG + "no appId on the page: not a mini program" +
                        if (ex == null) " (nothing came back with the structure)" else " (extras, but no appId)")
                    lastRead = "no appId"
                    stop()
                    return@post
                }
                val nodes = if (st != null) runCatching { PickupAssist.nodes(st) }.getOrNull() else null
                if (nodes != null) {
                    val (rule, name) = brandOf(nodes.joinToString("\n") { it.text })
                    oemRule = rule
                    brand = name
                }
                val r = nodes?.let { recognize(it, oemRule) }
                val ms = SystemClock.uptimeMillis() - t0
                lastRead = when {
                    st == null -> "no structure (${ms}ms)"
                    nodes == null -> "unreadable (${ms}ms)"
                    r == null -> "no code in ${nodes.size} texts (${ms}ms)"
                    else -> "code found in ${nodes.size} texts (${ms}ms)"
                }
                if (r != null) {
                    lastHead = ""
                    Xp.d(TAG + "$brand: ${r.code} ${r.label} ${r.status} ${r.store}")
                    PickupIsland.show(r, PickupIsland.Page(task, host, brand, oemRule, program?.scheme.orEmpty()))
                    // §6.6, and one state further: an order that is ready to collect is nothing to
                    // wait for any more, so the park ends there and not only at 已完成 - the wait is
                    // for the change, and the user is on their way to the counter.
                    if (PickupParse.isOver(r.status)) PickupPark.releaseFrom("the order reads ${r.status}")
                } else if (nodes != null) {
                    val front = nodes.maxOfOrNull { it.page } ?: -1
                    lastHead = "page $front: " + nodes.filter { it.page == front }.take(12)
                        .joinToString("|") { it.text.take(12) }
                }
                next(task, g)
            }
        }
        if (!asked) {
            bg.removeCallbacks(timeout)
            inFlight = false
            lastRead = "not asked"
            next(task, g)
        }
    }

    private fun next(task: Int, g: Int) {
        if (g != gen || task != taskId) return
        val gap = when {
            PickupPark.parked() == task -> parkedGap()
            reads <= QUICK -> QUICK_GAP
            inFront -> EVERY
            else -> SLOW
        }
        bg.postDelayed({ read(task, g) }, READ, gap)
    }

    /** OPPO's intervals for an order being waited on: what it is doing decides how often to look. */
    private fun parkedGap(): Long {
        val cfg = PickupObserve.intervals
        val s = PickupIsland.status
        if (PickupParse.isReady(s)) return cfg.ready
        if (parkedReads <= 1) return cfg.firstAfter
        val gap = if (PickupParse.isMaking(s)) cfg.making else cfg.ordered
        val level = Main.appContext()?.let { battery(it) } ?: -1
        return if (level in 1 until cfg.lowBatteryPercent) maxOf(gap, cfg.lowBattery) else gap
    }

    /**
     * The page's code: ColorOS's own recognizer first, this file's reading of the page second.
     *
     * [PickupOem] is OPPO's plugin - the one ColorOS's 流体云 runs - and it answers for a page by
     * its rules; when the rules find nothing it would fall to its NER model, which is the part left
     * out of the bundle we ship (see [PickupOem]). So this is OPPO's first rung and none of its
     * second: a page the rules cannot read falls through to [PickupParse] exactly as before.
     *
     * The plugin is handed the front page only - ColorOS reads the page in front, by its
     * `innerText`, and the stack behind it holds the order list with every old order's code on it.
     * Whether its code is taken at all is [PickupParse.choose]'s to say: its code labels include the
     * order states, so it will read a shop's queue length as a code.
     *
     * Reads nothing but its arguments, and writes only [lastOem], which is the probe's.
     */
    private fun recognize(nodes: List<PickupParse.Node>, rule: PickupRule?): PickupParse.Result? {
        val texts = PickupParse.frontPage(nodes).map { it.text }
        val out = PickupOem.recognize(texts, rule?.tagAppName?.takeIf { it.isNotEmpty() } ?: OEM_APP, "")
        if (out == null) {
            lastOem = "off"
            return PickupParse.choose(nodes, null)
        }
        // Said whether or not a code came out, so the probe can tell "did not run" from "ran and
        // found nothing" from "answered" - three different things to go and fix.
        lastOem = (out.code.ifEmpty { "none" }) + " ${out.status} pt=${out.processType}" +
            " page=${out.orderPage} n=${texts.size}" +
            (if (out.product.isEmpty()) "" else " ${out.product}") +
            (if (out.orderTime.isEmpty()) "" else " ${out.orderTime}") +
            (if (out.temperature.isEmpty()) "" else " ${out.temperature}")
        val r = PickupParse.choose(nodes,
            PickupParse.Oem(out.code, out.orderPage, out.status, out.product, out.temperature))
        if (out.code.isNotEmpty() && r?.code != out.code.trim().removePrefix("#")) {
            lastOem += " (not taken)"
        }
        return r
    }

    /**
     * The brand of the page being read: its entry in ColorOS's rules, and what to call it.
     *
     * Four ways in, most exact first:
     *  1. the appId the page's own launch intent carries, matched against a rule's `origin_id` -
     *     which is how 支付宝's entries in the config are keyed, and needs no guess;
     *  2. the task label, scored together with the page's text ([PickupRules.matchPage]) - how the
     *     微信 brands are found, and still the better answer there because the text says what the
     *     label cannot;
     *  3. the name, searched by [PickupRules.matchBrand] across **both** platforms, because a brand
     *     is one company whichever app it is reached through: 蜜雪冰城's rule is written for 微信
     *     and names the same logo, the same drink and the same red, so it draws its 支付宝 card too;
     *  4. nothing, and the card is drawn from the order's own drink ([PickupArt.model]).
     *
     * The name is a heuristic - it is the query the page was opened from, so it is the brand in the
     * common case and something else when it is not - and its failure is harmless: 「奶茶」 finds no
     * rule anywhere and falls to 4. Identity is always the appId; the name only chooses artwork.
     */
    private fun brandOf(content: String): Pair<PickupRule?, String> {
        val rules = Main.appContext()?.let { runCatching { PickupRules.get(it) }.getOrNull() }
        val appId = program?.appId.orEmpty()
        if (appId.isNotEmpty()) {
            rules?.ruleForOrigin(host, appId)?.let { return it to brand.ifEmpty { it.label } }
            // 微信's appId is not what the rules are keyed by - they use the `gh_…` original id -
            // and the cloud's applet list is the only thing here carrying one for the other. That
            // is half of what fetching it is for; the other half is its `disabled` flag.
            if (host == WECHAT) {
                val origin = PickupCloud.originOf(appId)
                if (origin != null) {
                    rules?.ruleForOrigin(WECHAT, origin)
                        ?.let { return it to brand.ifEmpty { it.label } }
                }
            }
        }
        if (brand.isNotEmpty()) {
            rules?.matchPage(host, brand, content)?.let { return it to brand.ifEmpty { it.label } }
        }
        val name = program?.name?.takeIf { it.isNotEmpty() } ?: brand
        if (name.isNotEmpty()) {
            rules?.matchBrand(name)?.let { return it to brand.ifEmpty { it.label } }
        }
        // Nothing corroborated the name, so nothing is claimed: the task label still stands - it is
        // the mini program's own name, which is what 微信 puts there - but a search query that
        // matched no rule is not shown as a brand.
        return null to brand
    }


}

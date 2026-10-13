package com.os4.musiccover

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.service.notification.StatusBarNotification

/**
 * 小爱's 「记忆」 read of the order page, asked for by the module - what a three-finger swipe up
 * does, and the answer kept to ourselves.
 *
 * The swipe is the system's shortcut `three_gesture_up = launch_ai_memory`, which starts 小爱's
 * VoiceService with ACTION_ASSIST and `voice_assist_start_from_key = three_gesture_up`; 小爱 takes a
 * screenshot, sends it to Xiaomi's cloud (AICR's AI memory), and posts the order as a focus
 * notification on `memory_island_channel` - code in the title, the item in the text, the shop in the
 * sub-text (2026-10-13: 740 / 芋泥啵啵奶绿 / 广州番禺广工东食堂店, 3.4s after the intent). That is the
 * one reading of the item that is right where the page's own row cannot be read, and it is ColorOS's
 * third rung in kind: its cloud is asked for the item too, once a code has been read on the phone.
 *
 * A swipe the user makes is theirs and shows as 小爱 shows it. One the module makes is asked for
 * [WINDOW], and whatever 小爱 posts in that time - its 「记忆中」 placeholder, the answer, its own
 * running-service notice - is read and then not let into SystemUI at all: it is stopped at
 * MiuiNotificationListener.onNotificationPosted, the one door the island, the shade, the lock
 * screen and the back screen (SubScreenNotificationController is fed from there) all come
 * through. The keys stopped are remembered, so 小爱's later updates of them stop there too; its
 * removals go through and find nothing, which the collection only logs.
 */
internal object XiaoaiMemory {

    private const val TAG = "MCXiaoai: "
    private const val VA = "com.miui.voiceassist"
    private const val SERVICE = "com.xiaomi.voiceassistant.VoiceService"
    private const val CHANNEL = "memory_island_channel"
    /** The extra only 小爱's scene card (the order) carries. */
    private const val SCENE = "miui.focus.param.custom"
    private const val LISTENER = "com.android.systemui.statusbar.notification.MiuiNotificationListener"
    /** How long after asking 小爱's notifications are taken as the answer: it took 3.4s, measured. */
    private const val WINDOW = 20_000L

    /** What 小爱 read off the page. */
    data class Answer(val code: String, val product: String?, val store: String?)

    /** Called with each answer, on the thread the notification arrived on. */
    @Volatile var onAnswer: ((Answer) -> Unit)? = null

    @Volatile private var askedUntil = 0L
    @Volatile private var askedCode = ""
    /**
     * The keys stopped, and when (wall clock). Kept on disk as well: 小爱's card lives 12 hours in the
     * system, and a SystemUI restart in that time would hand it to SystemUI again with the rest of
     * the active notifications - not through onNotificationPosted, but out of
     * getActiveNotifications() when the listener connects, which is filtered here too.
     */
    private val stopped = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false
    /** 小爱's card's own timeout (its param's `timeout: 720` minutes): a stopped key is kept no longer. */
    private const val KEEP = 12 * 60 * 60_000L
    private const val FILE = "mc_xiaoai_stopped"

    /**
     * On the intent the module sends: the swipe's 「remember」 animation - the screenshot drawn
     * into a glowing shrink by `RememberMglTextureView` - is not played for it. A swipe of the
     * user's own carries no such extra and plays as before.
     */
    private const val QUIET = "com.os4.musiccover.xiaoai_quiet"
    /** ThreeGestureUpEffectWindow, R8-named (8.2.62.3916): `show(Bitmap)` adds and starts the effect. */
    private const val EFFECT = "com.xiaomi.voiceassistant.mainui.x2"
    private const val EFFECT_VIEW = "com.xiaomi.voiceassist.shaderanimation.mgl.remember.RememberMglTextureView"
    /** Until when the next effect is ours to drop (uptime, in 小爱's process); one effect, then 0. */
    @Volatile private var quietUntil = 0L

    /** In 小爱's own process: the quiet extra noticed, and the effect it asks for dropped. */
    fun handle(cl: ClassLoader) {
        runCatching {
            Xp.hookAll(Xp.findClass(SERVICE, cl), "onStartCommand") { chain ->
                val i = chain.args.firstOrNull() as? Intent
                if (i != null && runCatching { i.getBooleanExtra(QUIET, false) }.getOrDefault(false)) {
                    quietUntil = SystemClock.uptimeMillis() + 10_000L
                    Xp.log(TAG + "quiet ask")
                }
                chain.proceed()
            }
        }.onFailure { Xp.log(TAG + "asks not told apart, the effect plays: $it"); return }
        // By the window's own show(Bitmap), when the R8 name still holds one; otherwise at
        // addView, which show() wraps in its own catch and then starts nothing.
        val show = runCatching {
            Xp.findClass(EFFECT, cl).declaredMethods.firstOrNull {
                it.name == "show" && it.parameterTypes.contentEquals(arrayOf(android.graphics.Bitmap::class.java))
            }
        }.getOrNull()
        runCatching {
            if (show != null) {
                Xp.hook(show) { chain -> if (quiet()) null else chain.proceed() }
                Xp.log(TAG + "effect dropped at show")
            } else {
                Xp.hookAll(Xp.findClass("android.view.WindowManagerImpl", null), "addView") { chain ->
                    if (chain.args.firstOrNull()?.javaClass?.name == EFFECT_VIEW && quiet()) {
                        throw IllegalStateException("not shown: asked by MusicCover")
                    }
                    chain.proceed()
                }
                Xp.log(TAG + "no $EFFECT.show(Bitmap), effect dropped at addView")
            }
        }.onFailure { Xp.log(TAG + "effect not dropped: $it") }
    }

    private fun quiet(): Boolean {
        if (SystemClock.uptimeMillis() > quietUntil) return false
        quietUntil = 0L
        Xp.log(TAG + "remember effect skipped")
        return true
    }

    fun install(cl: ClassLoader) {
        runCatching {
            Xp.hookAll(Xp.findClass(LISTENER, cl), "onNotificationPosted") { chain ->
                val sbn = chain.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                // Guarded: a throw here would lose the notification for everyone.
                val stop = sbn != null && runCatching { take(sbn) }.getOrDefault(false)
                if (stop) null else chain.proceed()
            }
            Xp.log(TAG + "watching 小爱's notifications")
        }.onFailure { Xp.log(TAG + "小爱's notifications not watched: $it") }
        // The list SystemUI takes once, when its listener connects - a restart's way in.
        runCatching {
            Xp.hookAll(android.service.notification.NotificationListenerService::class.java,
                "getActiveNotifications") { chain ->
                val out = chain.proceed()
                if (chain.thisObject?.javaClass?.name != LISTENER || out !is Array<*>) return@hookAll out
                runCatching {
                    load()
                    if (stopped.isEmpty()) out
                    else {
                        val kept = out.filterIsInstance<StatusBarNotification>().filter { !stopped.containsKey(it.key) }
                        if (kept.size == out.size) out else kept.toTypedArray()
                    }
                }.getOrDefault(out)
            }
        }.onFailure { Xp.log(TAG + "active notifications not filtered: $it") }
    }

    /** The stopped keys from disk, once, minus the ones past [KEEP]. */
    private fun load() {
        if (loaded) return
        val f = file() ?: return
        loaded = true
        val now = System.currentTimeMillis()
        runCatching {
            if (f.isFile) f.readLines().forEach { line ->
                val tab = line.lastIndexOf('\t')
                val at = if (tab > 0) line.substring(tab + 1).toLongOrNull() else null
                if (at != null && now - at < KEEP) stopped[line.substring(0, tab)] = at
            }
        }.onFailure { Xp.log(TAG + "stopped keys not read: $it") }
    }

    private fun save() {
        val f = file() ?: return
        val now = System.currentTimeMillis()
        stopped.entries.removeIf { now - it.value >= KEEP }
        runCatching { f.writeText(stopped.entries.joinToString("") { it.key + "\t" + it.value + "\n" }) }
            .onFailure { Xp.log(TAG + "stopped keys not saved: $it") }
    }

    /** In SystemUI's device-protected storage, which is there before the phone is first unlocked. */
    private fun file(): java.io.File? = Main.appContext()?.let {
        java.io.File(it.createDeviceProtectedStorageContext().filesDir, FILE)
    }

    /**
     * 小爱 asked to read the page in front, for the order [code]. False when it could not be asked.
     * The page has to be the one in front: what 小爱 reads is a screenshot.
     */
    fun ask(ctx: Context, code: String): Boolean = runCatching {
        askedCode = code
        askedUntil = SystemClock.elapsedRealtime() + WINDOW
        ctx.startForegroundService(Intent(Intent.ACTION_ASSIST)
            .setComponent(ComponentName(VA, SERVICE))
            .putExtra("voice_assist_start_from_key", "three_gesture_up")
            .putExtra(QUIET, true))
        Xp.log(TAG + "asked for $code")
        true
    }.onFailure {
        askedUntil = 0L
        Xp.log(TAG + "not asked: $it")
    }.getOrDefault(false)

    /** Whether [sbn] is one of ours to read and keep out of SystemUI. */
    private fun take(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != VA) return false
        load()
        if (stopped.containsKey(sbn.key)) return true
        if (SystemClock.elapsedRealtime() > askedUntil) return false
        val n = sbn.notification
        val running = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        if (n.channelId != CHANNEL && !running) return false
        stopped[sbn.key] = System.currentTimeMillis()
        save()
        if (n.channelId == CHANNEL) {
            val x = n.extras
            val code = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            // Two senders share the channel (8.2.62.3916). The 「记忆中」 placeholder, and the plain
            // 「已记住」 when nothing on the page was an order, come from MemoryIslandManager: a title
            // of its own (`memory_island_summary_state_left_title`), 记忆中 as the text, and the stock
            // `miui.focus.param`. The order comes from MemorySceneIslandManager, the only one with
            // `miui.focus.param.custom` - a title alone let the placeholder through as an answer.
            if (code.isNotEmpty() && x.containsKey(SCENE)) {
                val answer = Answer(code,
                    x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.ifEmpty { null },
                    x.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.ifEmpty { null })
                Xp.log(TAG + "answer for $askedCode: ${answer.code} / ${answer.product} / ${answer.store}")
                // A few seconds more for the running-service notice that follows, then a swipe of the
                // user's own is the user's again.
                askedUntil = SystemClock.elapsedRealtime() + 3_000L
                onAnswer?.invoke(answer)
            }
        }
        return true
    }
}

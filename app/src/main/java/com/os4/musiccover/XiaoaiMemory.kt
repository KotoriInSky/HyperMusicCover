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
    private const val LISTENER = "com.android.systemui.statusbar.notification.MiuiNotificationListener"
    /** How long after asking 小爱's notifications are taken as the answer: it took 3.4s, measured. */
    private const val WINDOW = 20_000L

    /** What 小爱 read off the page. */
    data class Answer(val code: String, val product: String?, val store: String?)

    /** Called with each answer, on the thread the notification arrived on. */
    @Volatile var onAnswer: ((Answer) -> Unit)? = null

    @Volatile private var askedUntil = 0L
    @Volatile private var askedCode = ""
    private val stopped = java.util.Collections.synchronizedSet(HashSet<String>())

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
            .putExtra("voice_assist_start_from_key", "three_gesture_up"))
        Xp.log(TAG + "asked for $code")
        true
    }.onFailure {
        askedUntil = 0L
        Xp.log(TAG + "not asked: $it")
    }.getOrDefault(false)

    /** Whether [sbn] is one of ours to read and keep out of SystemUI. */
    private fun take(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != VA) return false
        if (sbn.key in stopped) return true
        if (SystemClock.elapsedRealtime() > askedUntil) return false
        val n = sbn.notification
        val running = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        if (n.channelId != CHANNEL && !running) return false
        stopped += sbn.key
        if (n.channelId == CHANNEL) {
            val x = n.extras
            val code = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            // The 「记忆中」 placeholder has no title; the answer has the code there.
            if (code.isNotEmpty()) {
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

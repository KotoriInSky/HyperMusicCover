package com.os4.musiccover.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Step 0's test trigger, for adb rather than a finger:
 *
 * <pre>
 * adb shell am start -n com.github.zyl6932.HyperMusicCover/.app.HotReloadTestActivity \
 *     --ez setid false --el hold 0
 * </pre>
 *
 * <p>An activity rather than a receiver because a broadcast never reaches a freshly installed
 * package (`Broadcast completed: result=0` even with FLAG_INCLUDE_STOPPED_PACKAGES); am start
 * does. It is translucent, finishes itself, and goes away with the rest of the spike.
 *
 * <p>Results are logcat lines under [TAG]: `TARGETS`, `TARGET`, `SUBMIT`, `RESULT`.
 */
class HotReloadTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val setId = intent.getBooleanExtra("setid", false)
        val hold = intent.getLongExtra("hold", 0L)
        LsposedService.init(this)
        Thread({
            try {
                // A freshly started process has to bind the framework service first.
                val deadline = SystemClock.elapsedRealtime() + 4_000
                while (LsposedService.service.value == null && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(100)
                }
                if (LsposedService.service.value == null) {
                    Log.i(TAG, "NO_TARGET LSPosed 服务未绑定")
                } else {
                    Log.i(TAG, "TARGETS\n${HotReloadTest.targets()}")
                    HotReloadTest.start(setId, hold) { }
                    Thread.sleep(5_500)
                }
            } catch (t: Throwable) {
                Log.i(TAG, "ERROR $t")
            }
        }, "hr-test").start()
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 7_000)
    }

    companion object {
        const val TAG = "MCHotReloadTest"
    }
}

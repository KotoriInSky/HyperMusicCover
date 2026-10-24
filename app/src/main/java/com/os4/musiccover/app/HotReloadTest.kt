package com.os4.musiccover.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.os4.musiccover.BuildConfig
import com.os4.musiccover.HotReload

/**
 * Step 0 of the hot-reload plan: the app side of the spike, and only the app side.
 *
 * <p>One process (Apple Music), two ways of installing the same three hooks, and the result
 * printed where the tester can see it. The real orchestrator in §4.8 of 热重载实现方案.md does
 * not exist yet; this file is deleted with the spike.
 *
 * <p>All calls are binder calls into LSPosed, so callers run them off the main thread. Results
 * are posted back to the main thread for Compose state.
 */
object HotReloadTest {

    private const val TAG = "MCHotReloadTest"

    const val APPLE = "com.apple.android.music"

    /** Every running target with the two fields the plan says to distrust (§2.3 U6), plus the API. */
    fun targets(): String {
        val service = LsposedService.service.value
        val api = if (service == null) "unbound" else "api=${service.apiVersion} ${service.frameworkName} ${service.frameworkVersion}"
        val running = LsposedService.runningTargets() ?: return "$api\nrunning targets unavailable"
        return buildString {
            append(api).append('\n')
            running.forEach {
                append(it.processName)
                append("  pid=").append(it.pid)
                append("  ").append(it.state)
                append("  versionCode=").append(it.loadedVersionCode)
                append('\n')
            }
        }.trimEnd()
    }

    /**
     * Submits one hot reload of Apple Music and returns the line to show until the callback
     * lands. [setId] is U3: install by id and leave the old handles installed, so a hook call
     * shows whether the framework replaced the old hook or stacked a second one.
     */
    fun start(setId: Boolean, holdMs: Long = 0, onResult: (String) -> Unit): String {
        val target = LsposedService.target(APPLE)
        if (target == null) {
            val line = "NO_TARGET Apple Music 未在运行（或 LSPosed 服务未绑定）"
            Log.i(TAG, line)
            return line
        }
        val extras = Bundle().apply {
            putInt(HotReload.KEY_PROTOCOL, HotReload.PROTOCOL)
            putString(HotReload.KEY_BUILD, "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            if (setId) putBoolean(HotReload.KEY_SET_ID, true)
            if (holdMs > 0) putLong(HotReload.KEY_HOLD, holdMs)
        }
        Log.i(TAG, "TARGET ${target.processName} pid=${target.pid} state=${target.state} versionCode=${target.loadedVersionCode}")
        val submitted = LsposedService.hotReload(target, extras) { result ->
            val full = "RESULT ${result.status()} pid=${target.pid} ${result.message().orEmpty()}".trim()
            Log.i(TAG, full)
            Handler(Looper.getMainLooper()).post { onResult(full) }
        }
        val now = "SUBMIT pid=${target.pid} ${target.state} versionCode=${target.loadedVersionCode} setId=$setId hold=$holdMs"
        Log.i(TAG, if (submitted) now else "SUBMIT_FAILED LSPosed 服务未绑定")
        return if (submitted) now else "LSPosed 服务未绑定"
    }
}

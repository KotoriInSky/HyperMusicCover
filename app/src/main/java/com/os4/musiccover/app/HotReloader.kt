package com.os4.musiccover.app

import android.os.Bundle
import android.util.Log
import com.os4.musiccover.BuildConfig
import com.os4.musiccover.HotReloadPolicy
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService

/**
 * The app's side of a hot reload: which of the running targets to try, in what order, and what
 * the answers mean.
 *
 * Only the app can make the decision. The retiring generation is the old APK's code, so it
 * cannot know that its own retirement is broken - that list lives in the new build.
 */
object HotReloader {
    private const val TAG = "MCHotReloadApp"

    enum class Plan { HOT_RELOAD, RESTART, SKIP }

    data class Report(
        val attempted: List<String>,
        val succeeded: List<String>,
        val failed: List<String>,
        val needsRestart: List<String>,
    ) {
        /** True only when something was tried and nothing was left needing a restart. */
        val allSucceeded: Boolean
            get() = attempted.isNotEmpty() && failed.isEmpty() && needsRestart.isEmpty()
    }

    fun plan(t: HookedTarget): Plan = plan(t.state, t.processName, t.loadedVersionCode)

    internal fun plan(
        state: HookedTarget.State,
        processName: String,
        loadedVersionCode: Long,
        supported: Set<String> = HotReloadPolicy.SUPPORTED,
        brokenRetire: Set<Int> = HotReloadPolicy.BROKEN_RETIRE,
        firstHotReloadVersion: Int = HotReloadPolicy.FIRST_HOT_RELOAD_VERSION,
    ): Plan {
        if (state != HookedTarget.State.STALE && state != HookedTarget.State.FAILED) return Plan.SKIP
        // Step 4: nothing is supported yet, so trying is how the refusal path gets exercised -
        // the module answers "process not supported" and the app falls back to restarting. Once
        // Step 5 adds the first process, one that is not on the list is restarted without the
        // round trip.
        if (supported.isEmpty()) return Plan.HOT_RELOAD
        if (processName !in supported) return Plan.RESTART
        if (brokenRetire.contains(loadedVersionCode.toInt())) return Plan.RESTART
        if (loadedVersionCode < firstHotReloadVersion.toLong()) return Plan.RESTART
        return Plan.HOT_RELOAD
    }

    /** The build the module logs with the reload, so a log can say which one was tried. */
    fun buildMark(): String = BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE

    /**
     * One batch, blocking. Call it off the main thread. Targets are tried in the order given;
     * a target that has gone (or is already current) does not cost a round trip.
     */
    fun run(service: XposedService, targets: List<HookedTarget>): Report {
        val planned = targets.map { it to plan(it) }
        val steps = planned.filter { it.second == Plan.HOT_RELOAD }.map { (target, _) -> step(service, target) }
        val outcomes = HotReloadBatch().run(steps)

        for (outcome in outcomes) {
            if (outcome.message != null) {
                Log.w(TAG, "${outcome.name}: ${outcome.status} ${outcome.message}")
            }
        }

        val succeeded = outcomes
            .filter { it.status == HotReloadBatch.Status.SUCCEEDED || it.status == HotReloadBatch.Status.PROCESS_DIED }
            .map { it.name }
        val failed = outcomes
            .filterNot { it.status == HotReloadBatch.Status.SUCCEEDED || it.status == HotReloadBatch.Status.PROCESS_DIED }
            .map { it.name }
        val restarts = planned.filter { it.second == Plan.RESTART }.map { it.first.processName }
        return Report(
            attempted = steps.map { it.name },
            succeeded = succeeded,
            failed = failed,
            needsRestart = (restarts + failed).distinct(),
        )
    }

    private fun step(service: XposedService, target: HookedTarget) = object : HotReloadBatch.Step {
        override val name: String = target.processName

        override fun start(onResult: (HotReloadBatch.Status, String?) -> Unit) {
            // The user may have done something between the page's snapshot and this step. A
            // process that is gone is a success (it starts on the new build), one that is
            // already current needs nothing, and one already reloading is a failure so the
            // card stays up rather than claiming a result nobody saw.
            val now = runCatching { service.runningTargets }.getOrNull()
            if (now != null) {
                val again = now.firstOrNull { it.pid == target.pid && it.uid == target.uid }
                when {
                    again == null -> {
                        onResult(HotReloadBatch.Status.PROCESS_DIED, "process exited; the next start is the new build")
                        return
                    }
                    again.state == HookedTarget.State.UP_TO_DATE -> {
                        onResult(HotReloadBatch.Status.SUCCEEDED, "already up to date")
                        return
                    }
                    again.state == HookedTarget.State.RELOADING -> {
                        onResult(HotReloadBatch.Status.IN_PROGRESS, "another reload is running")
                        return
                    }
                }
            }

            val extras = Bundle().apply {
                putInt(HotReloadPolicy.KEY_PROTOCOL, HotReloadPolicy.PROTOCOL)
                putString(HotReloadPolicy.KEY_BUILD, buildMark())
            }
            try {
                service.hotReloadModule(target, extras) { _, result ->
                    onResult(map(result.status()), result.message())
                }
            } catch (t: Throwable) {
                Log.w(TAG, "hotReloadModule threw for ${target.processName}: $t")
                onResult(HotReloadBatch.Status.FAILED, t.toString())
            }
        }
    }

    private fun map(status: HotReloadResult.Status): HotReloadBatch.Status = when (status) {
        HotReloadResult.Status.SUCCEEDED -> HotReloadBatch.Status.SUCCEEDED
        HotReloadResult.Status.FAILED -> HotReloadBatch.Status.FAILED
        HotReloadResult.Status.UNSUPPORTED -> HotReloadBatch.Status.UNSUPPORTED
        HotReloadResult.Status.IN_PROGRESS -> HotReloadBatch.Status.IN_PROGRESS
        HotReloadResult.Status.PROCESS_DIED -> HotReloadBatch.Status.PROCESS_DIED
    }
}

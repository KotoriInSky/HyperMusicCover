package com.os4.musiccover.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs hot reloads one at a time and waits for each answer.
 *
 * The framework serialises reloads per process, but a batch still has to be serial itself: the
 * results arrive on binder threads, one may never arrive, and a duplicate or late answer must
 * not move the next step. The first answer for a step wins; nothing after it is looked at.
 *
 * A refusal with no message is how the retiring generation says "busy, try again" (see the
 * hot-reload plan, §4.8). That is the only retryable answer, and it is retried once, which is
 * what the deployment script for the reference implementation does.
 */
internal class HotReloadBatch(
    private val timeoutMs: Long = 10_000L,
    private val retryDelayMs: Long = 1_500L,
    private val retries: Int = 1,
) {

    enum class Status { SUCCEEDED, FAILED, UNSUPPORTED, IN_PROGRESS, PROCESS_DIED, TIMEOUT }

    data class Outcome(val name: String, val status: Status, val message: String?)

    interface Step {
        val name: String

        /** Start one attempt. The callback may be called on any thread, late, or twice. */
        fun start(onResult: (Status, String?) -> Unit)
    }

    /** One outcome per step, in order. Blocking: call it off the main thread. */
    fun run(steps: List<Step>): List<Outcome> {
        val out = ArrayList<Outcome>(steps.size)
        for (step in steps) out.add(runOne(step))
        return out
    }

    private fun runOne(step: Step): Outcome {
        var attempt = 0
        while (true) {
            val latch = CountDownLatch(1)
            val answer = AtomicReference<Pair<Status, String?>?>(null)
            val done = AtomicBoolean(false)
            step.start { status, message ->
                // First answer wins. A duplicate is a framework bug or a late one; either way
                // the step it belongs to is over.
                if (done.compareAndSet(false, true)) {
                    answer.set(status to message)
                    latch.countDown()
                }
            }
            val got = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val a = answer.get()
            if (!got || a == null) return Outcome(step.name, Status.TIMEOUT, null)
            val (status, message) = a
            if (status == Status.FAILED && message == null && attempt < retries) {
                attempt++
                if (retryDelayMs > 0) Thread.sleep(retryDelayMs)
                continue
            }
            return Outcome(step.name, status, message)
        }
    }
}

package com.os4.musiccover.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The batch is what turns binder callbacks into one decision. Its two promises: one answer per
 * step, and a step that never answers does not hold the rest of the batch.
 */
class HotReloadBatchTest {

    private fun step(
        name: String,
        body: ((HotReloadBatch.Status, String?) -> Unit) -> Unit,
    ) = object : HotReloadBatch.Step {
        override val name: String = name
        override fun start(onResult: (HotReloadBatch.Status, String?) -> Unit) = body(onResult)
    }

    @Test
    fun runsSeriallyInOrder() {
        val order = mutableListOf<String>()
        val batch = HotReloadBatch(timeoutMs = 500, retryDelayMs = 0)
        val outcomes = batch.run(listOf(
            step("a") { done -> order.add("a"); done(HotReloadBatch.Status.SUCCEEDED, null) },
            step("b") { done -> order.add("b"); done(HotReloadBatch.Status.SUCCEEDED, null) },
        ))
        assertEquals(listOf("a", "b"), order)
        assertEquals(listOf("a", "b"), outcomes.map { it.name })
        assertTrue(outcomes.all { it.status == HotReloadBatch.Status.SUCCEEDED })
    }

    @Test
    fun duplicateAnswerIsIgnored() {
        val batch = HotReloadBatch(timeoutMs = 500, retryDelayMs = 0)
        val outcomes = batch.run(listOf(
            step("a") { done ->
                done(HotReloadBatch.Status.SUCCEEDED, null)
                done(HotReloadBatch.Status.FAILED, "second answer")
            },
        ))
        assertEquals(HotReloadBatch.Status.SUCCEEDED, outcomes.single().status)
    }

    @Test
    fun aLateAnswerDoesNotLeakIntoTheNextStep() {
        val release = CountDownLatch(1)
        val batch = HotReloadBatch(timeoutMs = 60, retryDelayMs = 0)
        val outcomes = batch.run(listOf(
            step("late") { done ->
                Thread {
                    release.await(2, TimeUnit.SECONDS)
                    done(HotReloadBatch.Status.FAILED, "late")
                }.start()
            },
            step("next") { done -> done(HotReloadBatch.Status.SUCCEEDED, null) },
        ))
        release.countDown()
        assertEquals(HotReloadBatch.Status.TIMEOUT, outcomes[0].status)
        assertEquals(HotReloadBatch.Status.SUCCEEDED, outcomes[1].status)
    }

    @Test
    fun deferredFailureIsRetriedOnce() {
        var attempts = 0
        val batch = HotReloadBatch(timeoutMs = 500, retryDelayMs = 0)
        val outcomes = batch.run(listOf(
            step("busy") { done ->
                attempts++
                if (attempts == 1) done(HotReloadBatch.Status.FAILED, null)
                else done(HotReloadBatch.Status.SUCCEEDED, null)
            },
        ))
        assertEquals(2, attempts)
        assertEquals(HotReloadBatch.Status.SUCCEEDED, outcomes.single().status)
    }

    @Test
    fun failureWithAMessageIsNotRetried() {
        var attempts = 0
        val batch = HotReloadBatch(timeoutMs = 500, retryDelayMs = 0)
        val outcomes = batch.run(listOf(
            step("refused") { done -> attempts++; done(HotReloadBatch.Status.FAILED, "protocol") },
        ))
        assertEquals(1, attempts)
        assertEquals(HotReloadBatch.Status.FAILED, outcomes.single().status)
        assertEquals("protocol", outcomes.single().message)
    }

    @Test
    fun anAnswerThatNeverComesIsATimeoutNotAWaitForever() {
        val batch = HotReloadBatch(timeoutMs = 50, retryDelayMs = 0)
        val started = System.nanoTime()
        val outcomes = batch.run(listOf(step("silent") { }))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(HotReloadBatch.Status.TIMEOUT, outcomes.single().status)
        assertTrue("waited ${elapsedMs}ms", elapsedMs >= 40)
    }
}

package com.os4.musiccover

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Module code does not own a thread, receiver, host listener or frame callback directly: it
 * asks Gen, which is the only place that can take them back out when this generation retires.
 *
 * Step 2 moves one group of files at a time; gen-baseline.txt lists the ones still to move, and
 * this test holds them there. A new file that makes a raw call fails immediately, and a file
 * that has moved must not creep back.
 *
 * The app's own process is not covered - it is never hot reloaded.
 */
class GenRulesTest {

    private val forbidden = listOf(
        Regex("registerReceiver\\("),
        Regex("new HandlerThread\\("),
        Regex("new Thread\\("),
        Regex("postFrameCallback\\("),
        Regex("addOnPreDrawListener\\("),
        Regex("addOnLayoutChangeListener\\("),
        Regex("addOnAttachStateChangeListener\\("),
        Regex("addOnGlobalLayoutListener\\("),
        Regex("addOnScrollChangedListener\\("),
        Regex("registerContentObserver\\("),
        Regex("registerOnSharedPreferenceChangeListener\\("),
        Regex("registerDisplayListener\\("),
        Regex("Executors\\."),
        Regex("Thread\\s*\\{"),
    )

    @Test
    fun moduleCodeGoesThroughGen() {
        val baseline = File("src/test/resources/gen-baseline.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
        val root = File("src/main/java/com/os4/musiccover")
        val bad = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                if (rel.startsWith("app/") || rel.startsWith("ui/") ||
                    rel.startsWith("updater/") || rel == "Gen.java" ||
                    rel == "MainActivity.kt" || rel in baseline
                ) return@forEach
                file.readLines().forEachIndexed { index, line ->
                    if (forbidden.any { it.containsMatchIn(line) }) {
                        bad += "$rel:${index + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue("raw lifecycle calls outside Gen:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}

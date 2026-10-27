package com.os4.musiccover

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HotReloadPolicy.SUPPORTED] is what the retirement lets go of; [HotReload.install] is what the
 * adoption puts back. A process on one list and not the other retires and then adopts nothing -
 * the process ends up inert until a restart, which is exactly the failure this scans for.
 *
 * The scan is coarse, like HookIdTest: string literals compared against `proc`, plus the one
 * branch that names its package through a constant.
 */
class HotReloadProcessTest {

    private val literal = Regex("\"([^\"]*)\"")
    // A package, or a package's process (高德's com.autonavi.minimap:locationservice).
    private val idShape = Regex("^[a-z][a-z0-9_]*\\.[a-z0-9_.]+(:[A-Za-z][A-Za-z0-9_]*)?$")

    private fun read(path: String) = File("src/main/java/com/os4/musiccover/$path").readText()

    private fun supported(): Set<String> {
        val policy = read("HotReloadPolicy.kt")
        val block = Regex("val SUPPORTED: Set<String> = setOf\\(([^)]*)\\)")
            .find(policy)?.groupValues?.get(1)
            ?: throw AssertionError("SUPPORTED not found in HotReloadPolicy.kt")
        return literal.findAll(block).map { it.groupValues[1] }.filter { it.isNotEmpty() }.toSet()
    }

    private fun installBranches(): Set<String> {
        val source = read("HotReload.java")
        val body = Regex("private static void install\\(String proc, ClassLoader cl\\) \\{(.*?)\\n    \\}",
            RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1)
            ?: throw AssertionError("install() not found in HotReload.java")
        val out = literal.findAll(body).map { it.groupValues[1] }
            .filter { idShape.matches(it) }.toMutableSet()
        // Apple's branch matches its package constant rather than a literal.
        if (body.contains("AppleLyrics.PKG.equals(proc)")) out += "com.apple.android.music"
        return out
    }

    @Test
    fun everySupportedProcessHasAnInstaller() {
        val supported = supported()
        val installers = installBranches()
        assertEquals("SUPPORTED and HotReload.install disagree",
            supported.sorted(), installers.sorted())
    }

    @Test
    fun theInstallerThrowsForAnythingElse() {
        assertTrue("install() must refuse an unknown process",
            read("HotReload.java").contains("no installer for "))
    }
}

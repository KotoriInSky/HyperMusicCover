package com.os4.musiccover

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hook id is the handle's name across module generations: the only thing a new build and the
 * build already in a process can agree on. Renaming one silently turns that hook into an unhook
 * plus a fresh install, so a rename has to be a deliberate change, made in `hook-ids.txt` too.
 *
 * The scan is deliberately coarse: every string literal shaped like an id is collected, wherever
 * it is (hook calls, helper arguments, the prefixes of the dynamic families). A literal that
 * looks like an id and is not declared here fails the test, which is the point.
 *
 * The reverse is not enforced: an entry may stay after its hook is gone. The file is a
 * declaration of what we are allowed to use, and stale entries cost nothing while a missing one
 * is the failure this is here to catch.
 */
class HookIdTest {

    private val idShape = Regex("^(sysui|wall|aod|sub|plugin|apple|amap|assist)[.][a-z0-9_.]+$")
    private val literal = Regex("\"([^\"]*)\"")

    private fun sources(): List<File> =
        File("src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .toList()

    @Test
    fun everyIdInTheSourceIsDeclared() {
        val declared = File("src/test/resources/hook-ids.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals("duplicate ids in hook-ids.txt",
            declared.size, declared.toSet().size)
        for (entry in declared) {
            val body = entry.removeSuffix("*").trimEnd('.')
            assertTrue("bad id shape: $entry", idShape.matches(body))
        }

        val found = linkedSetOf<String>()
        for (file in sources()) {
            for (m in literal.findAll(file.readText())) {
                val s = m.groupValues[1]
                if (!idShape.matches(s)) continue
                found += if (s.endsWith(".")) s.dropLast(1) + "*" else s
            }
        }

        for (id in found) {
            val ok = declared.any { entry ->
                entry == id || (entry.endsWith("*") && id.startsWith(entry.dropLast(1)))
            }
            assertTrue("hook id $id is not declared in hook-ids.txt", ok)
        }
    }

    @Test
    fun noHookBypassesXp() {
        val offenders = sources().filter { it.readText().contains("Xp.api().hook") }
        assertTrue("installs without an id: $offenders", offenders.isEmpty())
    }
}

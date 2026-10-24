package com.os4.musiccover

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing rule a hot reload depends on: an old handle is only taken for a new hook when the
 * id and the executable both match. Everything else stays pending and is unhooked, and a
 * replacement that failed puts its old handle back where it was.
 */
class HandleStoreTest {

    private val a: Executable = String::class.java.getMethod("length")
    private val b: Executable = String::class.java.getMethod("isEmpty")

    private class Handle(
        private val id: String?,
        private val target: Executable,
        val label: String = id ?: "?",
    ) : XposedInterface.HookHandle {
        var unhooked = false
        override fun getExecutable(): Executable = target
        override fun unhook() {
            unhooked = true
        }
        override fun getId(): String? = id
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = this
    }

    @Test
    fun takePairsOnIdAndExecutable() {
        val h = Handle("sysui.clock.container_attach", a)
        val store = HandleStore(listOf(h))
        assertSame(h, store.take("sysui.clock.container_attach", a))
        assertEquals(0, store.unhookRemaining())
        assertTrue(!h.unhooked)
    }

    @Test
    fun sameIdDifferentExecutableIsNotPaired() {
        val h = Handle("sysui.clock.container_attach", a)
        val store = HandleStore(listOf(h))
        assertNull(store.take("sysui.clock.container_attach", b))
        assertEquals(1, store.unhookRemaining())
        assertTrue(h.unhooked)
    }

    @Test
    fun duplicateIdsArePairedByExecutable() {
        val one = Handle("sysui.media.bind#a", a)
        val two = Handle("sysui.media.bind#a", b)
        val store = HandleStore(listOf(one, two))
        assertSame(two, store.take("sysui.media.bind#a", b))
        assertSame(one, store.take("sysui.media.bind#a", a))
        assertEquals(0, store.unhookRemaining())
    }

    @Test
    fun aFailedReplaceGoesBackToPending() {
        val old = Handle("sysui.fp.icon", a)
        val store = HandleStore(listOf(old))
        assertSame(old, store.take("sysui.fp.icon", a))
        store.keep(old)
        assertEquals(1, store.unhookRemaining())
        assertTrue(old.unhooked)
    }

    @Test
    fun countsAndNewIdsCoverBothPaths() {
        val store = HandleStore(emptyList())
        store.replaced("sysui.clock.container_attach", Handle("sysui.clock.container_attach", a))
        store.added("sysui.shade.expansion", Handle("sysui.shade.expansion", b))
        assertEquals(1, store.replacedCount())
        assertEquals(1, store.addedCount())
        assertEquals(
            listOf("sysui.clock.container_attach", "sysui.shade.expansion"),
            store.newIds(),
        )
        assertEquals(2, store.installed().size)
    }

    @Test
    fun nullIdOrEmptyStoreIsHarmless() {
        val store = HandleStore(emptyList())
        assertNull(store.take("sysui.anything", a))
        assertEquals(0, store.unhookRemaining())
        assertTrue(store.newIds().isEmpty())
    }
}

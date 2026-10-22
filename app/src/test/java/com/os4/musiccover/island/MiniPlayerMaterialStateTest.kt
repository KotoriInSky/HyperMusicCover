package com.os4.musiccover.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerMaterialStateTest {
    @Test fun equalNativeArrayValuesHaveTheSameSignature() {
        val a = arrayOf<Any>(intArrayOf(1, 2), floatArrayOf(0.2f, 0.8f))
        val b = arrayOf<Any>(intArrayOf(1, 2), floatArrayOf(0.2f, 0.8f))
        assertEquals(MiniPlayerMaterialState.snapshot(a), MiniPlayerMaterialState.snapshot(b))
    }

    @Test fun nativeArrayReuseCannotMutateThePreviousSignature() {
        val values = floatArrayOf(0.2f, 0.8f)
        val previous = MiniPlayerMaterialState.snapshot(values)
        values[0] = 0.5f
        assertNotEquals(previous, MiniPlayerMaterialState.snapshot(values))
        assertEquals(listOf(0.2f, 0.8f), previous)
    }

    @Test fun onlyAnotherEffectReplacesTheLayer() {
        assertFalse(MiniPlayerMaterialState.replacesLayer(null, "Blur#1"))
        assertFalse(MiniPlayerMaterialState.replacesLayer("Blur#1", "Blur#2"))
        assertTrue(MiniPlayerMaterialState.replacesLayer("Blur#2", "Glass#3"))
    }
}

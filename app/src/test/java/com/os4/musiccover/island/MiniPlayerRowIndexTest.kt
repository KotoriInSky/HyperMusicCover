package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class MiniPlayerRowIndexTest {
    private class Row(val key: String?, val children: List<Row> = emptyList())

    @Test fun groupedNotificationsShareTheirSummaryAndRetainedRowsWin() {
        val child = Row("child")
        val summary = Row("summary", listOf(child))
        val transient = Row("held")
        val index = MiniPlayerRowIndex.build(listOf(summary), { it.key }, { it.children },
            mapOf("child" to transient))
        assertSame(summary, index["summary"])
        assertSame(transient, index["child"])
        assertNull(index["missing"])
        val grouped = MiniPlayerRowIndex.build(listOf(summary), { it.key }, { it.children }, emptyMap())
        assertSame(summary, grouped["child"])
    }

    @Test fun manyNotificationsAreReadOnceInsteadOfScanningForEveryKey() {
        val rows = (0 until 100).map { Row("group$it", listOf(Row("child$it"))) }
        var keyReads = 0
        var childReads = 0
        val index = MiniPlayerRowIndex.build(rows, { keyReads++; it.key },
            { childReads++; it.children }, emptyMap())
        assertEquals(200, keyReads)
        assertEquals(100, childReads)
        for (i in rows.indices) assertSame(rows[i], index["child$i"])
    }

    @Test fun duplicateAndMissingKeysPreserveTheFirstMatchingRow() {
        val first = Row("same")
        val index = MiniPlayerRowIndex.build(listOf(first, Row("same"), Row(null)),
            { it.key }, { it.children }, emptyMap())
        assertEquals(1, index.size)
        assertSame(first, index["same"])
    }
}

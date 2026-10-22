package com.os4.musiccover.pickup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ColorOS's page rules ([PickupCss]) run over pages rebuilt from the assist structure. `kfc_dom.txt`
 * is 肯德基's order page as the probe dumped it on 2026-10-13 (store, address, order numbers and
 * phone replaced), in the shape [PickupAssist.dom] builds: one line per node, two spaces a level,
 * `<tag .class.class #id>` for an element and `"text"` for a text node.
 */
class PickupCssTest {

    private val rules: Map<String, PickupCss.Rule> by lazy {
        fun load(name: String) = PickupCss.parse(File("src/main/assets/$name").readText(Charsets.UTF_8))
        PickupCss.combine(load(PickupCss.ASSET), load(PickupCss.LOCAL))
    }

    private fun rule(appId: String) = rules[appId]!!

    private fun docs(text: String): List<PickupCss.El> {
        val docs = ArrayList<PickupCss.El>()
        val stack = ArrayList<PickupCss.El>()
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val depth = (raw.length - raw.trimStart().length) / 2
            val line = raw.trim()
            if (line == "<#document>") {
                val d = PickupCss.El("#document")
                docs += d
                stack.clear()
                stack += d
                continue
            }
            while (stack.size > depth) stack.removeAt(stack.size - 1)
            val parent = stack.last()
            if (line.startsWith("\"")) {
                parent.add(PickupCss.El("", own = line.removeSurrounding("\"")))
                continue
            }
            val bits = line.removeSurrounding("<", ">").split(' ')
            val cls = bits.firstOrNull { it.startsWith(".") }?.removePrefix(".")?.split('.')?.toSet() ?: emptySet()
            val id = bits.firstOrNull { it.startsWith("#") }?.removePrefix("#").orEmpty()
            stack += parent.add(PickupCss.El(bits[0], cls, id))
        }
        return docs
    }

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("pickup/$name")!!.readText(Charsets.UTF_8)

    private fun page(vararg lines: String) = docs("<#document>\n" + lines.joinToString("\n") { "  $it" }).single()

    // ---------------------------------------------------------------- the rules

    @Test
    fun theShippedRulesParse() {
        assertTrue(rules.size >= 13)
        val kfc = rule(KFC)
        assertEquals(2, kfc.pages.size)
        val detail = kfc.pages.first { it.url.contains("kfcOrderDetails") }
        assertEquals("取餐号", detail.code[1].tag)
        assertEquals(3, detail.code[1].result)
        assertEquals("pattern=\\d+ *分钟", detail.wait.single().linkTo)
    }

    // ---------------------------------------------------------------- 肯德基, measured

    @Test
    fun kfcsStepBarIsReadByItsActiveStep() {
        val doc = PickupCss.front(docs(fixture("kfc_dom.txt")))!!
        val r = PickupCss.read(rule(KFC), doc)!!
        // 准备中 / 请取餐 / 已完成 are all on the page; the `_a` step is 已完成.
        assertEquals("已完成", r.status)
        assertEquals("A0414", r.code)
        assertEquals("取餐号", r.label)
        assertTrue(r.page.contains("详情"))
    }

    @Test
    fun kfcsStepBarOnAnotherStep() {
        val text = fixture("kfc_dom.txt")
            .replace(".od-pb-c_item_c_t.wrapper--od-pb-c_item_c_t.od-pb-c_item_c_t_a.wrapper--od-pb-c_item_c_t_a>",
                ".od-pb-c_item_c_t.wrapper--od-pb-c_item_c_t>")
        val lines = text.lines().toMutableList()
        // Put the active class on 请取餐's step instead.
        val at = lines.indexOfFirst { it.trim() == "\"请取餐\"" }
        lines[at - 1] = lines[at - 1].replace(".od-pb-c_item_c_t.wrapper--od-pb-c_item_c_t>",
            ".od-pb-c_item_c_t.wrapper--od-pb-c_item_c_t.od-pb-c_item_c_t_a.wrapper--od-pb-c_item_c_t_a>")
        val r = PickupCss.read(rule(KFC), PickupCss.front(docs(lines.joinToString("\n")))!!)!!
        assertEquals("待取餐", r.status)
    }

    @Test
    fun kfcsListPageReadsTheFirstOrder() {
        val list = docs(fixture("kfc_dom.txt")).first { it.select("wx-view.kfcListItemStatu").isNotEmpty() }
        val r = PickupCss.read(rule(KFC), list)!!
        assertEquals("已完成", r.status)
        assertNull(r.code)
    }

    // ---------------------------------------------------------------- words that mean different things

    @Test
    fun naixuesMadeAfterIsStillBeingMade() {
        val doc = page(
            "<wx-view .comNewCurrentOrderStatus--code>", "  \"A12\"",
            "<wx-view .comNewCurrentOrderStatus--info-remind-text>", "  \"制作完成后请凭取餐号取餐\"",
        )
        val r = PickupCss.read(rule(NAIXUE), doc)!!
        assertEquals("A12", r.code)
        assertEquals("制作中", r.status)
    }

    @Test
    fun chabaidaosThanksIsReady() {
        val doc = page(
            "<wx-rich-text .order_txt.orderStatusV2--order_txt.data-v-97b7c256.orderStatusV2--data-v-97b7c256>",
            "  \"您的茶饮已做好，感谢对茶百道的支持\"",
        )
        assertEquals("待取餐", PickupCss.read(rule(CHABAIDAO), doc)!!.status)
    }

    @Test
    fun mcdonaldsPreparedIsReadyNotOver() {
        // ColorOS reads 「订单已准备完毕」 as over; this module keeps the card (2026-10-13).
        val doc = page("<wx-text .header-bg-subtitle>", "  \"订单已准备完毕，请凭取餐码取餐\"")
        assertEquals("待取餐", PickupCss.read(rule(MCD), doc)!!.status)
    }

    @Test
    fun aPageTheRulesDoNotKnowReadsNothing() {
        assertNull(PickupCss.read(rule(KFC), page("<wx-view .something-else>", "  \"请取餐\"")))
    }

    // ---------------------------------------------------------------- 蜜雪冰城, this module's own rule

    @Test
    fun mixuesDoneIsReady() {
        // 蜜雪冰城 has no state for collected: 「订单已完成」 is shown once the drink is made.
        val r = PickupCss.read(rule(MIXUE), PickupCss.front(docs(fixture("mixue_dom.txt")))!!)!!
        assertEquals("6706", r.code)
        assertEquals("待取餐", r.status)
        assertEquals("2026-08-22T19:33", r.orderTime)
    }

    // The take page's status line, as pages/order_detail/take/index.wxml writes it (wxapkg 304,
    // 2026-10-13): the wait sits in its own <text>, with a 「+」 when the shop is busy.
    private fun mixueTake(vararg line: String) = page(
        "<wx-view .order-card-body>",
        "  <wx-text .f14>", "    \"取餐码\"",
        "  <wx-view .takeNum.mt8>", "    \"8746\"",
        *line.map { "  $it" }.toTypedArray(),
    )

    private fun mixueMaking(wait: String) = mixueTake(
        "<wx-view .f14.mt8.flex-center>",
        "  <wx-text>", "    \"制作中，预计等待\"",
        "    <wx-text .need-time.ml4.mr4>", "      \"$wait\"",
        "    \"分钟\"",
    )

    @Test
    fun mixueBeingMade() {
        val r = PickupCss.read(rule(MIXUE), mixueMaking("5"))!!
        assertEquals("8746", r.code)
        assertEquals("制作中", r.status)
        assertEquals("约5分钟", r.wait)
        assertNull(r.orderTime)
        val busy = PickupCss.read(rule(MIXUE), mixueMaking("15+"))!!
        assertEquals("制作中", busy.status)
        assertNotNull(busy.wait)
    }

    @Test
    fun mixuesOtherLines() {
        fun state(text: String) =
            PickupCss.read(rule(MIXUE), mixueTake("<wx-view .f14.mt8>", "  <wx-text>", "    \"$text\""))!!.status
        assertEquals("待取餐", state("制作完成，请取餐~"))
        assertEquals("待取餐", state("请前往取餐区查看"))
        assertEquals("制作中", state("您的餐品制作中"))
        assertEquals("制作中", state("门店已接单，请于下单当日及时到店取餐"))
        assertEquals("已下单", state("凭取餐码自提，预计取餐时间12:30"))
        assertEquals("已取消", state("订单金额已原路返回，请及时查看"))
    }

    @Test
    fun theOrderTimeFillsAGap() {
        val m = PickupCss.merge(own(null), PickupCss.Read("A12", null, "待取餐", null, "p", "2026-08-22T19:33"))!!
        assertEquals("2026-08-22T19:33", m.orderTime)
        val kept = PickupCss.merge(own(null).copy(orderTime = "2026-10-13T08:00"),
            PickupCss.Read("A12", null, "待取餐", null, "p", "2026-08-22T19:33"))!!
        assertEquals("2026-10-13T08:00", kept.orderTime)
    }

    @Test
    fun colorOsRulesWinOverOurs() {
        val oem = PickupCss.Rule("wx1", "a", "", emptyList())
        val ours = PickupCss.Rule("wx1", "b", "", emptyList())
        val extra = PickupCss.Rule("wx2", "c", "", emptyList())
        val all = PickupCss.combine(mapOf("wx1" to oem), mapOf("wx1" to ours, "wx2" to extra))
        assertEquals("a", all["wx1"]!!.name)
        assertEquals("c", all["wx2"]!!.name)
    }

    // ---------------------------------------------------------------- the verdict

    private fun own(status: String?) = PickupParse.Result("A12", "取餐号", status, "示例店", waitTime = "约5分钟")

    @Test
    fun theRulesStateWins() {
        val m = PickupCss.merge(own("制作中"), PickupCss.Read("A12", null, "待取餐", null, "p"))!!
        assertEquals("待取餐", m.status)
        assertTrue(m.ruled)
        assertEquals("示例店", m.store)
        assertEquals("约5分钟", m.waitTime)
    }

    @Test
    fun aReadyWordIsNotTakenOnARuledBrand() {
        // The rules found the code and no state: the page's 「已完成」 elsewhere is not the order's.
        val m = PickupCss.merge(own("已完成"), PickupCss.Read("A12", null, null, null, "p"))!!
        assertNull(m.status)
        assertFalse(m.ruled)
        assertEquals("制作中", PickupCss.merge(own("制作中"), PickupCss.Read("A12", null, null, null, "p"))!!.status)
    }

    @Test
    fun theRulesCodeWins() {
        val m = PickupCss.merge(own(null), PickupCss.Read("B7", "取单号", "制作中", "约3分钟", "p"))!!
        assertEquals("B7", m.code)
        assertEquals("取单号", m.label)
        assertEquals("约3分钟", m.waitTime)
        assertTrue(m.strong)
        val alone = PickupCss.merge(null, PickupCss.Read("B7", null, "制作中", null, "p"))!!
        assertEquals("取餐码", alone.label)
    }

    @Test
    fun nothingReadLeavesTheWordsAlone() {
        val o = own("已完成")
        assertEquals(o, PickupCss.merge(o, null))
        assertNull(PickupCss.merge(null, PickupCss.Read(null, null, "制作中", null, "p")))
    }

    @Test
    fun selectorsMatchTagAndEveryClass() {
        val doc = page("<wx-view .a.b>", "  \"x\"", "<wx-text .a>", "  \"y\"")
        assertEquals(listOf("x"), doc.select("wx-view.a.b").map { it.text() })
        assertEquals(emptyList<String>(), doc.select("wx-view.a.c").map { it.text() })
        assertEquals(listOf("x", "y"), doc.select("wx-view.b,wx-text.a").map { it.text() })
        assertNotNull(doc.select("wx-text").singleOrNull())
    }

    private companion object {
        const val KFC = "wx08ee7f7d36a2eff8"
        const val NAIXUE = "wxab7430e6e8b9a4ab"
        const val CHABAIDAO = "wx2804355dbf8d15c3"
        const val MCD = "wx25f982a55e60a540"
        const val MIXUE = "wx7696c66d2245d107"
    }
}

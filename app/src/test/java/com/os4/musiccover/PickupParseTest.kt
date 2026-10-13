package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pages are real ones read off a phone on 2026-10-08 (shops, addresses and order
 * numbers replaced), one line a text node: the WebView it is in, a tab, the text.
 */
class PickupParseTest {

    private fun page(name: String): List<PickupParse.Node> =
        javaClass.classLoader!!.getResource("pickup/$name.txt")!!.readText().lines()
            .filter { it.isNotEmpty() }
            .map { val tab = it.indexOf('\t'); PickupParse.Node(it.substring(tab + 1), it.substring(0, tab).toInt()) }

    private fun n(vararg texts: String) = texts.map { PickupParse.Node(it, 0) }

    @Test
    fun mixueCodeIsUnderItsLabel() {
        val r = PickupParse.parse(page("mixue"))!!
        assertEquals("6706", r.code)
        assertEquals("取餐码", r.label)
        // 「订单已完成，祝您用餐愉快」 is what 蜜雪冰城 says once the drink is made, and ColorOS does
        // not read it as collected; nothing else on the page names a state.
        assertNull(r.status)
        assertEquals("示例南门店", r.store)
    }

    @Test
    fun chageeCodeIsAboveItsLabel() {
        val r = PickupParse.parse(page("chagee"))!!
        assertEquals("T0295", r.code)
        assertEquals("取单号", r.label)
        assertEquals("已完成", r.status)
        assertEquals("示例市示例区时光里店", r.store)
    }

    @Test
    fun mcdCallsItsCodeTheOrderNumber() {
        // 订单号 / 35516, with the real order number under 订单编码 further down.
        val r = PickupParse.parse(page("mcd"))!!
        assertEquals("35516", r.code)
        assertEquals("订单号", r.label)
        assertEquals("已准备完毕", r.status)
        assertEquals("麦当劳示例广场餐厅", r.store)
    }

    @Test
    fun aStrongLabelBeatsAnEarlierWeakOne() {
        val r = PickupParse.parse(n("订单号", "123456", "取餐号", "A12"))!!
        assertEquals("A12", r.code)
    }

    @Test
    fun aWeakLabelWithALongNumberIsNoCode() {
        assertNull(PickupParse.parse(n("订单号", "2091126703119405058")))
    }

    @Test
    fun theHashIsNotPartOfTheCode() {
        assertEquals("123", PickupParse.parse(n("取餐码", "#123"))!!.code)
    }

    @Test
    fun aPageBehindIsNotRead() {
        // The order list (page 2) lies under the order (page 3); without page 3 there is no code.
        val listOnly = page("mixue").filter { it.page < 3 }
        assertNull(PickupParse.parse(listOnly))
    }

    @Test
    fun theShopsOwnNumberIsNotTheCode() {
        assertNull(PickupParse.parse(n("取餐号码", "门店编码", "123456")))
    }

    @Test
    fun aPriceIsNotTheCode() {
        assertNull(PickupParse.parse(n("取餐号", "¥", "18")))
    }

    @Test
    fun anOrderNumberIsNotTheCode() {
        assertNull(PickupParse.parse(n("取餐号", "2091126703119405058")))
    }

    @Test
    fun codeInTheLabelsOwnNode() {
        val r = PickupParse.parse(n("制作中", "取餐号：A123"))!!
        assertEquals("A123", r.code)
        assertEquals("取餐号", r.label)
        assertEquals("制作中", r.status)
    }

    @Test
    fun nothingWithoutALabel() {
        assertNull(PickupParse.parse(n("订单详情", "6706", "已完成")))
    }

    // ---------------------------------------------------------------- code shape

    @Test
    fun twoBareDigitsAreNoCode() {
        assertNull(PickupParse.parse(n("取餐号", "12")))
        assertEquals("A12", PickupParse.parse(n("取餐码", "A12"))!!.code)
    }

    @Test
    fun aServiceLineIsNoCode() {
        assertNull(PickupParse.parse(n("取餐号", "400")))
    }

    @Test
    fun aCountIsNoCode() {
        assertNull(PickupParse.parse(n("取餐码", "当前", "118", "杯", "制作中")))
        assertNull(PickupParse.parse(n("取餐码", "前面还有", "12", "单")))
    }

    // ---------------------------------------------------------------- the recognizer's code

    private fun oem(code: String, orderPage: Boolean = true) =
        PickupParse.Oem(code, orderPage, "uncompleted", "", "")

    /** 霸王茶姬's menu page, 2026-10-13: the shop's queue, which the recognizer read as code 118. */
    private val menu = n("广东广州番禺示例店", "距您2.1km", "当前", "118", "杯", "制作中", "好友拼单")

    @Test
    fun theRecognizerIsNotHeardOffAnOrderPage() {
        assertNull(PickupParse.choose(menu, oem("118", orderPage = false)))
    }

    @Test
    fun theRecognizersCountIsNoCodeEvenOnAnOrderPage() {
        assertNull(PickupParse.choose(menu, oem("118")))
        assertNull(PickupParse.choose(n("当前118杯制作中", "订单号"), oem("118")))
    }

    @Test
    fun theRecognizerAnswersForAnOrderPageThisFileCannotRead() {
        val r = PickupParse.choose(n("订单信息", "A307", "制作中", "示例广场店"), oem("A307"))!!
        assertEquals("A307", r.code)
        assertEquals("制作中", r.status)
        assertEquals("示例广场店", r.store)
    }

    @Test
    fun aCodeBesideAStrongLabelBeatsTheRecognizers() {
        val r = PickupParse.choose(n("订单号", "818", "取餐号", "A12"), oem("818"))!!
        assertEquals("A12", r.code)
    }

    @Test
    fun theStateIsReadAroundTheCodeTaken() {
        // This file reads 556 under 订单号 (weak); the recognizer reads 307, and its state is 已出餐.
        val nodes = n("订单号", "556", "已取消", "a", "b", "c", "d", "e", "307", "已出餐", "订单信息")
        val r = PickupParse.choose(nodes, oem("307"))!!
        assertEquals("307", r.code)
        assertEquals("已出餐", r.status)
    }

    @Test
    fun onlyTheFrontPageIsTheFrontPage() {
        val nodes = listOf(PickupParse.Node("取餐码", 1), PickupParse.Node("111", 1),
            PickupParse.Node("取餐码", 2), PickupParse.Node("222", 2))
        assertEquals(listOf("取餐码", "222"), PickupParse.frontPage(nodes).map { it.text })
    }

    @Test
    fun aShopInBracketsAfterItsBrand() {
        // 蜜雪冰城's older order pages, 2026-10-13 (8628).
        val r = PickupParse.parse(n("订单详情", "取餐码", "8628", "订单已完成，祝您用餐愉快",
            "蜜雪冰城（示例工业园店）", "直线距离1016.8km", "珍珠奶茶", "¥ ", "10", "大杯/芋圆/少冰/不额外加糖", "X1"))!!
        assertEquals("示例工业园店", r.store)
        assertEquals("珍珠奶茶", r.product)
    }

    // ---------------------------------------------------------------- waiting

    @Test
    fun theWaitIsRead() {
        assertEquals("约5分钟", PickupParse.parse(n("取餐号", "A123", "制作中", "预计5分钟后可取"))!!.waitTime)
        assertEquals("前面3单", PickupParse.parse(n("取餐号", "A123", "制作中", "前面还有", "3", "单"))!!.waitTime)
        assertEquals("预计12:40", PickupParse.parse(n("取餐号", "A123", "预计 12:40 取餐"))!!.waitTime)
        assertNull(PickupParse.parse(n("取餐号", "A123", "已完成"))!!.waitTime)
        // 蜜雪冰城's coupon line is not a wait.
        assertNull(PickupParse.parse(n("取餐码", "8746", "订单已完成，祝您用餐愉快", "支付完成1分钟后发放"))!!.waitTime)
    }

    // ---------------------------------------------------------------- the item

    @Test
    fun theItemIsReadOffItsRow() {
        assertEquals("香柠蓝莓霸气桶", PickupParse.parse(page("mixue"))!!.product)
        assertEquals("轻因·单丛柠檬奶", PickupParse.parse(page("chagee"))!!.product)
        // A meal's own name, not the parts it lists as 「1 x <part>」.
        assertEquals("人气经典随心配", PickupParse.parse(page("mcd"))!!.product)
    }

    @Test
    fun anItemWithItsOptionsInBrackets() {
        // 益禾堂's order page, 2026-10-13.
        val r = PickupParse.parse(n("请取餐", "740", "取单号", "芋泥啵啵奶绿", "[", "]", ",", "默认糖", "x1", "￥", "10"))!!
        assertEquals("芋泥啵啵奶绿", r.product)
    }

    @Test
    fun aDrinkNamedForIceIsNotAnOption() {
        assertEquals("草莓牛乳冰", PickupParse.product(listOf("草莓牛乳冰", "少冰", "x1")))
    }

    @Test
    fun theItemBeatsTheRecognizersList() {
        val r = PickupParse.choose(n("订单信息", "A307", "制作中", "芋泥啵啵奶绿", "默认糖", "x1"),
            PickupParse.Oem("A307", true, "uncompleted", "芋泥啵啵牛奶", ""))!!
        assertEquals("芋泥啵啵奶绿", r.product)
    }

    @Test
    fun noRowNoItem() {
        assertNull(PickupParse.parse(n("取餐号", "A123", "制作中"))!!.product)
    }

    // ---------------------------------------------------------------- order state

    @Test
    fun aProgressBarIsNotTheState() {
        assertNull(PickupParse.parse(n("已下单", "制作中", "待取餐", "取餐号", "A123", "请耐心等待"))!!.status)
    }

    @Test
    fun aLoneStateBeatsAProgressBar() {
        val r = PickupParse.parse(n("已下单", "制作中", "待取餐", "x", "y", "z", "取餐号", "A123", "待取餐"))!!
        assertEquals("待取餐", r.status)
    }

    @Test
    fun aPageAskingForAReviewIsFinished() {
        assertEquals("已完成", PickupParse.parse(n("取餐号", "A123", "评价一下"))!!.status)
    }

    @Test
    fun everyMakingWordCanBeRead() {
        for (w in PickupParse.MAKING) {
            val s = PickupParse.parse(n("取餐号", "A123", w))!!.status
            assertEquals(w, s)
        }
    }

    @Test
    fun theMostSpecificStateWins() {
        assertEquals("退单已完成", PickupParse.parse(n("取餐号", "A123", "退单已完成"))!!.status)
        assertEquals("制作中", PickupParse.parse(n("取餐号", "A123", "精心制作中"))!!.status)
    }
}

package com.os4.musiccover

/**
 * The pickup code on a mini program's order page, out of the page's text (PickupCodeIsland reads
 * the text).
 *
 * The page is a mini program's XWeb WebView, and its DOM comes back as the assist structure's
 * nodes. What is handed here is those nodes' texts in tree order, each with the WebView it sits
 * in: the structure holds every page of the mini program's stack (home, order list, the order),
 * one WebView each, and the last one is the page in front.
 *
 * No two brands write the code the same way (2026-10-08, real pages):
 *   蜜雪冰城   取餐码 / 6706 / 订单已完成，祝您用餐愉快 ... 取餐号码 / 6706 ... 门店编码 / 927639
 *   霸王茶姬   已完成 / T0295 / 取单号
 *   麦当劳     35516 / 订单号 / 订单已准备完毕 ... 订单编码 / 1030383590000572115636496149
 * so the label is looked for first, and the code is the nearest short code on either side of it.
 * A label is strong (取餐码, 取单号, 餐号 - only ever the code) or weak (订单号, 流水号, 叫号 -
 * the code at 麦当劳, a long order number elsewhere); a weak one is tried only when no strong one
 * gives a code anywhere on the page, and a long number is never a code.
 *
 * ColorOS's recognizer ([PickupOem]) is asked as well, and [choose] decides between the two.
 */
internal object PickupParse {

    /** One text node: [page] is the index of the WebView it is in, -1 for none. */
    data class Node(val text: String, val page: Int)

    /**
     * [product] and [oemStatus] come from ColorOS's recognizer, not from anything in this file: they
     * are filled in by [choose] when it is the recognizer's code that was taken, and are null when
     * this parser answered alone. [status] stays the page's own wording either way, because the
     * island's ready/over decisions read that; the recognizer's verdict is only ever shown.
     */
    data class Result(
        val code: String,
        val label: String,
        val status: String?,
        val store: String?,
        val product: String? = null,
        val oemStatus: String? = null,
        /** The drink's own temperature, from the recognizer: it picks between a cold and hot cup. */
        val temperature: String? = null,
        /** Whether the code sat beside a label that is only ever the code (取餐码, not 订单号). */
        val strong: Boolean = false,
    )

    /** What ColorOS's recognizer answered for the front page, as [choose] weighs it. */
    data class Oem(
        val code: String,
        /** Its own order-page test: a code label and an order-info label both on the page. */
        val orderPage: Boolean,
        val status: String,
        val product: String,
        val temperature: String,
    )

    /** 取餐码, 取餐号, 取单号, 取茶号, 取餐号码, 取件码 ... - 取, up to two more, then 码 or 号 (码). */
    private val LABEL = Regex("^(?:您的)?取.{0,2}[码号]码?[:：]?$")
    /** The same, with the code in the same node: 「取餐号：A123」. */
    private val LABEL_AND_CODE = Regex("(取.{0,2}[码号]码?)\\s*[:：]?\\s*(#?(?:[A-Z]{1,2}-?\\d{2,6}|\\d{3,6}))$")
    private val STRONG = setOf("餐号", "取餐编号", "取餐号码", "取单口令")
    private val WEAK = setOf("订单号", "流水号", "排队号", "叫号", "号码")
    /**
     * A code: one or two capitals and two to six digits, or three to six digits; some pages put a #
     * before it. Two bare digits are not one - a quantity, a price's yuan or a minute is that shape,
     * and ColorOS's own rule wants three characters too.
     */
    private val CODE = Regex("^#?(?:[A-Z]{1,2}-?\\d{2,6}|\\d{3,6})$")
    /** ColorOS's code blacklist: the 400/800 service lines, and 「APP」. */
    private val NOT_CODES = setOf("400", "800", "APP")
    /**
     * A count rather than a code: 「当前 / 118 / 杯 / 制作中」 is how busy the shop is, on its menu
     * page (霸王茶姬, 2026-10-13) - and ColorOS's recognizer read it as the code, because 制作中 is
     * one of its code labels.
     */
    private val COUNT_AFTER = listOf("杯", "单", "份", "人", "位", "件", "个", "分钟", "元", "km", "公里")
    private val COUNT_BEFORE = listOf("当前", "前面", "还有", "排队", "第", "约", "共", "距您")
    /** How far from its label a code may be, in text nodes. */
    private const val REACH = 4
    /** How close two order states may sit before they are a progress bar rather than a state. */
    private const val STEP_BAR = 2

    // ------------------------------------------------------------------ order states
    //
    // One vocabulary, in one place: [status] answers with one of [STATUSES], and the island decides
    // what to do with it through the four groups. The groups take ColorOS's own lists in
    // (OrderInfoProcessor's KEYWORD_FINISHED/PROCESS_TEXT_LIST, 取餐码-OPPO逆向/04 §2).

    /** The order is over: nothing left to wait on. */
    val DONE = setOf(
        "退单已完成", "已退单", "订单已退款", "退款成功", "已退款", "已取消", "取餐成功", "您已扫码取餐", "已完成",
    )

    /**
     * The food can be collected. 已完成 is in: some pages call the ready state that (蜜雪冰城's read
     * 「订单已完成」).
     */
    val READY = setOf(
        "制作完成", "已准备完毕", "已出餐", "待取餐", "请取餐", "可取餐", "待取茶", "可取茶", "可取单", "未取餐",
        "已完成",
    )

    /** The order is being made. */
    val MAKING = setOf("制作中", "制茶中", "制备中", "备餐中", "配餐中", "出餐中", "准备中", "待备餐", "已接单")

    /** The order is in, and nothing has been started on it. */
    val ORDERED = setOf("待支付", "已下单", "已付款", "配送中")

    /**
     * Every state, most specific first, since a node is matched by containing one: 退单已完成 is
     * 已完成, 等待取餐 is 待取餐, 精心制作中 is 制作中.
     */
    val STATUSES: List<String> = (DONE.toList() + (READY - "已完成") + MAKING + ORDERED).distinct()

    /**
     * Words only a finished order's page carries, with no state of its own beside them: a page that
     * asks for a review has been collected. ColorOS reads them as completed too.
     */
    private val FINISHED_HINTS = listOf("评价一下", "立即评价", "用餐评价", "去评价", "欢迎再次光临", "谢谢您的光临")

    fun isReady(s: String?) = s != null && s in READY
    fun isOver(s: String?) = s != null && (s in READY || s in DONE)
    fun isMaking(s: String?) = s != null && s in MAKING

    // ------------------------------------------------------------------ reading

    /** The front page's nodes: the last WebView's, or every node when there is no WebView at all. */
    fun frontPage(all: List<Node>): List<Node> {
        val nodes = all.filter { it.text.isNotBlank() }
        val front = nodes.maxOfOrNull { it.page } ?: return emptyList()
        return if (front >= 0) nodes.filter { it.page == front } else nodes
    }

    /** This file's own reading: the front page first, then the whole stack. */
    fun parse(all: List<Node>): Result? {
        val nodes = all.filter { it.text.isNotBlank() }
        val front = nodes.maxOfOrNull { it.page } ?: return null
        if (front >= 0) find(nodes.filter { it.page == front })?.let { return it }
        return find(nodes)
    }

    /**
     * The page's code, out of this file's reading and ColorOS's recognizer together.
     *
     * The recognizer is OPPO's first rung, and its code labels are loose: they include the order
     * states (制作中, 已下单, 准备中 ...), so any three-to-six digit line near one of those is a code
     * to it - the 「当前 118 杯制作中」 on 霸王茶姬's menu page went up as a pickup code
     * (2026-10-13). So its code is taken only
     *  - when the page is an order page by its own test ([Oem.orderPage]), or this file found a code
     *    beside a label too - ColorOS gates the same way, with its per-page paths on top; and
     *  - when it does not read as a count where it sits ([plausible]); and
     *  - when this file did not find a *different* code beside a label that is only ever the code.
     * The state and the shop are then looked for around the code that was taken, not around
     * whichever code this file had found - two different codes would otherwise have one's code
     * shown with the other's state.
     */
    fun choose(all: List<Node>, oem: Oem?): Result? {
        val own = parse(all)
        val code = oem?.code?.trim()?.removePrefix("#").orEmpty()
        if (oem == null || code.isEmpty()) return own
        if (!oem.orderPage && own == null) return null
        if (own != null && own.code != code && own.strong) return own
        val t = frontPage(all).map { it.text.trim() }
        val at = t.indexOfFirst { it == code || it == "#$code" }.takeIf { it >= 0 }
            ?: t.indexOfFirst { it.contains(code) }
        if (code in NOT_CODES || (at >= 0 && !plausible(t, at, code))) return own
        val same = own != null && own.code == code
        return Result(
            code = code,
            label = own?.label?.takeIf { it.isNotEmpty() } ?: "取餐码",
            status = if (same || at < 0) own?.status else status(t, at),
            store = if (same || at < 0) own?.store else store(t, at),
            product = oem.product.ifEmpty { null },
            oemStatus = oem.status.ifEmpty { null },
            temperature = oem.temperature.ifEmpty { null },
            strong = same && own?.strong == true,
        )
    }

    private fun find(nodes: List<Node>): Result? {
        val t = nodes.map { it.text.trim() }
        for (i in t.indices) {
            LABEL_AND_CODE.find(t[i])?.let { m ->
                val code = m.groupValues[2].removePrefix("#")
                if (plausible(t, i, code)) {
                    return Result(code, m.groupValues[1], status(t, i), store(t, i), strong = true)
                }
            }
        }
        return near(t, strong = true) { s -> s.length <= 6 && (LABEL.matches(s) || s.trimEnd(':', '：') in STRONG) }
            ?: near(t, strong = false) { s -> s.trimEnd(':', '：') in WEAK }
    }

    /** The first label [isLabel] knows with a code beside it. */
    private fun near(t: List<String>, strong: Boolean, isLabel: (String) -> Boolean): Result? {
        for (i in t.indices) {
            if (!isLabel(t[i])) continue
            for (d in 1..REACH) {
                for (j in intArrayOf(i - d, i + d)) {
                    if (j in t.indices && isCode(t, j, i)) {
                        val code = t[j].removePrefix("#")
                        return Result(code, t[i].trimEnd(':', '：'), status(t, j), store(t, j), strong = strong)
                    }
                }
            }
        }
        return null
    }

    /** Whether [t] at [j] is a code, for the label at [label]. */
    private fun isCode(t: List<String>, j: Int, label: Int): Boolean {
        val s = t[j]
        if (!CODE.matches(s)) return false
        val before = t.getOrNull(j - 1).orEmpty()
        // Under another label of its own, 订单号 / 123456 next to 取餐号: that one's.
        if (j - 1 != label && before.trimEnd(':', '：') in WEAK) return false
        // A number the page has its own name for, 门店编码 / 927639: not ours however close.
        if (before.endsWith("编码") || before.endsWith("编号") || before.endsWith("电话")) return false
        return plausible(t, j, s.removePrefix("#"))
    }

    /**
     * Whether [code], found in [t] at [at], reads as a code where it sits: not a price, not a
     * blacklisted number, not a count. The text on either side is the rest of its own node, or the
     * node next to it when the code is a node by itself.
     */
    private fun plausible(t: List<String>, at: Int, code: String): Boolean {
        if (code in NOT_CODES) return false
        val s = t[at]
        val k = s.indexOf(code)
        val inBefore = if (k > 0) s.substring(0, k).trimEnd().removeSuffix("#") else ""
        val inAfter = if (k >= 0) s.substring(k + code.length).trimStart() else ""
        val before = inBefore.ifEmpty { t.getOrNull(at - 1).orEmpty() }
        val after = inAfter.ifEmpty { t.getOrNull(at + 1).orEmpty() }
        // A price split over two nodes: 「¥ 」 then 「18」.
        if (before.endsWith("¥") || before.endsWith("￥") || before.endsWith("$")) return false
        if (COUNT_AFTER.any { after.startsWith(it) }) return false
        if (COUNT_BEFORE.any { before.endsWith(it) }) return false
        return true
    }

    /**
     * The order's state, the one nearest the code - ColorOS's reading of it, which is not simply the
     * nearest word: a progress bar 「已下单 / 制作中 / 待取餐 / 已完成」 names every state at once,
     * and only a state with no other state within [STEP_BAR] nodes of it is the order's own. When
     * none is, a page that asks for a review is a finished one.
     */
    private fun status(t: List<String>, at: Int): String? {
        val found = t.indices.mapNotNull { k ->
            val s = t[k]
            if (s.length > 14) null else STATUSES.firstOrNull { s.contains(it) }?.let { k to it }
        }
        val lone = found.filter { (k, _) -> found.none { (o, _) -> o != k && kotlin.math.abs(o - k) <= STEP_BAR } }
        lone.minByOrNull { (k, _) -> kotlin.math.abs(k - at) }?.let { return it.second }
        if (t.any { s -> FINISHED_HINTS.any { s.contains(it) } }) return "已完成"
        return null
    }

    /** The shop: a short text ending in 店 or 餐厅, 「南门店-No.927639」 read as 「南门店」. */
    private fun store(t: List<String>, at: Int): String? {
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (k in t.indices) {
            val s = t[k].substringBefore("-No.").trimEnd('>', ' ')
            if (s.length !in 3..24 || !(s.endsWith("店") || s.endsWith("餐厅"))) continue
            if (s.contains("联系") || s.contains("致电") || s.contains("附近") || s.contains("订单") ||
                s.startsWith("到店") || s.startsWith("家")) continue
            val d = kotlin.math.abs(k - at)
            if (d < bestD) { best = s; bestD = d }
        }
        return best
    }
}

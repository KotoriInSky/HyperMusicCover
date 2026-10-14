package com.os4.musiccover.transit

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.os4.musiccover.BuildConfig
import com.os4.musiccover.R
import com.os4.musiccover.Xp

/**
 * The trip's card on the back screen (`miui.rear.rv` / `miui.rear.rvAOD` on [AmapTransitIsland]'s
 * notification).
 *
 * Without one the back screen draws the trip with its own template for the package, and its list
 * (`sub_screen_app_registry`) has 高德 as `carHailing`: the ride-hailing template, whose picture
 * can only be a car (`extraInfo.carType` picks a saloon, a taxi, a GL8 or a minibus) - so a subway
 * trip showed a white saloon (2026-10-14). A notification that carries `miui.rear.rv` is drawn from
 * it instead, whatever its business (`is_remote_view` in the app's widget bundle, then a type-3
 * widget), so nothing on the back screen's side has to change.
 *
 * The card is the template's own, see [R.layout.mc_transit_rear], with 智能助理's high-speed train
 * in the car's place - the user's pick: the closest thing on the phone to a subway car, drawn in the
 * same white studio style as the template's cars.
 */
internal object TransitRear {

    private const val TAG = "MCAmap: transit rear: "

    /** 智能助理, whose travel card's train this borrows. 高德 holds QUERY_ALL_PACKAGES, so it sees it. */
    private const val ASSISTANT = "com.miui.personalassistant"
    private const val TRAIN = "pa_ic_travel_train"

    /** The template's line 3 for 高德 (`set_line3_text_color`), on either ground. */
    private const val BLUE = 0xff3482ff.toInt()
    private const val INK = 0xff000000.toInt()
    private const val INK_SOFT = 0x99000000.toInt()
    private const val LIGHT = 0xffffffff.toInt()
    private const val LIGHT_SOFT = 0x99ffffff.toInt()

    /**
     * The template's floors, a column down the middle of each (bg.webp, bg_aod.webp): a grey studio
     * wall darkest a third of the way down, where the car stands, lifting to the floor's near white;
     * and the same in near black for the always-on screen.
     */
    private val DAY = intArrayOf(0xffbbbbbb.toInt(), 0xffbfbfbf.toInt(), 0xffb6b6b6.toInt(),
        0xffd4d4d4.toInt(), 0xffe0e0e0.toInt(), 0xffebebeb.toInt())
    private val NIGHT = intArrayOf(0xff1b1b1b.toInt(), 0xff181818.toInt(), 0xff151515.toInt(),
        0xff1f1f1f.toInt(), 0xff212121.toInt(), 0xff232323.toInt())
    private val STOPS = floatArrayOf(0f, 0.12f, 0.33f, 0.5f, 0.75f, 1f)

    /** The train's id in 智能助理, looked up once: 0 for not found, null for not asked yet. */
    @Volatile private var trainId: Int? = null

    /** Both cards, the day one and the always-on one, for [AmapTransitIsland] to attach. */
    fun cards(ctx: Context, c: AmapTransitCard.Card, title: String, lineRow: String,
              logo: Bitmap, bus: () -> Bitmap, open: PendingIntent): Pair<RemoteViews, RemoteViews> {
        // A bus leg keeps its bus, a walk has no picture (a drawn walker beside the train read
        // badly, 10-14), and everything else - a subway leg, a wait for one, the end - is the train.
        val walk = c.kind == AmapTransitCard.Card.KIND_WALK || c.kind == AmapTransitCard.Card.KIND_WALK_NAVI
        val picture = when {
            walk -> null
            c.line.isNotEmpty() && !c.subway() -> Icon.createWithBitmap(bus())
            else -> train(ctx)
        }
        val line1 = lineRow.ifEmpty { "高德地图" }
        // The lock screen's own pair, which is written to be read together: the template's own
        // choice (the capsule's left word over the title) said 「步行至」 over 「步行至大学城南」.
        val line2 = c.lockTitle.ifEmpty { title }
        val line3 = c.lockSubtitle.takeIf { it.isNotEmpty() && it != line2 } ?: ""
        return card(picture, logo, line1, line2, line3, open, false) to
            card(picture, logo, line1, line2, line3, open, true)
    }

    /**
     * The big line's size: the template's 32sp while it fits its 193dp, and smaller for a longer
     * one rather than cut off - 「步行至大学城南」 is seven characters, and a CJK character is about
     * as wide as the type is tall. Not under 22sp, past which ellipsizing is the better loss.
     */
    private fun bigSize(text: String): Float {
        val ems = text.sumOf { if (it.code > 127) 1.0 else 0.55 }
        return if (ems <= 0) BIG else (190.0 / ems).toFloat().coerceIn(22f, BIG)
    }

    private const val BIG = 32f

    private fun card(picture: Icon?, logo: Bitmap, line1: String, line2: String, line3: String,
                     open: PendingIntent, aod: Boolean): RemoteViews {
        val v = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.mc_transit_rear)
        v.setImageViewIcon(R.id.mc_transit_rear_floor, Icon.createWithBitmap(floor(if (aod) NIGHT else DAY)))
        if (picture != null) {
            v.setImageViewIcon(R.id.mc_transit_rear_picture, picture)
        } else {
            v.setViewVisibility(R.id.mc_transit_rear_picture, View.INVISIBLE)
        }
        v.setImageViewIcon(R.id.mc_transit_rear_logo, Icon.createWithBitmap(logo))
        v.setTextViewText(R.id.mc_transit_rear_line1, line1)
        v.setTextColor(R.id.mc_transit_rear_line1, if (aod) LIGHT_SOFT else INK_SOFT)
        v.setTextViewText(R.id.mc_transit_rear_line2, line2)
        v.setTextColor(R.id.mc_transit_rear_line2, if (aod) LIGHT else INK)
        v.setTextViewTextSize(R.id.mc_transit_rear_line2, TypedValue.COMPLEX_UNIT_SP, bigSize(line2))
        v.setTextViewText(R.id.mc_transit_rear_line3, line3)
        v.setTextColor(R.id.mc_transit_rear_line3, BLUE)
        v.setOnClickPendingIntent(R.id.mc_transit_rear_root, open)
        return v
    }

    /**
     * 智能助理's train, as a resource of its own: the back screen loads it from that package, so
     * nothing of theirs is copied into this module. Null where 智能助理 is missing or renamed it -
     * the card then has words only, which is still better than a car.
     */
    private fun train(ctx: Context): Icon? {
        val id = trainId ?: try {
            ctx.packageManager.getResourcesForApplication(ASSISTANT)
                .getIdentifier(TRAIN, "drawable", ASSISTANT)
        } catch (t: Throwable) {
            0
        }.also {
            trainId = it
            if (it == 0) Xp.log(TAG + "no $TRAIN in $ASSISTANT, card without a picture")
        }
        return if (id != 0) Icon.createWithResource(ASSISTANT, id) else null
    }

    /** One column of [colours], stretched across the card by the layout. */
    private fun floor(colours: IntArray): Bitmap {
        val rows = 64
        val b = Bitmap.createBitmap(1, rows, Bitmap.Config.ARGB_8888)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, rows.toFloat(), colours, STOPS, Shader.TileMode.CLAMP)
        }
        Canvas(b).drawRect(0f, 0f, 1f, rows.toFloat(), p)
        return b
    }
}

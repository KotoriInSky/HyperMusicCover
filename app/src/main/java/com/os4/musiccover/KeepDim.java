package com.os4.musiccover;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

/**
 * 保留壁纸's veil: the user's own wallpaper, a little darker while the lyric page is up.
 *
 * A view at the top of keyguard_background_layer rather than anything in the wallpaper process,
 * so a still, a live and a shared wallpaper all take the same one. The clock's glass samples the
 * wallpaper window and not this, so the small clock refracts the wallpaper a little brighter than
 * it sits; at this strength that is the trade for one path instead of three.
 *
 * No animator of its own. It rides the clock's progress - ClockCollapse hands the media card one
 * number per frame and this takes the same one - so the veil, the clock and the card arrive
 * together, as everything else in the cover's transition does.
 *
 * And it gives way to the doze. The OEM shrinks the whole keyguard (keyguard_root_view, to about
 * 0.95) for the AOD and grows it back on the wake, while the wallpaper window keeps its size - so
 * a veil inside the keyguard shrank away from the screen's edges and left a lit frame round it.
 * Its strength follows that zoom: none at the AOD's size, all of it at full size, steeply, so what
 * is left of the frame on the way back is gone before it is dark enough to see.
 */
final class KeepDim {
    private KeepDim() {
    }

    /** How dark at the lyric page: a light veil, enough to lift white words off a pale wallpaper. */
    static final float MAX_ALPHA = 0.25f;

    /** The keyguard's size in the doze; at or below it the veil is clear. */
    private static final float DOZE_ZOOM = 0.95f;

    private static View sView;
    /** What the clock's progress asks for, before the zoom has its say. */
    private static float sWant;
    private static ViewTreeObserver.OnPreDrawListener sZoomWatch;
    private static View sZoomWatched;

    /** Main thread. p is the cover look's progress, 0 the OEM's lock screen and 1 the lyric page. */
    static void apply(float p) {
        float want = Math.max(0f, Math.min(1f, p)) * MAX_ALPHA;
        if (want == sWant && (want == 0f || attached())) return;
        sWant = want;
        if (want <= 0f) {
            View v = sView;
            if (v != null) v.setVisibility(View.GONE);
            unwatch();
            return;
        }
        View v = ensure();
        if (v == null) return;
        show(v);
        watch(v);
    }

    /** The asked-for strength, scaled by how far the keyguard is from its doze size. */
    private static void show(View v) {
        float a = sWant * zoomFactor(v);
        if (v.getAlpha() != a) v.setAlpha(a);
        int vis = a > 0f ? View.VISIBLE : View.INVISIBLE;
        if (v.getVisibility() != vis) v.setVisibility(vis);
    }

    /** 0 at the doze's size, 1 at full size, steep in between. */
    private static float zoomFactor(View v) {
        float s = 1f;
        for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            s *= ((View) p).getScaleX();
        }
        float t = Math.max(0f, Math.min(1f, (s - DOZE_ZOOM) / (1f - DOZE_ZOOM)));
        return t * t * t * t;
    }

    /**
     * The zoom moves on frames this class is not told about - the OEM's own doze animation - so
     * while the veil is wanted it reads the zoom on every frame the keyguard draws.
     */
    private static void watch(final View v) {
        if (sZoomWatched == v && sZoomWatch != null) return;
        unwatch();
        sZoomWatch = () -> {
            if (sWant > 0f) show(v);
            return true;
        };
        v.getViewTreeObserver().addOnPreDrawListener(sZoomWatch);
        sZoomWatched = v;
    }

    private static void unwatch() {
        View w = sZoomWatched;
        ViewTreeObserver.OnPreDrawListener l = sZoomWatch;
        sZoomWatched = null;
        sZoomWatch = null;
        if (w != null && l != null) w.getViewTreeObserver().removeOnPreDrawListener(l);
    }

    private static boolean attached() {
        // Per frame while the clock moves, so no lookup of the layer here: a keyguard rebuilt
        // under the veil detaches it, and that is what sends it back through ensure().
        View v = sView;
        return v != null && v.isAttachedToWindow();
    }

    /**
     * The veil in the background layer, above what is there: a live wallpaper's own TextureView
     * sits in this layer too, and a veil under it would darken nothing. A rebuilt keyguard gets a
     * new one.
     */
    private static View ensure() {
        ViewGroup layer = CoverPush.coverLayer();
        if (layer == null) return null;
        View v = sView;
        if (v != null && v.getParent() == layer) return v;
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        unwatch();
        v = new View(layer.getContext());
        v.setBackgroundColor(Color.BLACK);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layer.addView(v, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sView = v;
        Xp.log(Main.TAG + "keep-wallpaper veil attached");
        return v;
    }
}

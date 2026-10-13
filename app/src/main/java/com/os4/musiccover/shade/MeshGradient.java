package com.os4.musiccover.shade;
import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.Random;

/**
 * The CPU half of the Apple Music style background: the cover turned into a small tinted texture,
 * and a warped mesh of control points coloured from it.
 *
 * A port of the mesh gradient renderer in the Clef player (com.mocharealm.clef), which is itself
 * a port of AMLL's. The numbers below are theirs, read out of that APK, so the look matches; the
 * structure around them is ours. Everything here runs once per track on Main's worker, so the GL
 * thread only ever uploads finished arrays.
 */
final class MeshGradient {

    private MeshGradient() {
    }

    /** Side of the square the cover is reduced to. */
    static final int TEX = 32;
    /** Mesh vertices per control-point cell, along each axis. */
    private static final int SUBDIV = 50;

    /** A finished mesh: interleaved-free arrays, ready for three VBOs and an index buffer. */
    static final class Mesh {
        final float[] pos;
        final float[] uv;
        final float[] color;
        final short[] index;

        Mesh(float[] pos, float[] uv, float[] color, short[] index) {
            this.pos = pos;
            this.uv = uv;
            this.color = color;
            this.index = index;
        }
    }

    /** The texture and the mesh for one cover, built together so they always agree. */
    static final class Frame {
        final Bitmap texture;
        final Mesh mesh;

        Frame(Bitmap texture, Mesh mesh) {
            this.texture = texture;
            this.mesh = mesh;
        }
    }

    private static final Random RANDOM = new Random();

    static Frame build(Bitmap cover) {
        final Bitmap tex = texture(cover);
        return new Frame(tex, mesh(tex, preset()));
    }

    // ------------------------------------------------------------------ texture

    /**
     * 32px square, every pixel's saturation lifted to at least half and its lightness squeezed into
     * 0.15-0.70, then one 5x5 box blur. The lightness squeeze is what keeps the background from
     * ever being white or black, whatever the cover is.
     */
    private static Bitmap texture(Bitmap src) {
        final Bitmap small = shrink(src);
        final int w = small.getWidth();
        final int h = small.getHeight();
        final int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();

        final float[] hsl = new float[3];
        for (int i = 0; i < px.length; i++) {
            toHsl(px[i], hsl);
            if (hsl[1] > 0.1f) hsl[1] = clamp(hsl[1], 0.5f, 1f);
            hsl[2] = hsl[2] * 0.55f + 0.15f;
            px[i] = fromHsl(hsl);
        }

        final int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    final int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -2; dx <= 2; dx++) {
                        final int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        final int c = px[yy * w + xx];
                        r += Color.red(c);
                        g += Color.green(c);
                        b += Color.blue(c);
                        n++;
                    }
                }
                out[y * w + x] = Color.rgb(r / n, g / n, b / n);
            }
        }
        final Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }

    /** Halving steps first: one jump from a full-screen picture samples only a few of its pixels. */
    private static Bitmap shrink(Bitmap src) {
        Bitmap cur = src;
        int w = src.getWidth();
        int h = src.getHeight();
        while (w / 2 >= TEX * 2 || h / 2 >= TEX * 2) {
            w = Math.max(TEX, w / 2);
            h = Math.max(TEX, h / 2);
            final Bitmap next = Bitmap.createScaledBitmap(cur, w, h, true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        final Bitmap out = Bitmap.createScaledBitmap(cur, TEX, TEX, true);
        if (cur != src && cur != out) cur.recycle();
        return out;
    }

    private static void toHsl(int c, float[] out) {
        final float r = Color.red(c) / 255f;
        final float g = Color.green(c) / 255f;
        final float b = Color.blue(c) / 255f;
        final float max = Math.max(r, Math.max(g, b));
        final float min = Math.min(r, Math.min(g, b));
        final float d = max - min;
        final float l = (max + min) / 2f;
        float hue;
        float s;
        if (max == min) {
            hue = 0f;
            s = 0f;
        } else {
            if (max == r) hue = ((g - b) / d) % 6f;
            else if (max == g) hue = (b - r) / d + 2f;
            else hue = (r - g) / d + 4f;
            s = d / (1f - Math.abs(2f * l - 1f));
        }
        hue = (hue * 60f) % 360f;
        if (hue < 0f) hue += 360f;
        out[0] = Math.min(hue, 360f);
        out[1] = clamp(s, 0f, 1f);
        out[2] = clamp(l, 0f, 1f);
    }

    private static int fromHsl(float[] hsl) {
        final float hue = hsl[0];
        final float l = hsl[2];
        final float c = (1f - Math.abs(2f * l - 1f)) * hsl[1];
        final float m = l - c / 2f;
        final float x = c * (1f - Math.abs((hue / 60f) % 2f - 1f));
        float r, g, b;
        switch ((int) hue / 60) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            case 5:
            case 6: r = c; g = 0; b = x; break;
            default: r = -m; g = -m; b = -m; break;
        }
        return Color.rgb(channel(r + m), channel(g + m), channel(b + m));
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }

    // ------------------------------------------------------------------ control points

    /** One control point: grid cell, position, and the two tangents' angle (degrees) and length. */
    private static final class Point {
        int cx, cy;
        float x, y, ur, vr, up, vp;
    }

    /**
     * A random 4-6 by 4-6 grid. The border stays on the screen's edge; inside, positions are pushed
     * around by value noise and each point gets a noise-driven tangent angle and a random length,
     * which is what bends the gradient into those slow swirls.
     */
    private static Point[][] preset() {
        final int cols = RANDOM.nextInt(3) + 4;
        final int rows = RANDOM.nextInt(3) + 4;
        final float ox = RANDOM.nextFloat() * 100f;
        final float oy = RANDOM.nextFloat() * 100f;
        final Point[][] grid = new Point[rows][cols];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                final float fu = i / (float) (cols - 1);
                final float fv = j / (float) (rows - 1);
                final boolean edge = i == 0 || i == cols - 1 || j == 0 || j == rows - 1;
                final float nx = fu * 2f + ox;
                final float ny = fv * 2f + oy;
                float x = noise(nx, ny) * 0.5f + (fu * 2f - 1f);
                float y = noise(nx + 50f, ny + 50f) * 0.5f + (fv * 2f - 1f);
                if (i == 0) x = -1f;
                if (i == cols - 1) x = 1f;
                if (j == 0) y = -1f;
                if (j == rows - 1) y = 1f;
                final float angle = (float) Math.toDegrees(
                        noise(fu * 1.5f - ox, fv * 1.5f - oy) * (float) Math.PI);
                final Point p = new Point();
                p.cx = i;
                p.cy = j;
                p.x = x;
                p.y = y;
                p.ur = edge ? 0f : angle;
                p.vr = edge ? 0f : angle + 90f;
                p.up = edge ? 1f : RANDOM.nextFloat() + 0.5f;
                p.vp = edge ? 1f : RANDOM.nextFloat() + 0.5f;
                grid[j][i] = p;
            }
        }
        return grid;
    }

    private static float noise(float x, float y) {
        final float fx = (float) Math.floor(x);
        final float fy = (float) Math.floor(y);
        final float tx = x - fx;
        final float ty = y - fy;
        final float a = hash(fx, fy);
        final float b = hash(fx + 1f, fy);
        final float c = hash(fx, fy + 1f);
        final float d = hash(fx + 1f, fy + 1f);
        final float sx = (3f - 2f * tx) * tx * tx;
        final float sy = (3f - 2f * ty) * ty * ty;
        return ((d * sx + c * (1f - sx)) * sy) + ((1f - sy) * (b * sx + a * (1f - sx)));
    }

    private static float hash(float x, float y) {
        final float s = (float) Math.sin(y * 78.233f + x * 12.9898f) * 43758.547f;
        return (s - (float) Math.floor(s)) * 2f - 1f;
    }

    // ------------------------------------------------------------------ mesh

    /**
     * A bicubic Hermite patch per cell, sampled SUBDIV times along each side.
     *
     * Position uses the points' tangents; colour uses the differences between neighbours instead,
     * exactly as the source does - including its choice of which differences, so the blend between
     * cells looks the same.
     */
    private static Mesh mesh(Bitmap tex, Point[][] grid) {
        final int rows = grid.length;
        final int cols = grid[0].length;

        // Per point: position, u tangent, v tangent, colour sampled from the texture.
        final float[][] pos = new float[rows * cols][];
        final float[][] tu = new float[rows * cols][];
        final float[][] tv = new float[rows * cols][];
        final float[][] col = new float[rows * cols][];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                final Point p = grid[j][i];
                final int k = j * cols + i;
                pos[k] = new float[]{p.x, p.y};
                final double ur = Math.toRadians(p.ur);
                final double vr = Math.toRadians(p.vr);
                tu[k] = new float[]{(float) Math.cos(ur) * p.up, (float) Math.sin(ur) * p.up};
                tv[k] = new float[]{-(float) Math.sin(vr) * p.vp, (float) Math.cos(vr) * p.vp};
                final int px = clampInt((int) ((p.x + 1f) * 0.5f * (tex.getWidth() - 1)), 0, tex.getWidth() - 1);
                final int py = clampInt((int) ((p.y + 1f) * 0.5f * (tex.getHeight() - 1)), 0, tex.getHeight() - 1);
                final int c = tex.getPixel(px, py);
                col[k] = new float[]{Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f};
            }
        }

        final int uSeg = (cols - 1) * SUBDIV;
        final int vSeg = (rows - 1) * SUBDIV;
        final int w = uSeg + 1;
        final int h = vSeg + 1;
        final float[] outPos = new float[w * h * 2];
        final float[] outUv = new float[w * h * 2];
        final float[] outCol = new float[w * h * 3];

        for (int row = 0; row < h; row++) {
            final float v = row / (float) vSeg;
            int cy = row / SUBDIV;
            if (cy >= rows - 1) cy = rows - 2;
            final float lv = (rows - 1) * v - cy;
            for (int c = 0; c < w; c++) {
                final float u = c / (float) uSeg;
                int cx = c / SUBDIV;
                if (cx >= cols - 1) cx = cols - 2;
                final float lu = (cols - 1) * u - cx;

                final int k00 = cy * cols + cx;
                final int k01 = k00 + 1;
                final int k10 = k00 + cols;
                final int k11 = k10 + 1;
                final int o = row * w + c;

                for (int a = 0; a < 2; a++) {
                    final float top = herm(lu, pos[k00][a], pos[k01][a], tu[k00][a], tu[k01][a]);
                    final float bottom = herm(lu, pos[k10][a], pos[k11][a], tu[k10][a], tu[k11][a]);
                    final float t0 = herm(lu, tv[k00][a], tv[k01][a], 0f, 0f);
                    final float t1 = herm(lu, tv[k10][a], tv[k11][a], 0f, 0f);
                    outPos[o * 2 + a] = herm(lv, top, bottom, t0, t1);
                }
                for (int a = 0; a < 3; a++) {
                    final float c00 = col[k00][a], c01 = col[k01][a];
                    final float c10 = col[k10][a], c11 = col[k11][a];
                    final float dv0 = c10 - c00;
                    final float dv1 = c11 - c01;
                    final float du1 = c11 - c10;
                    final float top = herm(lu, c00, c01, c01 - c00, dv1);
                    final float bottom = herm(lu, c10, c11, du1, du1);
                    final float t0 = herm(lu, dv0, dv1, 0f, 0f);
                    final float t1 = herm(lu, dv0, dv1, 0f, 0f);
                    outCol[o * 3 + a] = herm(lv, top, bottom, t0, t1);
                }
                outUv[o * 2] = u;
                outUv[o * 2 + 1] = 1f - v;
            }
        }

        final short[] index = new short[uSeg * vSeg * 6];
        int n = 0;
        for (int row = 0; row < vSeg; row++) {
            for (int c = 0; c < uSeg; c++) {
                final int a = row * w + c;
                final int b = (row + 1) * w + c;
                index[n++] = (short) a;
                index[n++] = (short) b;
                index[n++] = (short) (a + 1);
                index[n++] = (short) (a + 1);
                index[n++] = (short) b;
                index[n++] = (short) (b + 1);
            }
        }
        return new Mesh(outPos, outUv, outCol, index);
    }

    /** Cubic Hermite: p0 to p1 with tangents m0 and m1. */
    private static float herm(float t, float p0, float p1, float m0, float m1) {
        final float t2 = t * t;
        final float t3 = t2 * t;
        return (2f * t3 - 3f * t2 + 1f) * p0
                + (-2f * t3 + 3f * t2) * p1
                + (t3 - 2f * t2 + t) * m0
                + (t3 - t2) * m1;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}

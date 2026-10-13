package com.os4.musiccover;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The square card's motion cover, as Bitmaps CoverCardLayer draws like its still.
 *
 * A TextureView beside the card would need the card's every effect copied onto it - opacity, the
 * playback spring, the rounded clip, the pad's blur, the swipe's transitionAlpha, the aspect
 * crossfade. Instead each decoded frame is turned into an RGBA HardwareBuffer and wrapped as a
 * Bitmap (no copy), and the card's own onDraw draws it where it would draw the art.
 *
 *   MediaPlayer -> SurfaceTexture (OES, our own EGL context on our own thread)
 *     -> one textured quad into an ImageReader's Surface (RGBA_8888, GPU sampled)
 *     -> Image.getHardwareBuffer() -> Bitmap.wrapHardwareBuffer -> CoverCardLayer.invalidate()
 *
 * A decoder's output cannot be wrapped directly: it is YUV, and Bitmap only wraps RGB buffers.
 *
 * Main thread for control; the GL work runs on its own thread so a slow frame never lands on
 * SystemUI's UI thread. Nothing here runs unless something calls start().
 */
final class CardVideo {

    private CardVideo() {
    }

    private static final String TAG = "[MCCard] video: ";
    /** Buffers in the reader. Two held by us (on screen, and the one before, which the render
     * thread may still be sampling), one being drawn into, one spare. */
    private static final int MAX_IMAGES = 4;
    private static final int MAX_SIDE = 1080;

    // ---------------------------------------------------------------- main thread

    private static String sPath;
    private static MediaPlayer sPlayer;
    private static boolean sWanted;     // CoverCardLayer wants it playing
    private static boolean sPrepared;
    private static HandlerThread sThread;
    private static Handler sGl;
    private static ImageReader sReader;
    private static Image sShown, sBefore;
    private static Bitmap sFrame;
    private static BroadcastReceiver sScreen;
    private static int sSession;

    // ---------------------------------------------------------------- GL thread

    private static EGLDisplay sDisplay = EGL14.EGL_NO_DISPLAY;
    private static EGLContext sContext = EGL14.EGL_NO_CONTEXT;
    private static EGLSurface sSurface = EGL14.EGL_NO_SURFACE;
    private static SurfaceTexture sTexture;
    private static int sOes, sProgram;
    private static FloatBuffer sQuad;
    private static final float[] sMatrix = new float[16];
    private static int sW, sH;

    // ---------------------------------------------------------------- numbers

    private static volatile int sDecoded, sDelivered;
    private static volatile long sGlNs, sGlMaxNs;
    private static long sSince = SystemClock.uptimeMillis();

    // ================================================================ control

    static boolean loaded() {
        return sPath != null;
    }

    /** The newest frame, or null before the first. Main thread; valid until the next frame. */
    static Bitmap frame() {
        return sFrame;
    }

    static void load(Context ctx, String path) {
        unload("reload");
        int w, h;
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(path);
                w = Integer.parseInt(r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                h = Integer.parseInt(r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            } finally {
                r.release();
            }
        } catch (Throwable t) {
            Xp.w(TAG + "cannot read " + path + ": " + t);
            return;
        }
        float down = Math.min(1f, MAX_SIDE / (float) Math.max(w, h));
        sW = Math.max(1, Math.round(w * down));
        sH = Math.max(1, Math.round(h * down));
        sPath = path;
        final int session = ++sSession;
        resetStats();
        sReader = ImageReader.newInstance(sW, sH, PixelFormat.RGBA_8888, MAX_IMAGES,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        sReader.setOnImageAvailableListener(r -> onImage(r, session), Main.main());
        sThread = new HandlerThread("MCCardVideo");
        sThread.start();
        sGl = new Handler(sThread.getLooper());
        final Surface out = sReader.getSurface();
        sGl.post(() -> glSetUp(out, session));
        registerScreen(ctx);
        Xp.log(TAG + "loaded " + path + " " + w + "x" + h + " -> " + sW + "x" + sH);
        CoverCardLayer.refresh();
    }

    static void unload(String why) {
        if (sPath == null) return;
        sSession++;
        sPath = null;
        sPrepared = false;
        MediaPlayer p = sPlayer;
        sPlayer = null;
        if (p != null) {
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        final Handler gl = sGl;
        final HandlerThread th = sThread;
        sGl = null;
        sThread = null;
        if (gl != null) {
            gl.post(CardVideo::glRelease);
            gl.post(th::quitSafely);
        }
        // The card may be mid-draw with sFrame; drop the reference here and close the images a
        // frame later, after the render thread is done with them.
        final Image a = sShown, b = sBefore;
        final ImageReader reader = sReader;
        sShown = sBefore = null;
        sFrame = null;
        sReader = null;
        Main.main().postDelayed(() -> {
            if (a != null) a.close();
            if (b != null) b.close();
            if (reader != null) reader.close();
        }, 100L);
        Xp.log(TAG + "unloaded (" + why + ") " + stats());
        CoverCardLayer.refresh();
    }

    /**
     * Whether the card is showing it now. Called from the card's frame loop whenever its state
     * moves; idempotent. Paused, the decoder stops and so do the frames.
     */
    static void want(boolean on) {
        if (sWanted == on) return;
        sWanted = on;
        apply();
    }

    private static void apply() {
        MediaPlayer p = sPlayer;
        if (p == null || !sPrepared) return;
        try {
            boolean run = sWanted && Main.screenOn();
            if (run && !p.isPlaying()) p.start();
            else if (!run && p.isPlaying()) p.pause();
        } catch (Throwable t) {
            Xp.w(TAG + "play/pause: " + t);
        }
    }

    private static void registerScreen(Context ctx) {
        if (sScreen != null) return;
        sScreen = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                apply();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        ctx.registerReceiver(sScreen, f);
    }

    /** Main thread: the GL side has a SurfaceTexture; start decoding into it. */
    private static void attachPlayer(SurfaceTexture st, int session) {
        if (session != sSession || sPath == null) return;
        Surface surface = new Surface(st);
        try {
            final MediaPlayer p = new MediaPlayer();
            sPlayer = p;
            p.setDataSource(sPath);
            p.setSurface(surface);
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnErrorListener((mp, what, extra) -> {
                Xp.w(TAG + "player error " + what + "/" + extra);
                unload("player error");
                return true;
            });
            p.setOnPreparedListener(mp -> {
                if (sPlayer != mp) return;
                sPrepared = true;
                // Shows the first frame even while paused, so the card has something to fade in.
                mp.seekTo(0);
                apply();
            });
            p.prepareAsync();
        } catch (Throwable t) {
            Xp.w(TAG + "player: " + t);
            unload("player failed");
        } finally {
            surface.release();
        }
    }

    /** Main thread: a frame has been drawn into the reader. */
    private static void onImage(ImageReader r, int session) {
        if (session != sSession || r != sReader) return;
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Throwable t) {
            // All MAX_IMAGES are out: one more than the reader allows. Skip this frame.
            return;
        }
        if (img == null) return;
        HardwareBuffer hb = img.getHardwareBuffer();
        Bitmap b = hb == null ? null : Bitmap.wrapHardwareBuffer(hb,
                ColorSpace.get(ColorSpace.Named.SRGB));
        if (hb != null) hb.close();   // the Bitmap holds its own reference
        if (b == null) {
            img.close();
            return;
        }
        // The one drawn two frames ago is past the render thread now.
        if (sBefore != null) sBefore.close();
        sBefore = sShown;
        sShown = img;
        sFrame = b;
        sDelivered++;
        CoverCardLayer.videoFrame();
    }

    // ================================================================ GL thread

    private static void glSetUp(Surface out, int session) {
        try {
            sDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            EGL14.eglInitialize(sDisplay, v, 0, v, 1);
            int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(sDisplay, attrs, 0, cfg, 0, 1, n, 0) || n[0] < 1) {
                throw new IllegalStateException("no EGL config");
            }
            sContext = EGL14.eglCreateContext(sDisplay, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            sSurface = EGL14.eglCreateWindowSurface(sDisplay, cfg[0], out,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (sContext == EGL14.EGL_NO_CONTEXT || sSurface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("EGL 0x" + Integer.toHexString(EGL14.eglGetError()));
            }
            EGL14.eglMakeCurrent(sDisplay, sSurface, sSurface, sContext);

            int[] id = new int[1];
            GLES20.glGenTextures(1, id, 0);
            sOes = id[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sOes);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            sProgram = program();
            sQuad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            sQuad.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}).position(0);

            final SurfaceTexture st = new SurfaceTexture(sOes);
            st.setDefaultBufferSize(sW, sH);
            sTexture = st;
            st.setOnFrameAvailableListener(s -> glFrame(s), sGl);
            Main.main().post(() -> attachPlayer(st, session));
        } catch (Throwable t) {
            Xp.w(TAG + "GL set-up failed: " + android.util.Log.getStackTraceString(t));
            glRelease();
            Main.main().post(() -> {
                if (session == sSession) unload("GL failure");
            });
        }
    }

    private static void glFrame(SurfaceTexture st) {
        if (st != sTexture || sSurface == EGL14.EGL_NO_SURFACE) return;
        long t0 = System.nanoTime();
        try {
            st.updateTexImage();
            st.getTransformMatrix(sMatrix);
            GLES20.glViewport(0, 0, sW, sH);
            GLES20.glUseProgram(sProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sOes);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(sProgram, "uSt"), 1, false,
                    sMatrix, 0);
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, sQuad);
            GLES20.glEnableVertexAttribArray(0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGL14.eglSwapBuffers(sDisplay, sSurface);
            sDecoded++;
        } catch (Throwable t) {
            Xp.w(TAG + "frame: " + t);
        }
        long ns = System.nanoTime() - t0;
        sGlNs += ns;
        if (ns > sGlMaxNs) sGlMaxNs = ns;
    }

    private static void glRelease() {
        SurfaceTexture st = sTexture;
        sTexture = null;
        if (st != null) {
            st.setOnFrameAvailableListener(null);
            st.release();
        }
        if (sDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(sDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (sSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(sDisplay, sSurface);
            if (sContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(sDisplay, sContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(sDisplay);
        }
        sDisplay = EGL14.EGL_NO_DISPLAY;
        sContext = EGL14.EGL_NO_CONTEXT;
        sSurface = EGL14.EGL_NO_SURFACE;
        sOes = sProgram = 0;
    }

    private static int program() {
        String vs = "attribute vec2 aPos;\n"
                + "uniform mat4 uSt;\n"
                + "varying vec2 vTex;\n"
                + "void main() {\n"
                + "  vTex = (uSt * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;\n"
                + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
                + "}\n";
        String fs = "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "uniform samplerExternalOES uVideo;\n"
                + "varying vec2 vTex;\n"
                + "void main() { gl_FragColor = vec4(texture2D(uVideo, vTex).rgb, 1.0); }\n";
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glBindAttribLocation(p, 0, "aPos");
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    // ================================================================ probe report

    private static void resetStats() {
        sDecoded = sDelivered = 0;
        sGlNs = sGlMaxNs = 0L;
        sSince = SystemClock.uptimeMillis();
    }

    static String stats() {
        long el = Math.max(1L, SystemClock.uptimeMillis() - sSince);
        int d = sDecoded;
        String pos;
        try {
            MediaPlayer p = sPlayer;
            pos = p == null ? "none" : (p.isPlaying() ? "playing" : "paused")
                    + " @" + p.getCurrentPosition() + "ms";
        } catch (Throwable t) {
            pos = "?";
        }
        return "path=" + sPath + " wanted=" + sWanted + " player=" + pos + " over " + el
                + "ms: rendered=" + d + " (" + Math.round(d * 10000.0 / el) / 10.0 + "/s)"
                + " delivered=" + sDelivered
                + " gl avg=" + (d == 0 ? 0 : Math.round(sGlNs / (double) d / 1000.0))
                + "us max=" + Math.round(sGlMaxNs / 1000.0) + "us size=" + sW + "x" + sH;
    }

    static String statsAndReset() {
        String s = stats();
        resetStats();
        return s;
    }
}

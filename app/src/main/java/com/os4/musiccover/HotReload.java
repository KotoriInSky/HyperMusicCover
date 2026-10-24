package com.os4.musiccover;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;

import com.os4.musiccover.lyrics.AppleLyrics;
import com.os4.musiccover.pickup.RearScreen;
import com.os4.musiccover.transit.AmapImmerse;
import com.os4.musiccover.transit.MetroCodeIsland;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Executable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * The two hot-reload entry points. Main forwards {@code onHotReloading}/{@code onHotReloaded}
 * here.
 *
 * <p>Apple Music is the first supported process (Step 5): three hooks and no threads, receivers
 * or views of its own, so retiring it is the check section plus the ledger's pass-through flag.
 * Every other process still refuses, and the app restarts it.
 *
 * <p>The new generation has every static field empty, exactly as after a process start. The
 * saved state carries the few host objects it cannot look up again; persistent settings come
 * from the state file, never from here.
 */
public final class HotReload {

    private HotReload() {
    }

    static final String TAG = "[MCHotReload] ";

    /** The package onPackageLoaded last reported in this process. */
    private static volatile String sProcess = "?";
    /** Every package onPackageLoaded named; more than one per process (see status's log). */
    private static final Set<String> sPkgs = ConcurrentHashMap.newKeySet();
    /** The host loader the first package reported, a hint only; adoption still validates it. */
    private static volatile ClassLoader sHostLoader;
    /** One delayed breaker check per cold start, even when several packages report. */
    private static volatile boolean sBootCheckPosted;

    /** This generation's number; the new code starts at 1 and adoption writes the old one + 1. */
    private static volatile int sGen = 1;
    /** Distinguishes two class loaders whose counters both start at 1. */
    private static final String sClassId = Integer.toHexString(System.identityHashCode(HotReload.class));

    /** Set once retirement has been agreed to: old hookers still run, and say so. */
    private static volatile boolean sRetired;

    /** The adoption in progress, or null. Xp's install path pairs hooks through it. */
    private static volatile HandleStore sStore;

    /** Set while the new generation runs boot: that is not a cold start, so no breaker check. */
    private static volatile boolean sAdoptingBoot;

    private static final String PENDING = "mc_hot_reload_pending";
    private static final String BLOCKED = "mc_hot_reload_blocked";
    /** A pending marker older than this is from a process that was simply restarted later. */
    private static final long PENDING_WINDOW_MS = 2 * 60 * 1000L;
    /** The build the last hot reload did not survive, or empty. For `op=gen`. */
    private static volatile String sBlocked = "";

    // ---------------------------------------------------------------- module side

    /** Called first thing in Main.onPackageLoaded; remembers where this process is. */
    static void onPackageLoaded(String pkg, ClassLoader cl) {
        sProcess = pkg;
        sPkgs.add(pkg);
        if (cl != null && sHostLoader == null) sHostLoader = cl;
        if (!sBootCheckPosted && HotReloadPolicy.SUPPORTED.contains(pkg)) {
            sBootCheckPosted = true;
            // The Application does not exist yet at onPackageLoaded; a second later it does.
            // A process that came up through Main.boot checks again there - whichever runs
            // first clears the marker, and the other finds nothing.
            Gen.postDelayed(() -> onBoot(appContext(null)), 1000L);
        }
    }

    /**
     * The retirement half. Only the check section (A) is implemented: whatever fails here leaves
     * the old generation whole. Refusing is the normal answer while SUPPORTED is empty.
     */
    public static boolean retire(XposedModuleInterface.HotReloadingParam param) {
        Bundle extras = param.getExtras();
        String build = extras == null ? "?" : extras.getString(HotReloadPolicy.KEY_BUILD, "?");
        Xp.log(TAG + "onHotReloading thread=" + Thread.currentThread().getName()
                + " main=" + (Looper.myLooper() == Looper.getMainLooper())
                + " process=" + sProcess + " pkgs=" + sPkgs
                + " gen=" + sGen + " class=" + sClassId + " build=" + build);

        int protocol = extras == null
                ? HotReloadPolicy.PROTOCOL
                : extras.getInt(HotReloadPolicy.KEY_PROTOCOL, HotReloadPolicy.PROTOCOL);
        if (protocol != HotReloadPolicy.PROTOCOL) {
            return refuse("protocol " + protocol + " != " + HotReloadPolicy.PROTOCOL);
        }
        String proc = supportedProcess();
        if (proc == null) {
            return refuse("process not supported");
        }
        // A feature that is in the middle of something it cannot stop reports it here; the
        // first ones arrive with their retire() in Steps 5-7.
        String busy = busy();
        if (busy != null) {
            return refuse(busy);
        }
        if (BuildConfig.DEBUG && extras != null
                && "check".equals(extras.getString(HotReloadPolicy.KEY_FAULT))) {
            return refuse("fault injection: check");
        }

        try {
            // The commit step may not throw; the framework also refuses our own classes, so the
            // encoding - and its check - happens here, where a failure still leaves us running.
            param.setSavedInstanceState(HotReloadState.encode(
                    build, proc, sHostLoader, Main.sAppCtx, saveFeatures(proc), sGen + 1));
        } catch (Throwable t) {
            return refuse("could not save state: " + t);
        }

        // Commit. From here the old hookers pass through and nothing new is posted; the ledger
        // takes back the receivers, observers, listeners, posts, threads and views it recorded.
        // Two seconds is generous for those processes: the work is unregistration, not drawing.
        if (!Gen.retire(2000L)) {
            Xp.w(TAG + "retirement sweep did not finish in time; guarded callbacks are inert");
        }
        sRetired = true;
        Xp.log(TAG + "HotReload retired gen=" + sGen + " process=" + proc + " build=" + build
                + " hooks=" + Gen.hooks.size() + " class=" + sClassId);
        return true;
    }

    private static boolean refuse(String reason) {
        Xp.w(TAG + "HotReload deferred: " + reason + " process=" + sProcess + " pkgs=" + sPkgs
                + " gen=" + sGen);
        return false;
    }

    /** The package this process is supported under, or null. See HotReloadPolicy.SUPPORTED. */
    private static String supportedProcess() {
        for (String p : HotReloadPolicy.SUPPORTED) {
            if (sPkgs.contains(p)) return p;
        }
        return null;
    }

    /** The feature that cannot be interrupted right now, or null. Filled in with the features. */
    private static String busy() {
        int io = Gen.ioInFlight();
        return io > 0 ? "a background request is still open (" + io + ")" : null;
    }

    /**
     * What each feature hands over. Only values that are not ours: primitives, strings, and
     * host or framework objects (HotReloadState checks that before the commit).
     */
    private static Object[][] saveFeatures(String proc) {
        List<Object[]> out = new ArrayList<>();
        if (AppleLyrics.PKG.equals(proc)) {
            out.add(new Object[]{"apple", new Object[]{
                    AppleLyrics.heldId(), AppleLyrics.heldPayload(),
                    AppleLyrics.playingId(), AppleLyrics.playingTitle(),
            }});
        }
        return out.toArray(new Object[0][]);
    }

    /**
     * The adoption half, in the new generation. Installs the same hooks the cold path installs -
     * there is only one installer per process - pairing each with the old handle of the same id
     * and executable. Whatever is left of the old handles is unhooked; any failure here leaves
     * this generation inert (all hookers pass through, nothing is posted) until the process
     * restarts, so the module never runs half of one generation and half of another.
     */
    public static void adopt(XposedModuleInterface.HotReloadedParam param) {
        String proc = param.getProcessName();
        // onPackageLoaded is not replayed, so this is the only place the new generation learns
        // where it is. Without it the next hot reload is refused with "not supported (?)".
        sProcess = proc;
        sPkgs.add(proc);
        Bundle extras = param.getExtras();
        String build = extras == null ? "?" : extras.getString(HotReloadPolicy.KEY_BUILD, "?");

        Xp.log(TAG + "onHotReloaded thread=" + Thread.currentThread().getName()
                + " main=" + (Looper.myLooper() == Looper.getMainLooper())
                + " process=" + proc + " build=" + build + " class=" + sClassId);

        HotReloadState.State state = HotReloadState.decode(param.getSavedInstanceState());
        if (state == null) {
            Xp.w(TAG + "no usable saved state; adopting without it");
        } else {
            Xp.log(TAG + "saved: schema=" + state.schema + " from=" + state.fromBuild
                    + " process=" + state.process + " features=" + state.features.length
                    + " reload=" + state.reloadCount);
        }
        sGen = state != null ? state.reloadCount : 1;
        sRetired = false;

        Context ctx = appContext(state);
        // If this process does not survive the next few seconds, the marker is still here when
        // it comes back, and that cold start blocks this build until the next restart.
        writePending(ctx, build + "@" + System.currentTimeMillis());

        Set<String> oldIds = new java.util.HashSet<>();
        for (XposedInterface.HookHandle h : param.getOldHookHandles()) oldIds.add(h.getId());

        HandleStore store = new HandleStore(param.getOldHookHandles());
        sStore = store;
        boolean failed = false;
        try {
            ClassLoader cl = hostLoader(proc, state, param.getOldHookHandles());
            if (cl == null) {
                throw new IllegalStateException("no host class loader can load the process's classes");
            }
            adoptAppleState(state);
            install(proc, cl);
            if (state != null) adoptFeatures(proc, state);
        } catch (Throwable t) {
            failed = true;
            Xp.w(TAG + "adopt FAILED: " + t);
        } finally {
            sStore = null;
            if (failed) {
                for (XposedInterface.HookHandle h : store.installed()) unhook(h);
                store.clearInstalled();
            }
            int removed = store.unhookRemaining();
            oldIds.removeAll(store.newIds());
            Xp.log(TAG + "HotReload adopted gen=" + sGen + " process=" + proc
                    + " replaced=" + store.replacedCount()
                    + " added=" + store.addedCount()
                    + " removedOld=" + removed
                    + " failed=" + failed
                    + " missing=" + oldIds
                    + " left=" + store.remainingLabels()
                    + " new=" + store.newIds());
            if (failed) {
                Gen.inert = true;
                Xp.w(TAG + "adopt FAILED, module inert in this process until restart");
            } else {
                // Five seconds is long enough for a crash in the first frame to leave the
                // marker behind, and short enough that a later user restart is not blamed.
                Gen.postDelayed(() -> clearPending(ctx), 5000L);
            }
        }
    }

    /** The context the new generation can use before Main.boot has run. */
    private static Context appContext(HotReloadState.State state) {
        if (state != null && state.context != null) return state.context;
        if (Main.sAppCtx != null) return Main.sAppCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void install(String proc, ClassLoader cl) {
        if ("com.android.systemui".equals(proc)) {
            Main.installSystemUi(cl);
        } else if (AppleLyrics.PKG.equals(proc)) {
            AppleLyrics.handle(cl);
        } else if ("com.xiaomi.subscreencenter".equals(proc)) {
            RearScreen.handle(cl);
        } else if ("com.autonavi.minimap".equals(proc)) {
            AmapImmerse.handle(cl);
        } else if ("com.miui.aod".equals(proc)) {
            HyperTweaks.aod(cl);
        } else if ("com.miui.personalassistant".equals(proc)) {
            MetroCodeIsland.INSTANCE.install(cl);
        } else {
            throw new IllegalStateException("no installer for " + proc);
        }
    }

    /**
     * Apple's lyric for the track the session already names, before the hooks go back in. The
     * metadata on the session refers to a lyric only this process holds; without it the lock
     * screen shows an empty page until the next track. Strings only, so the binder thread the
     * framework calls us on is fine.
     */
    private static void adoptAppleState(HotReloadState.State state) {
        if (state == null) return;
        for (Object[] f : state.features) {
            if (!"apple".equals(f[0]) || !(f[1] instanceof Object[])) continue;
            Object[] a = (Object[]) f[1];
            AppleLyrics.adoptState(str(a, 0), str(a, 1), str(a, 2), str(a, 3));
        }
    }

    private static String str(Object[] a, int i) {
        return a != null && i < a.length && a[i] instanceof String ? (String) a[i] : null;
    }

    /** Host objects the features left in the saved state that have to be taken on the main thread. */
    private static void adoptFeatures(String proc, HotReloadState.State state) {
        if (!"com.android.systemui".equals(proc) || state.features.length == 0) return;
        sAdoptingBoot = true;
        Gen.post(() -> {
            try {
                Context ctx = state.context != null ? state.context : Main.sAppCtx;
                if (ctx != null) Main.boot(ctx);
                for (Object[] f : state.features) {
                    if ("clock".equals(f[0]) && f[1] instanceof android.view.View) {
                        Main.onContainerAttached((android.view.View) f[1]);
                    }
                }
            } catch (Throwable t) {
                Xp.w(TAG + "adopt on main failed: " + t);
            } finally {
                sAdoptingBoot = false;
            }
        });
    }

    // ---------------------------------------------------------------- circuit breaker

    /** Called once from Main.boot on a real cold start. */
    static void onBoot(Context ctx) {
        if (sAdoptingBoot || ctx == null) return;
        try {
            File f = new File(ctx.getFilesDir(), PENDING);
            if (!f.exists()) return;
            long age = System.currentTimeMillis() - f.lastModified();
            String mark = read(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (age < 0 || age > PENDING_WINDOW_MS) return;
            sBlocked = mark;
            appendBlocked(ctx, mark);
            Xp.w(TAG + "previous hot reload did not settle (" + mark + ", " + age
                    + "ms ago); this build is blocked until another restart");
        } catch (Throwable t) {
            Xp.w(TAG + "breaker check failed: " + t);
        }
    }

    private static void writePending(Context ctx, String mark) {
        if (ctx == null) return;
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), PENDING))) {
            out.write(mark.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Xp.w(TAG + "could not write the pending marker: " + t);
        }
    }

    private static void clearPending(Context ctx) {
        if (ctx == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(ctx.getFilesDir(), PENDING).delete();
        } catch (Throwable t) {
            Xp.w(TAG + "could not clear the pending marker: " + t);
        }
    }

    private static void appendBlocked(Context ctx, String mark) {
        try (FileOutputStream out = new FileOutputStream(
                new File(ctx.getFilesDir(), BLOCKED), true)) {
            out.write((mark + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Xp.w(TAG + "could not record the blocked build: " + t);
        }
    }

    private static String read(File f) {
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 256)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(b);
                return n <= 0 ? "?" : new String(b, 0, n, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    /** For `op=gen`: whether a previous hot reload left a build blocked. */
    static String status() {
        return "blocked=" + (sBlocked.isEmpty() ? "-" : sBlocked)
                + " gen=" + sGen + " class=" + sClassId + " retired=" + sRetired;
    }

    // ---------------------------------------------------------------- hook installer

    /**
     * An old handle with this id and executable, taken out of the pending list. Null outside an
     * adoption.
     */
    static XposedInterface.HookHandle takeOld(String id, Executable target) {
        HandleStore store = sStore;
        return store == null ? null : store.take(id, target);
    }

    static void onReplaced(String id, XposedInterface.HookHandle handle) {
        HandleStore store = sStore;
        if (store != null) store.replaced(id, handle);
    }

    static void onAdded(String id, XposedInterface.HookHandle handle) {
        HandleStore store = sStore;
        if (store != null) store.added(id, handle);
    }

    /** A replace that failed: leave the old handle for the final unhook, and install anew. */
    static void keepOld(XposedInterface.HookHandle handle) {
        HandleStore store = sStore;
        if (store != null) store.keep(handle);
    }

    static String labelOf(XposedInterface.HookHandle h) {
        return label(h.getExecutable(), h.getId());
    }

    private static void unhook(XposedInterface.HookHandle h) {
        try {
            h.unhook();
        } catch (Throwable t) {
            Xp.w(TAG + "unhook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- host loader

    /**
     * The host's class loader, validated rather than assumed.
     *
     * <p>The first run took the first non-null loader off the old handles, and it could not load
     * the player's own classes - adoption then unhooked the build hook and installed nothing.
     * Candidates are tried against a class the host has, most certain first: the running
     * Application's loader, the loader LSPosed gives the module (its parent is the host's), the
     * saved state's, then whatever the old handles name. Every candidate is logged with the
     * answer.
     */
    private static ClassLoader hostLoader(String proc, HotReloadState.State state,
                                          List<XposedInterface.HookHandle> old) {
        String probe = probeClass(proc);
        List<ClassLoader> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        addCandidate(candidates, names, appLoader(), "application");
        ClassLoader module = HotReload.class.getClassLoader();
        addCandidate(candidates, names, module == null ? null : module.getParent(), "module.parent");
        if (state != null) addCandidate(candidates, names, state.hostLoader, "saved");
        for (XposedInterface.HookHandle h : old) {
            Executable e = h.getExecutable();
            addCandidate(candidates, names,
                    e == null ? null : e.getDeclaringClass().getClassLoader(),
                    "handle:" + (e == null ? "?" : e.getDeclaringClass().getName()));
        }
        for (int i = 0; i < candidates.size(); i++) {
            ClassLoader cl = candidates.get(i);
            if (probe == null) {
                Xp.log(TAG + "host loader from " + names.get(i) + " = " + cl + " (unvalidated)");
                return cl;
            }
            try {
                cl.loadClass(probe);
                Xp.log(TAG + "host loader from " + names.get(i) + " = " + cl
                        + " (loads " + probe + ")");
                return cl;
            } catch (Throwable t) {
                Xp.log(TAG + "host loader from " + names.get(i) + " = " + cl
                        + " cannot load " + probe + ": " + t);
            }
        }
        Xp.w(TAG + "no host loader can load " + probe + "; tried " + names);
        return null;
    }

    /** A class each supported process is certain to have; null means "take the first loader". */
    private static String probeClass(String proc) {
        if (AppleLyrics.PKG.equals(proc)) {
            return "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel";
        }
        if ("com.android.systemui".equals(proc)) {
            return "com.android.systemui.keyguard.KeyguardService";
        }
        // 背屏's classes are obfuscated, and the other three processes' entry points change with
        // their apps. The Application's loader is the first candidate anyway, and the old-handle
        // candidate that once misled us is last; taking the first loader is right by construction.
        return null;
    }

    private static ClassLoader appLoader() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app == null ? null : app.getClass().getClassLoader();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addCandidate(List<ClassLoader> out, List<String> names,
                                     ClassLoader cl, String name) {
        if (cl == null || out.contains(cl)) return;
        out.add(cl);
        names.add(name);
    }

    // ---------------------------------------------------------------- labels

    private static String label(Executable e, String id) {
        if (e == null) return String.valueOf(id);
        return id + "@" + e.getDeclaringClass().getSimpleName() + "." + e.getName();
    }
}

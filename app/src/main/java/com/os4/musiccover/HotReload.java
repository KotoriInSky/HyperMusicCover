package com.os4.musiccover;

import android.os.Bundle;
import android.os.Looper;

import com.os4.musiccover.lyrics.AppleLyrics;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * The hot-reload entry points' work for the one process Step 0 proved the mechanism in:
 * Apple Music. Main forwards {@code onHotReloading}/{@code onHotReloaded} here; every other
 * process refuses, so updating there still means a restart exactly as before.
 *
 * <p>The real retirement/adoption path comes in Step 4: no quiescing, no saved-state schema
 * beyond the small Object[] below, no inert fallback, and the resource ledger lives in
 * {@link Gen}. What is here is the part that does not depend on it - the generation handoff for
 * AppleLyrics' session state, the host class loader validation, and the id/executable pairing
 * that {@link Xp} uses to replace a hook in place.
 *
 * <p>The generation is carried through {@code setSavedInstanceState}, so a successful adoption
 * numbers itself {@code old + 1}; the class identity is logged beside it because the framework
 * does not promise a saved state at all, and two class loaders both starting at 1 would
 * otherwise be indistinguishable.
 */
public final class HotReload {

    private HotReload() {
    }

    static final String TAG = "[MCHotReload] ";

    /** Bumped when extras or the saved shape change; the app sends the same number. */
    public static final int PROTOCOL = 1;

    public static final String KEY_PROTOCOL = "hr_protocol";
    public static final String KEY_BUILD = "hr_build_mark";

    /** The package onPackageLoaded last reported in this process. */
    private static volatile String sProcess = "?";
    /** Every package onPackageLoaded named; more than one per process (see retire's log). */
    private static final Set<String> sPkgs = ConcurrentHashMap.newKeySet();
    /** Whether that included Apple Music, the only process this spike adopts. Sticky. */
    private static volatile boolean sApple;

    /** This generation's number; the new code starts at 1 and adoption writes the old one + 1. */
    private static volatile int sGen = 1;
    /** Distinguishes two class loaders whose counters both start at 1. */
    private static final String sClassId = Integer.toHexString(System.identityHashCode(HotReload.class));

    /** Set once retirement has been agreed to: old hookers still run, and say so. */
    private static volatile boolean sRetired;
    /** True between onHotReloaded starting and its last old handle being dealt with. */
    private static volatile boolean sAdopting;

    /** Old handles not yet claimed by an id; whatever is left is logged and unhooked. */
    private static final List<XposedInterface.HookHandle> sOld = new ArrayList<>();
    /** Handles this generation installed, for the failure path and the log line. */
    private static final List<XposedInterface.HookHandle> sNew = new ArrayList<>();
    private static final List<String> sReplacedIds = new ArrayList<>();
    private static final List<String> sAddedIds = new ArrayList<>();

    // ---------------------------------------------------------------- module side

    /** Called first thing in Main.onPackageLoaded; the one thing this spike remembers per process. */
    static void onPackageLoaded(String pkg) {
        sProcess = pkg;
        sPkgs.add(pkg);
        if (AppleLyrics.PKG.equals(pkg)) sApple = true;
    }

    /**
     * The retirement half. Checks only - Apple Music has nothing to quiesce - and returns false
     * everywhere else, which is what keeps every other process on the existing restart path.
     */
    public static boolean retire(XposedModuleInterface.HotReloadingParam param) {
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        Xp.log(TAG + "onHotReloading thread=" + Thread.currentThread().getName()
                + " main=" + main + " process=" + sProcess + " pkgs=" + sPkgs
                + " gen=" + sGen + " class=" + sClassId);

        Bundle extras = param.getExtras();
        int protocol = extras == null ? PROTOCOL : extras.getInt(KEY_PROTOCOL, PROTOCOL);
        if (protocol != PROTOCOL) {
            Xp.w(TAG + "deferred: protocol " + protocol + " != " + PROTOCOL);
            return false;
        }
        if (!sApple) {
            Xp.w(TAG + "deferred: process not supported (" + sProcess + ")");
            return false;
        }
        try {
            param.setSavedInstanceState(new Object[]{
                    sGen + 1,
                    AppleLyrics.heldId(), AppleLyrics.heldPayload(),
                    AppleLyrics.playingId(), AppleLyrics.playingTitle(),
            });
        } catch (Throwable t) {
            // Nothing has been torn down yet, so refusing here leaves the old code whole.
            Xp.w(TAG + "deferred: could not save Apple state: " + t);
            return false;
        }
        sRetired = true;
        Xp.log(TAG + "retired gen=" + sGen + " process=" + sProcess + " class=" + sClassId);
        return true;
    }

    /**
     * The adoption half, in the new generation. Installs the same hooks the cold path installs -
     * through AppleLyrics.handle, so there is only one installer - and pairs each with the old
     * handle of the same id and executable.
     */
    public static void adopt(XposedModuleInterface.HotReloadedParam param) {
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        String proc = param.getProcessName();
        // onPackageLoaded is not replayed, so this is the only place the new generation learns
        // where it is. Without it the next hot reload is refused with "not supported (?)".
        sProcess = proc;
        sPkgs.add(proc);
        sApple = AppleLyrics.PKG.equals(proc);
        Bundle extras = param.getExtras();
        String build = extras == null ? "?" : extras.getString(KEY_BUILD, "?");

        Xp.log(TAG + "onHotReloaded thread=" + Thread.currentThread().getName()
                + " main=" + main + " process=" + proc + " build=" + build + " class=" + sClassId);

        int gen = 1;
        Object saved = param.getSavedInstanceState();
        if (saved instanceof Object[]) {
            Object[] a = (Object[]) saved;
            if (a.length > 0 && a[0] instanceof Integer) gen = (Integer) a[0];
            AppleLyrics.adoptState(str(a, 1), str(a, 2), str(a, 3), str(a, 4));
        } else if (saved != null) {
            Xp.w(TAG + "saved state of unexpected type " + saved.getClass().getName());
        }
        sGen = gen;
        sRetired = false;

        List<XposedInterface.HookHandle> old = new ArrayList<>(param.getOldHookHandles());
        Set<String> oldIds = new HashSet<>();
        List<String> oldLabels = new ArrayList<>();
        for (XposedInterface.HookHandle h : old) {
            oldIds.add(h.getId());
            oldLabels.add(label(h.getExecutable(), h.getId()));
        }
        Xp.log(TAG + "old handles=" + old.size() + " ids=" + oldLabels);

        synchronized (sOld) {
            sOld.clear();
            sOld.addAll(old);
        }
        sNew.clear();
        sReplacedIds.clear();
        sAddedIds.clear();

        boolean failed = false;
        sAdopting = true;
        try {
            if (AppleLyrics.PKG.equals(proc)) {
                ClassLoader cl = hostLoader(proc, old);
                if (cl == null) {
                    throw new IllegalStateException("no host class loader can load the player");
                }
                AppleLyrics.handle(cl);
            } else {
                Xp.w(TAG + "adopt in a process this spike does not support: " + proc);
            }
        } catch (Throwable t) {
            failed = true;
            Xp.w(TAG + "adopt FAILED: " + t);
        } finally {
            sAdopting = false;
            if (failed) {
                for (XposedInterface.HookHandle h : sNew) unhook(h);
                sNew.clear();
            }
            int removed = 0;
            List<String> remaining = new ArrayList<>();
            synchronized (sOld) {
                for (XposedInterface.HookHandle h : sOld) {
                    remaining.add(label(h.getExecutable(), h.getId()));
                    unhook(h);
                    removed++;
                }
                sOld.clear();
            }
            Set<String> newIds = new HashSet<>(sReplacedIds);
            newIds.addAll(sAddedIds);
            oldIds.removeAll(newIds);
            Xp.log(TAG + "adopted gen=" + gen + " process=" + proc
                    + " replaced=" + sReplacedIds.size()
                    + " added=" + sAddedIds.size()
                    + " removedOld=" + removed
                    + " failed=" + failed
                    + " missing=" + oldIds
                    + " left=" + remaining
                    + " new=" + newIds);
            if (failed) {
                Xp.w(TAG + "adopt FAILED, module inert in this process until restart");
            }
        }
    }

    /**
     * The host's class loader, validated rather than assumed.
     *
     * <p>The first run took the first non-null loader off the old handles, and it could not load
     * the player's own classes - adoption then unhooked the build hook and installed nothing.
     * Candidates are therefore tried against a class we know the host has, most certain first:
     * the running Application's loader, the loader LSPosed gives the module (its parent is the
     * host's), then whatever the old handles name. Every candidate is logged with the answer.
     */
    private static ClassLoader hostLoader(String proc, List<XposedInterface.HookHandle> old) {
        String probe = AppleLyrics.PKG.equals(proc)
                ? "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel" : null;
        List<ClassLoader> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        addCandidate(candidates, names, appLoader(), "application");
        ClassLoader module = HotReload.class.getClassLoader();
        addCandidate(candidates, names, module == null ? null : module.getParent(), "module.parent");
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

    // ---------------------------------------------------------------- hook installer

    /**
     * An old handle with this id and executable, taken out of the pending list. Null outside an
     * adoption, or in the setId experiment where a new hook is installed instead.
     */
    static XposedInterface.HookHandle takeOld(String id, Executable target) {
        if (!sAdopting) return null;
        synchronized (sOld) {
            for (int i = 0; i < sOld.size(); i++) {
                XposedInterface.HookHandle h = sOld.get(i);
                if (id.equals(h.getId()) && target.equals(h.getExecutable())) {
                    sOld.remove(i);
                    return h;
                }
            }
        }
        return null;
    }

    static void onReplaced(String id, XposedInterface.HookHandle handle) {
        sReplacedIds.add(id);
        sNew.add(handle);
    }

    static void onAdded(String id, XposedInterface.HookHandle handle) {
        sAddedIds.add(id);
        sNew.add(handle);
    }

    /** A replace that failed: leave the old handle for the final unhook, and install anew. */
    static void keepOld(XposedInterface.HookHandle handle) {
        synchronized (sOld) {
            sOld.add(handle);
        }
    }

    private static void unhook(XposedInterface.HookHandle h) {
        try {
            h.unhook();
        } catch (Throwable t) {
            Xp.w(TAG + "unhook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- labels

    private static String label(Executable e, String id) {
        if (e == null) return String.valueOf(id);
        return id + "@" + e.getDeclaringClass().getSimpleName() + "." + e.getName();
    }

    private static String str(Object[] a, int i) {
        return a != null && i < a.length && a[i] instanceof String ? (String) a[i] : null;
    }
}

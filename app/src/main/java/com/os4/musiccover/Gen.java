package com.os4.musiccover;

import android.animation.Animator;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * Everything this generation of the module owns inside one process.
 *
 * <p>Hot reload needs one place that knows what the retiring code still holds - hooks, main
 * thread messages, threads, receivers, listeners, animations, views, the host state it wrote -
 * because the framework only hands over hooks. This is that ledger: [retire] takes all of it
 * back, on the main thread, when a generation commits to stepping down.
 *
 * <p>The guard is the one behavioural edge: every hooker is wrapped so that once this
 * generation is retired or inert it proceeds untouched.
 *
 * <p>Rules that GenRulesTest will enforce, once the migration is finished: module code does not
 * call registerReceiver, start threads or executors, post frame callbacks or register host
 * listeners directly; it goes through here. The app's own process (app/, ui/, updater/) is not
 * covered - it is never hot reloaded.
 */
public final class Gen {

    private Gen() {
    }

    /** Set in the retirement's silent step; new work is refused from here on. */
    static volatile boolean quiescing;
    /** Set when retirement is committed: hooks pass through, queued work is dropped. */
    static volatile boolean retired;
    /** Adoption failed: this generation answers nothing until the process restarts. */
    static volatile boolean inert;
    /**
     * Set while a retirement's teardown runs on the main thread. The teardown gives OEM state
     * back through the same helpers the features use at runtime, and those are all refused once
     * the gate is closed - which it already is by the time the teardown runs. So the teardown's
     * own posts run in place instead: same work, on the main thread, before the sweep takes the
     * views they touch.
     */
    private static volatile boolean tearingDown;

    /** Whether this generation may still do its work. */
    static boolean live() {
        return !retired && !inert;
    }

    // ---------------------------------------------------------------- retirement

    /** Runs [task] on the main thread and waits for it; false when it could not be run in time. */
    static boolean onMain(long timeoutMs, Runnable task) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
            return true;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        if (!Main.main().post(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        })) {
            return false;
        }
        try {
            return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Take back everything this generation registered. Called from the commit step of a hot
     * reload, after which nothing here may call into this generation again.
     *
     * <p>The hooks are not touched: the next generation replaces or unhooks them through
     * HotReload, and until then they pass through because [retired] is set. What is left is the
     * callbacks and posts that would keep invoking our classes from the host: receivers,
     * observers, preference and display listeners, media-session callbacks, view listeners,
     * queued messages and frame callbacks, our threads, and our views.
     *
     * <p>Best effort on purpose: this runs after retirement is committed, when refusing is no
     * longer possible. Every step is independent and a failure is left for the next one.
     */
    static boolean retire(long timeoutMs) {
        return retire(timeoutMs, null);
    }

    /**
     * As {@link #retire(long)}, but with one main-thread step in front of the sweep. SystemUI's
     * teardown runs there: the features give the OEM its state back while our views are still
     * in the tree, and only then does the ledger take the views themselves.
     */
    static boolean retire(long timeoutMs, Runnable beforeSweep) {
        quiescing = true;
        retired = true;
        // The teardown gives OEM state back; run late - after the framework has moved on to the
        // next generation - it would shrink the clock or hide the print under the new code. The
        // sweep is safe to run late (it only touches this generation's own registrations), so
        // only the step in front of it is guarded.
        final java.util.concurrent.atomic.AtomicBoolean tooLate =
                new java.util.concurrent.atomic.AtomicBoolean();
        boolean ran = onMain(timeoutMs, () -> {
            if (!tooLate.get() && beforeSweep != null) {
                tearingDown = true;
                try {
                    beforeSweep.run();
                } catch (Throwable t) {
                    Xp.w("[MCGen] teardown before the sweep failed: " + t);
                } finally {
                    tearingDown = false;
                }
            }
            sweep(false);
        });
        if (!ran) tooLate.set(true);
        return ran;
    }

    /**
     * What a dry run takes back: everything registered since the install step, plus the host
     * state and views this generation wrote. What was registered by {@link #markInstallScope()}
     * stays (the dry run does not reinstall), and so do the long-lived threads and executors -
     * their owners are install-time code that is not run again, and some are static finals a
     * second start could not revive. The generation stays live: the caller puts it back up
     * immediately, which is the point of the exercise.
     */
    static boolean sweepForDryRun(long timeoutMs) {
        return onMain(timeoutMs, () -> sweep(true));
    }

    /**
     * Registrations made before the first container attach. A dry run re-runs boot and the
     * attach path, not installSystemUi, so anything recorded here would otherwise be taken back
     * and never put back.
     */
    private static final java.util.Set<Object> INSTALL_KEPT =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Called at the end of installSystemUi: from here on, these registrations outlive a dry run. */
    static void markInstallScope() {
        INSTALL_KEPT.clear();
        INSTALL_KEPT.addAll(receivers);
        INSTALL_KEPT.addAll(observers);
        INSTALL_KEPT.addAll(prefListeners);
        INSTALL_KEPT.addAll(sessionCallbacks);
        INSTALL_KEPT.addAll(activeSessions);
        INSTALL_KEPT.addAll(cameraCallbacks);
        INSTALL_KEPT.addAll(networkCallbacks);
        INSTALL_KEPT.addAll(preDraws);
        INSTALL_KEPT.addAll(attachStates);
        INSTALL_KEPT.addAll(layoutChanges);
        INSTALL_KEPT.addAll(displayListeners);
        INSTALL_KEPT.addAll(swaps);
        INSTALL_KEPT.addAll(ownViews);
        INSTALL_KEPT.addAll(ownWindows);
        INSTALL_KEPT.addAll(animators);
        Xp.log("[MCGen] install scope marked, " + INSTALL_KEPT.size()
                + " registrations outlive a dry run");
    }

    /**
     * @param dryRun keep the install-scoped registrations and the long-lived infrastructure;
     *               see sweepForDryRun.
     */
    private static void sweep(boolean dryRun) {
        // A real retirement calls this from the main thread through Gen.onMain: anything that
        // escaped here would be an uncaught exception in SystemUI, and the commit step may not
        // throw. The steps are independent anyway, so one failure must not stop the rest.
        try {
            sweepNow(dryRun);
        } catch (Throwable t) {
            Xp.w("[MCGen] sweep failed: " + t);
        }
    }

    private static void sweepNow(boolean dryRun) {
        for (java.util.Map.Entry<Runnable, Runnable> e : MAIN_POSTED.entrySet()) {
            try {
                Main.main().removeCallbacks(e.getValue());
            } catch (Throwable ignored) {
            }
        }
        MAIN_POSTED.clear();
        // The wrapper map only knows what Gen posted. Direct main().post() calls made by the
        // features are on the same Handler and would otherwise run after the teardown - one
        // queued card-gone timer would call into the generation that just let go. This Handler
        // is the module's own; no OEM message is on it.
        try {
            Main.main().removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        // Handlers the features made for themselves (DateStatus, the shade's frame driver, the
        // mini player's per-controller one...). Their messages are ours too, and the same rule
        // applies: after retirement nothing of ours may still be queued on the main thread.
        java.util.List<android.os.Handler> handlers;
        synchronized (mainHandlers) {
            handlers = new java.util.ArrayList<>(mainHandlers);
        }
        for (android.os.Handler h : handlers) {
            try {
                h.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {
            }
        }
        mainHandlers.clear();

        for (PostedOn p : postedOn) {
            try {
                p.view.removeCallbacks(p);
            } catch (Throwable ignored) {
            }
        }
        postedOn.clear();

        for (java.util.Map.Entry<Choreographer.FrameCallback, Frame> e : frames.entrySet()) {
            try {
                Choreographer.getInstance().removeFrameCallback(e.getValue());
            } catch (Throwable ignored) {
            }
        }
        frames.clear();

        // Long-lived infrastructure. A real retirement quits it - the next generation builds its
        // own - but a dry run has no next generation to build it: some of these are static
        // finals (the lyric pool) and the rest are cached in statics that install-time code
        // would have to be run again to replace.
        if (!dryRun) {
            for (HandlerThread t : handlerThreads) {
                try {
                    t.quitSafely();
                    t.join(200L);
                } catch (Throwable ignored) {
                }
            }
            handlerThreads.clear();

            for (ExecutorService e : executors) {
                try {
                    e.shutdownNow();
                } catch (Throwable ignored) {
                }
            }
            executors.clear();
        }

        for (ReceiverReg r : receivers) {
            if (kept(dryRun, r)) continue;
            try {
                r.context.unregisterReceiver(r.receiver);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, receivers);

        for (ObserverReg o : observers) {
            if (kept(dryRun, o)) continue;
            try {
                o.resolver.unregisterContentObserver(o.observer);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, observers);

        for (PrefReg p : prefListeners) {
            if (kept(dryRun, p)) continue;
            try {
                p.prefs.unregisterOnSharedPreferenceChangeListener(p.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, prefListeners);

        for (SessionReg s : sessionCallbacks) {
            if (kept(dryRun, s)) continue;
            try {
                s.controller.unregisterCallback(s.callback);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, sessionCallbacks);

        for (ActiveSessionsReg s : activeSessions) {
            if (kept(dryRun, s)) continue;
            try {
                s.manager.removeOnActiveSessionsChangedListener(s.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, activeSessions);

        for (CameraReg c : cameraCallbacks) {
            if (kept(dryRun, c)) continue;
            try {
                c.manager.unregisterAvailabilityCallback(c.callback);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, cameraCallbacks);

        for (NetworkReg n : networkCallbacks) {
            if (kept(dryRun, n)) continue;
            try {
                n.manager.unregisterNetworkCallback(n.callback);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, networkCallbacks);

        for (PreDrawReg p : preDraws) {
            if (kept(dryRun, p)) continue;
            try {
                if (p.view != null) {
                    p.view.getViewTreeObserver().removeOnPreDrawListener(p.listener);
                }
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, preDraws);

        for (AttachReg a : attachStates) {
            if (kept(dryRun, a)) continue;
            try {
                a.view.removeOnAttachStateChangeListener(a.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, attachStates);

        for (LayoutReg l : layoutChanges) {
            if (kept(dryRun, l)) continue;
            try {
                l.view.removeOnLayoutChangeListener(l.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, layoutChanges);

        for (DisplayReg d : displayListeners) {
            if (kept(dryRun, d)) continue;
            try {
                d.manager.unregisterDisplayListener(d.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, displayListeners);

        for (Animator a : animators) {
            if (kept(dryRun, a)) continue;
            try {
                a.cancel();
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, animators);

        for (Swap s : swaps) {
            if (kept(dryRun, s)) continue;
            try {
                if (s.restore != null) s.restore.run();
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, swaps);

        for (OwnedView v : ownViews) {
            if (kept(dryRun, v)) continue;
            try {
                v.parent.removeView(v.view);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, ownViews);

        for (OwnedWindow w : ownWindows) {
            if (kept(dryRun, w)) continue;
            try {
                w.manager.removeViewImmediate(w.view);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, ownWindows);

        // The host listeners recorded as opaque objects (treeListener) have no type to remove
        // them by; the feature that registered one removes it in its own retire. Anything left
        // is named in the log rather than silently called into a retired generation.
        int trees = treeListeners.size();
        treeListeners.clear();

        if (!dryRun) {
            for (Thread t : threads) {
                try {
                    t.join(20L);
                } catch (Throwable ignored) {
                }
            }
            threads.clear();

            ioExecutor.shutdownNow();
        } else {
            // Threads are not ours to stop in a dry run, but their records are: every lookup and
            // every card video added one, and 50 runs left 64 of them in a ledger that is
            // supposed to be a count of what is held now. Only the finished ones go - the
            // module's long-lived threads and pools are still alive and still theirs.
            threads.removeIf(t -> !t.isAlive());
            handlerThreads.removeIf(t -> !t.isAlive());
            executors.removeIf(ExecutorService::isShutdown);
        }
        Xp.log("[MCGen] " + (dryRun ? "dry-run swept" : "retired")
                + ": registrations taken back, treeListeners=" + trees);
    }

    /** Whether a dry run must leave this registration alone; see INSTALL_KEPT. */
    private static boolean kept(boolean dryRun, Object registration) {
        return dryRun && INSTALL_KEPT.contains(registration);
    }

    /** Drop the swept entries; a real retirement drops all of them. */
    private static <T> void retain(boolean dryRun, List<T> list) {
        list.removeIf(r -> !dryRun || !INSTALL_KEPT.contains(r));
    }

    // ---------------------------------------------------------------- what we own

    static final List<XposedInterface.HookHandle> hooks = new CopyOnWriteArrayList<>();
    private static final List<PostedOn> postedOn = new CopyOnWriteArrayList<>();
    /**
     * The main-looper Handlers the features made for themselves; see {@link #handler()}.
     *
     * Weak, so a controller that is gone takes its handler's ledger entry with it instead of
     * leaving one per keyguard rebuild. A handler with a queued message is still reachable
     * through that message, so the sweep can clear it; one with nothing pending has nothing
     * left to clear.
     */
    private static final java.util.Set<android.os.Handler> mainHandlers =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<android.os.Handler, Boolean>()));
    /**
     * One entry per callback, not one per post. An animation re-posts itself from inside its
     * own frame until it lands, so a list of posts grew by a frame's worth per animation.
     */
    private static final java.util.Map<Choreographer.FrameCallback, Frame> frames =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<HandlerThread> handlerThreads = new CopyOnWriteArrayList<>();
    private static final List<ExecutorService> executors = new CopyOnWriteArrayList<>();
    private static final List<Thread> threads = new CopyOnWriteArrayList<>();
    private static final List<ReceiverReg> receivers = new CopyOnWriteArrayList<>();
    private static final List<ObserverReg> observers = new CopyOnWriteArrayList<>();
    private static final List<PrefReg> prefListeners = new CopyOnWriteArrayList<>();
    private static final List<SessionReg> sessionCallbacks = new CopyOnWriteArrayList<>();
    private static final List<ActiveSessionsReg> activeSessions = new CopyOnWriteArrayList<>();
    private static final List<CameraReg> cameraCallbacks = new CopyOnWriteArrayList<>();
    private static final List<NetworkReg> networkCallbacks = new CopyOnWriteArrayList<>();
    private static final List<TreeReg> treeListeners = new CopyOnWriteArrayList<>();
    private static final List<PreDrawReg> preDraws = new CopyOnWriteArrayList<>();
    private static final List<AttachReg> attachStates = new CopyOnWriteArrayList<>();
    private static final List<LayoutReg> layoutChanges = new CopyOnWriteArrayList<>();
    private static final List<DisplayReg> displayListeners = new CopyOnWriteArrayList<>();
    private static final List<Swap> swaps = new CopyOnWriteArrayList<>();
    private static final List<OwnedView> ownViews = new CopyOnWriteArrayList<>();
    private static final List<OwnedWindow> ownWindows = new CopyOnWriteArrayList<>();
    private static final List<Animator> animators = new CopyOnWriteArrayList<>();

    private static final AtomicInteger ioInFlight = new AtomicInteger();
    private static final ExecutorService ioExecutor =
            Executors.newCachedThreadPool(r -> new Thread(guarded(r), "mc-io"));

    private static final class PostedOn implements Runnable {
        final View view;
        /** What the caller holds, for removeCallbacksOn. */
        final Runnable original;

        PostedOn(View view, Runnable original) {
            this.view = view;
            this.original = original;
        }

        @Override
        public void run() {
            // Like the main-handler wrapper below: a runnable that has run is no longer held,
            // or a track change's one-shot post would keep its view and bitmaps for the whole
            // generation.
            postedOn.remove(this);
            if (live()) original.run();
        }
    }

    /** The wrapper posted on the caller's behalf; see frame(). */
    private static final class Frame implements Choreographer.FrameCallback {
        final Choreographer.FrameCallback callback;

        Frame(Choreographer.FrameCallback callback) {
            this.callback = callback;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            try {
                if (live()) callback.doFrame(frameTimeNanos);
            } finally {
                // Only still ours if the callback did not re-post: a finished loop leaves
                // nothing behind, and an in-flight one was replaced by frame() already.
                frames.remove(callback, this);
            }
        }
    }

    private static final class ReceiverReg {
        final Context context;
        final BroadcastReceiver receiver;

        ReceiverReg(Context context, BroadcastReceiver receiver) {
            this.context = context;
            this.receiver = receiver;
        }
    }

    private static final class ObserverReg {
        final ContentResolver resolver;
        final ContentObserver observer;

        ObserverReg(ContentResolver resolver, ContentObserver observer) {
            this.resolver = resolver;
            this.observer = observer;
        }
    }

    private static final class PrefReg {
        final SharedPreferences prefs;
        final SharedPreferences.OnSharedPreferenceChangeListener listener;

        PrefReg(SharedPreferences prefs,
                SharedPreferences.OnSharedPreferenceChangeListener listener) {
            this.prefs = prefs;
            this.listener = listener;
        }
    }

    private static final class SessionReg {
        final MediaController controller;
        final MediaController.Callback callback;

        SessionReg(MediaController controller, MediaController.Callback callback) {
            this.controller = controller;
            this.callback = callback;
        }
    }

    private static final class TreeReg {
        final View view;
        final Object listener;

        TreeReg(View view, Object listener) {
            this.view = view;
            this.listener = listener;
        }
    }

    private static final class ActiveSessionsReg {
        final MediaSessionManager manager;
        final MediaSessionManager.OnActiveSessionsChangedListener listener;

        ActiveSessionsReg(MediaSessionManager manager,
                          MediaSessionManager.OnActiveSessionsChangedListener listener) {
            this.manager = manager;
            this.listener = listener;
        }
    }

    private static final class CameraReg {
        final CameraManager manager;
        final CameraManager.AvailabilityCallback callback;

        CameraReg(CameraManager manager, CameraManager.AvailabilityCallback callback) {
            this.manager = manager;
            this.callback = callback;
        }
    }

    private static final class NetworkReg {
        final ConnectivityManager manager;
        final ConnectivityManager.NetworkCallback callback;

        NetworkReg(ConnectivityManager manager, ConnectivityManager.NetworkCallback callback) {
            this.manager = manager;
            this.callback = callback;
        }
    }

    private static final class LayoutReg {
        final View view;
        final View.OnLayoutChangeListener listener;

        LayoutReg(View view, View.OnLayoutChangeListener listener) {
            this.view = view;
            this.listener = listener;
        }
    }

    private static final class PreDrawReg {
        final View view;
        final ViewTreeObserver.OnPreDrawListener listener;

        PreDrawReg(View view, ViewTreeObserver.OnPreDrawListener listener) {
            this.view = view;
            this.listener = listener;
        }
    }

    private static final class AttachReg {
        final View view;
        final View.OnAttachStateChangeListener listener;

        AttachReg(View view, View.OnAttachStateChangeListener listener) {
            this.view = view;
            this.listener = listener;
        }
    }

    private static final class DisplayReg {
        final DisplayManager manager;
        final DisplayManager.DisplayListener listener;

        DisplayReg(DisplayManager manager, DisplayManager.DisplayListener listener) {
            this.manager = manager;
            this.listener = listener;
        }
    }

    private static final class Swap {
        final Object owner;
        final String what;
        final Object original;
        final Runnable restore;

        Swap(Object owner, String what, Object original, Runnable restore) {
            this.owner = owner;
            this.what = what;
            this.original = original;
            this.restore = restore;
        }
    }

    private static final class OwnedView {
        final ViewGroup parent;
        final View view;

        OwnedView(ViewGroup parent, View view) {
            this.parent = parent;
            this.view = view;
        }
    }

    private static final class OwnedWindow {
        final WindowManager manager;
        final View view;

        OwnedWindow(WindowManager manager, View view) {
            this.manager = manager;
            this.view = view;
        }
    }

    // ---------------------------------------------------------------- main thread

    /**
     * The wrapper posted for each runnable, so removeCallbacks can find it: a guarded copy is
     * a different object from the one the caller holds. A runnable that has run takes itself
     * out, so this only holds what is still queued.
     */
    private static final java.util.Map<Runnable, Runnable> MAIN_POSTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A main-looper Handler of the module's own, for features that need their own queue rather
     * than [post]'s. Registered so retirement can clear what is still waiting on it - a delayed
     * refresh queued here runs on this generation's code, which by then must not run at all.
     */
    public static android.os.Handler handler() {
        return watchHandler(new android.os.Handler(android.os.Looper.getMainLooper()));
    }

    /** A Handler the feature had to subclass (a Messenger's, say): same registration, handed back. */
    public static android.os.Handler watchHandler(android.os.Handler h) {
        if (h != null) mainHandlers.add(h);
        return h;
    }

    /**
     * Runs [r] now when it belongs to a teardown on the main thread; true when it did. A
     * teardown's steps are synchronous hand-backs - restoring the cut-out, the panel's
     * presence, the glass - and queueing them would only let the sweep drop them.
     */
    private static boolean runInTeardown(Runnable r) {
        if (!tearingDown || r == null) return false;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return false;
        try {
            r.run();
        } catch (Throwable t) {
            Xp.w("[MCGen] teardown action failed: " + t);
        }
        return true;
    }

    /** Post to the module's main handler; refused once quiescing or inert. */
    public static void post(Runnable r) {
        if (runInTeardown(r)) return;
        if (quiescing || inert) return;
        Main.main().post(mainGuarded(r));
    }

    public static void postDelayed(Runnable r, long ms) {
        // Only an immediate step is a hand-back; a delayed one (a fade, a parked swap) is
        // something the teardown is meant to cancel, not run in the middle of itself.
        if (ms <= 0L && runInTeardown(r)) return;
        if (quiescing || inert) return;
        Main.main().postDelayed(mainGuarded(r), ms);
    }

    /** The counterpart of post/postDelayed; only the handler Gen posted through is targeted. */
    public static void removeCallbacks(Runnable r) {
        Runnable g = MAIN_POSTED.remove(r);
        Main.main().removeCallbacks(g == null ? r : g);
    }

    private static Runnable mainGuarded(Runnable r) {
        return MAIN_POSTED.computeIfAbsent(r, key -> new Runnable() {
            @Override
            public void run() {
                MAIN_POSTED.remove(key);
                if (live()) key.run();
            }
        });
    }

    /** Post on a host view, and remember it so retirement can take it back out. */
    public static void postOn(View view, Runnable r) {
        if (view == null || runInTeardown(r)) return;
        if (quiescing || inert) return;
        Runnable g = remember(view, r);
        view.post(g);
    }

    /** As {@link #postOn}, after [ms] on the view's own handler. */
    public static void postDelayedOn(View view, Runnable r, long ms) {
        if (view == null || (ms <= 0L && runInTeardown(r))) return;
        if (quiescing || inert) return;
        Runnable g = remember(view, r);
        view.postDelayed(g, ms);
    }

    /** As {@link #postOn}, on the view's next animation frame. Retirement removes both alike. */
    public static void postOnAnimation(View view, Runnable r) {
        if (view == null || runInTeardown(r)) return;
        if (quiescing || inert) return;
        Runnable g = remember(view, r);
        view.postOnAnimation(g);
    }

    /**
     * The caller's own view post, taken back (see {@link #postOn}); callers keep their own
     * Runnable for the removal, so the wrapper is looked up by the original.
     */
    public static void removeCallbacksOn(View view, Runnable r) {
        if (view == null || r == null) return;
        // A reposting loop may have queued the same Runnable more than once; each copy's wrapper
        // has to come off the view as well as out of the ledger.
        java.util.List<PostedOn> hits = new java.util.ArrayList<>();
        for (PostedOn p : postedOn) {
            if (p.view == view && p.original == r) hits.add(p);
        }
        if (hits.isEmpty()) {
            view.removeCallbacks(r);
            return;
        }
        for (PostedOn p : hits) {
            postedOn.remove(p);
            try {
                view.removeCallbacks(p);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Runnable remember(View view, Runnable original) {
        PostedOn g = new PostedOn(view, original);
        postedOn.add(g);
        return g;
    }

    /** Post a frame callback, and remember the wrapper so retirement (or unframe) can take it back. */
    public static void frame(Choreographer.FrameCallback cb) {
        if (quiescing || inert) return;
        Frame wrapped = new Frame(cb);
        frames.put(cb, wrapped);
        Choreographer.getInstance().postFrameCallback(wrapped);
    }

    public static void unframe(Choreographer.FrameCallback cb) {
        Frame wrapped = frames.remove(cb);
        Choreographer.getInstance().removeFrameCallback(wrapped == null ? cb : wrapped);
    }

    // ---------------------------------------------------------------- threads

    public static HandlerThread handlerThread(String name) {
        return handlerThread(name, android.os.Process.THREAD_PRIORITY_DEFAULT);
    }

    public static HandlerThread handlerThread(String name, int priority) {
        HandlerThread t = new HandlerThread(name, priority);
        t.start();
        handlerThreads.add(t);
        return t;
    }

    public static ExecutorService executor(String name) {
        ExecutorService e = Executors.newSingleThreadExecutor(r -> new Thread(guarded(r), name));
        executors.add(e);
        return e;
    }

    public static ExecutorService executor(String name, int priority, boolean daemon) {
        ExecutorService e = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(guarded(r), name);
            t.setDaemon(daemon);
            t.setPriority(priority);
            return t;
        });
        executors.add(e);
        return e;
    }

    public static ExecutorService executor(String name, int threads, int priority, boolean daemon) {
        ExecutorService e = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(guarded(r), name);
            t.setDaemon(daemon);
            t.setPriority(priority);
            return t;
        });
        executors.add(e);
        return e;
    }

    public static Thread thread(String name, Runnable r) {
        Thread t = new Thread(guarded(r), name);
        threads.add(t);
        t.start();
        return t;
    }

    /** A background call with an in-flight count, so retirement can tell whether one is open. */
    public static <T> Future<T> io(Callable<T> c) {
        ioInFlight.incrementAndGet();
        return ioExecutor.submit(() -> {
            try {
                return live() ? c.call() : null;
            } finally {
                ioInFlight.decrementAndGet();
            }
        });
    }

    public static int ioInFlight() {
        return ioInFlight.get();
    }

    // ---------------------------------------------------------------- host callbacks

    public static Intent receiver(Context c, BroadcastReceiver r, IntentFilter f, int flags) {
        Intent sticky = c.registerReceiver(r, f, flags);
        receivers.add(new ReceiverReg(c, r));
        return sticky;
    }

    public static Intent receiver(Context c, BroadcastReceiver r, IntentFilter f) {
        Intent sticky = c.registerReceiver(r, f);
        receivers.add(new ReceiverReg(c, r));
        return sticky;
    }

    /**
     * A sticky query, not a registration: a null receiver returns what is there and leaves
     * nothing to unregister. Kept here so no call site has to spell registerReceiver itself.
     */
    public static Intent stickyReceiver(Context c, IntentFilter f) {
        return c.registerReceiver(null, f);
    }

    public static Intent receiver(Context c, BroadcastReceiver r, IntentFilter f, String permission,
                           Handler handler) {
        Intent sticky = c.registerReceiver(r, f, permission, handler);
        receivers.add(new ReceiverReg(c, r));
        return sticky;
    }

    public static Intent receiver(Context c, BroadcastReceiver r, IntentFilter f, String permission,
                           Handler handler, int flags) {
        Intent sticky = c.registerReceiver(r, f, permission, handler, flags);
        receivers.add(new ReceiverReg(c, r));
        return sticky;
    }

    public static void observer(ContentResolver cr, Uri uri, boolean descendants, ContentObserver o) {
        cr.registerContentObserver(uri, descendants, o);
        observers.add(new ObserverReg(cr, o));
    }

    public static void prefListener(SharedPreferences p,
                             SharedPreferences.OnSharedPreferenceChangeListener l) {
        p.registerOnSharedPreferenceChangeListener(l);
        prefListeners.add(new PrefReg(p, l));
    }

    public static void unprefListener(SharedPreferences p,
                                      SharedPreferences.OnSharedPreferenceChangeListener l) {
        p.unregisterOnSharedPreferenceChangeListener(l);
        prefListeners.removeIf(r -> r.prefs == p && r.listener == l);
    }

    public static void sessionCallback(MediaController c, MediaController.Callback cb, Handler h) {
        c.registerCallback(cb, h);
        sessionCallbacks.add(new SessionReg(c, cb));
    }

    /** The other side of sessionCallback, for a rebind that moves to another controller. */
    public static void unsessionCallback(MediaController c, MediaController.Callback cb) {
        c.unregisterCallback(cb);
        sessionCallbacks.removeIf(r -> r.controller == c && r.callback == cb);
    }

    /** The system's active-session list (which app is playing), not one controller's callbacks. */
    public static void activeSessionsListener(MediaSessionManager m,
                                              MediaSessionManager.OnActiveSessionsChangedListener l,
                                              Handler h) {
        m.addOnActiveSessionsChangedListener(l, null, h);
        activeSessions.add(new ActiveSessionsReg(m, l));
    }

    public static void unactiveSessionsListener(MediaSessionManager m,
                                                MediaSessionManager.OnActiveSessionsChangedListener l) {
        m.removeOnActiveSessionsChangedListener(l);
        activeSessions.removeIf(r -> r.manager == m && r.listener == l);
    }

    /** Which cameras are in use (the pickup card's video-call gate, PickupCamera). */
    public static void cameraCallback(CameraManager m, CameraManager.AvailabilityCallback cb,
                                      Handler h) {
        m.registerAvailabilityCallback(cb, h);
        cameraCallbacks.add(new CameraReg(m, cb));
    }

    public static void uncameraCallback(CameraManager m, CameraManager.AvailabilityCallback cb) {
        m.unregisterAvailabilityCallback(cb);
        cameraCallbacks.removeIf(r -> r.manager == m && r.callback == cb);
    }

    /** The process's default network (the motion-cover downloader's online gate). */
    public static void networkCallback(ConnectivityManager m,
                                       ConnectivityManager.NetworkCallback cb, Handler h) {
        m.registerDefaultNetworkCallback(cb, h);
        networkCallbacks.add(new NetworkReg(m, cb));
    }

    public static void unnetworkCallback(ConnectivityManager m,
                                         ConnectivityManager.NetworkCallback cb) {
        m.unregisterNetworkCallback(cb);
        networkCallbacks.removeIf(r -> r.manager == m && r.callback == cb);
    }

    /**
     * A listener on a host view whose type varies (pre-draw, layout, attach). Recorded here so
     * retirement can find it; the typed removal is the caller's, by kind.
     */
    public static void treeListener(View v, Object listener) {
        treeListeners.add(new TreeReg(v, listener));
    }

    // ---------------------------------------------------------------- view listeners

    public static void preDraw(View v, ViewTreeObserver.OnPreDrawListener l) {
        v.getViewTreeObserver().addOnPreDrawListener(l);
        preDraws.add(new PreDrawReg(v, l));
    }

    /** For a call site that holds the observer rather than the view it belongs to. */
    public static void preDraw(ViewTreeObserver vto, ViewTreeObserver.OnPreDrawListener l) {
        vto.addOnPreDrawListener(l);
        preDraws.add(new PreDrawReg(null, l));
    }

    public static void unpreDraw(View v, ViewTreeObserver.OnPreDrawListener l) {
        v.getViewTreeObserver().removeOnPreDrawListener(l);
        preDraws.removeIf(r -> r.view == v && r.listener == l);
    }

    /** For a call site that only kept the observer it added through, not the view. */
    public static void unpreDraw(ViewTreeObserver vto, ViewTreeObserver.OnPreDrawListener l) {
        vto.removeOnPreDrawListener(l);
        preDraws.removeIf(r -> r.listener == l);
    }

    public static void attachState(View v, View.OnAttachStateChangeListener l) {
        v.addOnAttachStateChangeListener(l);
        attachStates.add(new AttachReg(v, l));
    }

    public static void unattachState(View v, View.OnAttachStateChangeListener l) {
        v.removeOnAttachStateChangeListener(l);
        attachStates.removeIf(r -> r.view == v && r.listener == l);
    }

    public static void layoutChange(View v, View.OnLayoutChangeListener l) {
        v.addOnLayoutChangeListener(l);
        layoutChanges.add(new LayoutReg(v, l));
    }

    public static void unlayoutChange(View v, View.OnLayoutChangeListener l) {
        v.removeOnLayoutChangeListener(l);
        layoutChanges.removeIf(r -> r.view == v && r.listener == l);
    }

    public static void displayListener(DisplayManager dm, DisplayManager.DisplayListener l,
                                       Handler h) {
        dm.registerDisplayListener(l, h);
        displayListeners.add(new DisplayReg(dm, l));
    }

    public static void undisplayListener(DisplayManager dm, DisplayManager.DisplayListener l) {
        dm.unregisterDisplayListener(l);
        displayListeners.removeIf(r -> r.manager == dm && r.listener == l);
    }

    // ---------------------------------------------------------------- what we changed

    /** A host value we wrote, with the way back. */
    public static <T> void swap(Object owner, String what, T original, Runnable restore) {
        swaps.add(new Swap(owner, what, original, restore));
        Xp.d("[MCGen] swap " + what + " (had " + original + ")");
    }

    public static void ownView(ViewGroup parent, View v) {
        // A view put back into a rebuilt tree would otherwise be recorded once per attach; the
        // sweep only ever needs the latest parent.
        ownViews.removeIf(o -> o.view == v);
        ownViews.add(new OwnedView(parent, v));
    }

    public static void ownWindow(WindowManager manager, View v) {
        ownWindows.removeIf(o -> o.view == v);
        ownWindows.add(new OwnedWindow(manager, v));
    }

    public static <A extends Animator> A anim(A a) {
        animators.add(a);
        return a;
    }

    /** Called by Xp for every hook it installs or replaces. */
    static void onHook(XposedInterface.HookHandle h) {
        hooks.add(h);
    }

    // ---------------------------------------------------------------- guard and report

    private static Runnable guarded(Runnable r) {
        return () -> {
            if (live()) r.run();
        };
    }

    /** What this generation holds, for the probe and for the hot-reload log line. */
    static String report() {
        return "class=" + Integer.toHexString(System.identityHashCode(Gen.class))
                + " live=" + live()
                + " quiescing=" + quiescing
                + " retired=" + retired
                + " inert=" + inert
                + " hooks=" + hooks.size()
                + " threads=" + threads.size()
                + " handlerThreads=" + handlerThreads.size()
                + " executors=" + executors.size()
                + " receivers=" + receivers.size()
                + " observers=" + observers.size()
                + " prefListeners=" + prefListeners.size()
                + " sessionCallbacks=" + sessionCallbacks.size()
                + " activeSessions=" + activeSessions.size()
                + " cameraCallbacks=" + cameraCallbacks.size()
                + " networkCallbacks=" + networkCallbacks.size()
                + " treeListeners=" + treeListeners.size()
                + " preDraws=" + preDraws.size()
                + " attachStates=" + attachStates.size()
                + " layoutChanges=" + layoutChanges.size()
                + " displayListeners=" + displayListeners.size()
                + " postedOn=" + postedOn.size()
                + " mainPosted=" + MAIN_POSTED.size()
                + " frames=" + frames.size()
                + " swaps=" + swaps.size()
                + " ownViews=" + ownViews.size()
                + " ownWindows=" + ownWindows.size()
                + " animators=" + animators.size()
                + " ioInFlight=" + ioInFlight.get();
    }
}

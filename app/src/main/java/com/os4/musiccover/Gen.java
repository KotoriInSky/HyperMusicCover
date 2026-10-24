package com.os4.musiccover;

import android.animation.Animator;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.media.session.MediaController;
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
 * because the framework only hands over hooks. This is that ledger. Nothing is retired yet;
 * Step 2 is about routing the installs through it, so the counts already describe the process.
 *
 * <p>The guard is the one behavioural edge: every hooker is wrapped so that once this
 * generation is retired or inert it proceeds untouched. Today neither flag is ever set, so the
 * wrapper costs a boolean and changes nothing.
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

    /** Whether this generation may still do its work. */
    static boolean live() {
        return !retired && !inert;
    }

    // ---------------------------------------------------------------- what we own

    static final List<XposedInterface.HookHandle> hooks = new CopyOnWriteArrayList<>();
    private static final List<PostedOn> postedOn = new CopyOnWriteArrayList<>();
    private static final List<Frame> frames = new CopyOnWriteArrayList<>();
    private static final List<HandlerThread> handlerThreads = new CopyOnWriteArrayList<>();
    private static final List<ExecutorService> executors = new CopyOnWriteArrayList<>();
    private static final List<Thread> threads = new CopyOnWriteArrayList<>();
    private static final List<ReceiverReg> receivers = new CopyOnWriteArrayList<>();
    private static final List<ObserverReg> observers = new CopyOnWriteArrayList<>();
    private static final List<PrefReg> prefListeners = new CopyOnWriteArrayList<>();
    private static final List<SessionReg> sessionCallbacks = new CopyOnWriteArrayList<>();
    private static final List<TreeReg> treeListeners = new CopyOnWriteArrayList<>();
    private static final List<PreDrawReg> preDraws = new CopyOnWriteArrayList<>();
    private static final List<AttachReg> attachStates = new CopyOnWriteArrayList<>();
    private static final List<DisplayReg> displayListeners = new CopyOnWriteArrayList<>();
    private static final List<Swap> swaps = new CopyOnWriteArrayList<>();
    private static final List<OwnedView> ownViews = new CopyOnWriteArrayList<>();
    private static final List<OwnedWindow> ownWindows = new CopyOnWriteArrayList<>();
    private static final List<Animator> animators = new CopyOnWriteArrayList<>();

    private static final AtomicInteger ioInFlight = new AtomicInteger();
    private static final ExecutorService ioExecutor =
            Executors.newCachedThreadPool(r -> new Thread(guarded(r), "mc-io"));

    private static final class PostedOn {
        final View view;
        final Runnable runnable;

        PostedOn(View view, Runnable runnable) {
            this.view = view;
            this.runnable = runnable;
        }
    }

    private static final class Frame {
        final Choreographer.FrameCallback callback;
        final Choreographer.FrameCallback wrapped;

        Frame(Choreographer.FrameCallback callback, Choreographer.FrameCallback wrapped) {
            this.callback = callback;
            this.wrapped = wrapped;
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

    /** Post to the module's main handler; refused once quiescing or inert. */
    public static void post(Runnable r) {
        if (quiescing || inert) return;
        Main.main().post(mainGuarded(r));
    }

    public static void postDelayed(Runnable r, long ms) {
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
        if (view == null || quiescing || inert) return;
        Runnable g = guarded(r);
        postedOn.add(new PostedOn(view, g));
        view.post(g);
    }

    /** Wrappers posted for each frame callback, so unframe can find the one the caller holds. */
    private static final java.util.Map<Choreographer.FrameCallback, Choreographer.FrameCallback>
            FRAME_WRAPPED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void frame(Choreographer.FrameCallback cb) {
        if (quiescing || inert) return;
        Choreographer c = Choreographer.getInstance();
        Choreographer.FrameCallback wrapped = FRAME_WRAPPED.computeIfAbsent(cb, key -> t -> {
            FRAME_WRAPPED.remove(key);
            if (live()) key.doFrame(t);
        });
        frames.add(new Frame(cb, wrapped));
        c.postFrameCallback(wrapped);
    }

    public static void unframe(Choreographer.FrameCallback cb) {
        Choreographer.FrameCallback wrapped = FRAME_WRAPPED.remove(cb);
        Choreographer.getInstance().removeFrameCallback(wrapped == null ? cb : wrapped);
        frames.removeIf(f -> f.callback == cb);
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

    public static void unpreDraw(View v, ViewTreeObserver.OnPreDrawListener l) {
        v.getViewTreeObserver().removeOnPreDrawListener(l);
        preDraws.removeIf(r -> r.view == v && r.listener == l);
    }

    public static void attachState(View v, View.OnAttachStateChangeListener l) {
        v.addOnAttachStateChangeListener(l);
        attachStates.add(new AttachReg(v, l));
    }

    public static void unattachState(View v, View.OnAttachStateChangeListener l) {
        v.removeOnAttachStateChangeListener(l);
        attachStates.removeIf(r -> r.view == v && r.listener == l);
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
        ownViews.add(new OwnedView(parent, v));
    }

    public static void ownWindow(WindowManager manager, View v) {
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
                + " treeListeners=" + treeListeners.size()
                + " preDraws=" + preDraws.size()
                + " attachStates=" + attachStates.size()
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

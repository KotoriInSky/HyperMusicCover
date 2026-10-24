package com.os4.musiccover.island;

import com.os4.musiccover.Xp;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Adds the module's policy to the OEM combine's inputs, so it emits through its own coroutine. */
public final class NotificationSink {
    private final List<WeakReference<Object>> policies = new ArrayList<>();
    /**
     * The 7-element arrays this sink replaced with its 8-element ones. The OEM's own consumer is
     * behind one of our hooks, so a retiring generation has to put the original back: the
     * injected policy is a host object, but the array's shape is not something the OEM built.
     */
    private final List<Injected> injected = new ArrayList<>();
    private int requestedMode;
    private long emissions;
    private final List<String> history = new ArrayList<>();
    private String hookStatus = "not installed";

    private static final class Injected {
        final WeakReference<Object> owner;
        final Field field;
        final Object original;
        final Object[] replaced;

        Injected(Object owner, Field field, Object original, Object[] replaced) {
            this.owner = new WeakReference<>(owner);
            this.field = field;
            this.original = original;
            this.replaced = replaced;
        }
    }

    public synchronized void hookStatus(String status) {
        hookStatus = status;
    }

    public synchronized void record(Object[] original, Object[] effective, String bound) {
        emissions++;
        if (original.length < 7) return;
        String state = "inputs=" + original.length + " policy="
                + (original.length == 8 ? original[7] : "absent")
                + " fingerprint=" + original[5] + "/" + original[6]
                + " effective=" + effective[5] + "/" + effective[6] + " bound=" + bound;
        if (history.isEmpty() || !history.get(history.size() - 1).equals(state)) {
            history.add(state);
            if (history.size() > 8) history.remove(0);
        }
    }

    /**
     * Retirement: put back the array the OEM had, where it still holds ours. Checked rather than
     * written blind - the OEM may have replaced it since, and an old array put back over a newer
     * one would lose a state change rather than restore a shape.
     */
    public synchronized void restore() {
        for (Injected i : injected) {
            Object owner = i.owner.get();
            if (owner == null) continue;
            try {
                if (i.field.get(owner) == i.replaced) i.field.set(owner, i.original);
            } catch (Throwable ignored) {
            }
        }
        injected.clear();
        policies.clear();
        requestedMode = 0;
    }

    /**
     * The live combines this generation injected into, for the next generation to re-attach
     * to. Host objects only; the array field is found again by shape. Read before {@link
     * #restore()}, which puts the originals back and forgets them.
     */
    public synchronized Object[] owners() {
        List<Object> out = new ArrayList<>();
        for (Injected i : injected) {
            Object owner = i.owner.get();
            if (owner != null) out.add(owner);
        }
        return out.toArray();
    }

    /**
     * Takes the live combines over again after a hot reload: the retirement put the OEM's own
     * arrays back, and the already-running combine flows will not call the factory again. Each
     * failure leaves that flow exactly as it was before the module.
     */
    public synchronized void adopt(Object[] owners, int mode) {
        if (owners == null || owners.length == 0) return;
        int n = 0;
        for (Object owner : owners) {
            try {
                if (owner != null && attach(owner, mode)) n++;
            } catch (Throwable t) {
                Xp.log("[MCNotify] re-attach to a live combine failed: " + t);
            }
        }
        Xp.log("[MCNotify] re-attached to " + n + "/" + owners.length
                + " live combine(s), mode=" + mode);
    }

    public synchronized String describe() {
        int live = 0;
        for (WeakReference<Object> policy : policies) if (policy.get() != null) live++;
        return "hook=v4 " + hookStatus + "\npolicyFlows=" + live + " requestedMode=" + requestedMode + " emissions=" + emissions
                + "\n" + String.join("\n", history);
    }

    public synchronized boolean attach(Object combinedFlow, int mode) throws ReflectiveOperationException {
        ClassLoader loader = combinedFlow.getClass().getClassLoader();
        for (Field field : combinedFlow.getClass().getDeclaredFields()) {
            if (!field.getType().isArray()) continue;
            Class<?> flowClass = field.getType().getComponentType();
            // The caller is restricted to the OEM combine and its array factory.
            // Avoid even comparing a Kotlin binary-name literal: R8 adapts that too.
            if (!flowClass.isInterface()) continue;
            field.setAccessible(true);
            Object[] original = (Object[]) field.get(combinedFlow);
            if (original == null || original.length != 7) continue;
            // Derive the host name at runtime. A literal Class.forName Kotlin name is
            // rewritten by our R8 to the module's obfuscated class, absent in SystemUI.
            Object policy = Class.forName(flowClass.getPackage().getName() + ".StateFlowKt", false, loader)
                    .getMethod("MutableStateFlow", Object.class).invoke(null, mode);
            Object[] inputs = (Object[]) Array.newInstance(flowClass, 8);
            System.arraycopy(original, 0, inputs, 0, 7);
            inputs[7] = policy;
            field.set(combinedFlow, inputs);
            policies.add(new WeakReference<>(policy));
            injected.add(new Injected(combinedFlow, field, original, inputs));
            return true;
        }
        return false;
    }

    public synchronized Object[] attachToCombine(Object factory, Object[] sources, int mode)
            throws ReflectiveOperationException {
        if (sources.length != 7) return sources;
        if (!attach(factory, mode)) return sources;
        for (Field field : factory.getClass().getDeclaredFields()) {
            if (field.getType() != sources.getClass()) continue;
            field.setAccessible(true);
            Object[] inputs = (Object[]) field.get(factory);
            if (inputs != null && inputs.length == 8) return inputs;
        }
        throw new IllegalStateException("policy attached without matching combine array");
    }

    public static Object[] withAvoidance(Object[] values, int fallbackMode) {
        Object[] result = values.clone();
        // The first seven inputs remain exactly the OEM's. Input eight wakes the combine
        // and carries the effective policy used for THIS emission, not a later global value.
        int mode = result.length == 8 && result[7] instanceof Integer ? (Integer) result[7] : fallbackMode;
        if ((result.length == 7 || result.length == 8) && mode != 0) {
            result[5] = mode == 2;
            result[6] = mode == 2;
        }
        return result;
    }

    public synchronized boolean refresh(int mode) throws ReflectiveOperationException {
        requestedMode = mode;
        boolean updated = false;
        Iterator<WeakReference<Object>> it = policies.iterator();
        while (it.hasNext()) {
            Object policy = it.next().get();
            if (policy == null) { it.remove(); continue; }
            Method setValue = Class.forName(policy.getClass().getPackage().getName()
                    + ".MutableStateFlow", false, policy.getClass().getClassLoader())
                    .getMethod("setValue", Object.class);
            setValue.invoke(policy, mode);
            updated = true;
        }
        return updated;
    }
}

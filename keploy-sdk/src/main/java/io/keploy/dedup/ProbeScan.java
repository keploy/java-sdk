package io.keploy.dedup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ProbeScan reads the hits of the app's classes straight from the probe arrays
 * that JaCoCo's agent holds in this JVM, in one pass: for each class it notes
 * the probes that were hit and clears them. JaCoCo's own API takes two passes
 * over every class it ever saw, library classes too: a dump, then a reset. At
 * 50k app classes those two passes, and the START's reset, cost 20-44 ms a
 * test; one pass over the app classes alone, split across a few threads, is
 * meant to bring that down.
 *
 * <p>It keeps the probe arrays of the app's classes in an array sorted by
 * class name, and adds to it only when JaCoCo's store has grown (a class was
 * loaded). The arrays are the ones the instrumented code writes to: JaCoCo
 * never replaces them, its reset only fills them with false.
 *
 * <p>A probe hit while the pass runs is the same race JaCoCo's dump and reset
 * have, made narrower: a probe is cleared only right after it was read as
 * hit, so a hit is either in this pass or left for the next one. JaCoCo's
 * reset clears every probe after the dump has read them all, and a hit
 * between the two is lost.
 *
 * <p>It reads JaCoCo's runtime by reflection (the agent's RuntimeData, its
 * ExecutionDataStore, and that store's map of ExecutionData), found by the
 * types of their fields, as the agent shades them under a package of its own.
 * Not found, {@link #locate} throws, and the caller uses JaCoCo's dump and
 * reset.
 */
final class ProbeScan {

    // MIN_CLASSES_PER_WORKER: below this many classes a worker, handing a part
    // to a pool thread costs more than scanning it on the caller's thread.
    static final int MIN_CLASSES_PER_WORKER = 2048;

    // MAX_WORKERS bounds the threads that scan, the caller's included.
    static final int MAX_WORKERS = 4;

    /** A class that was hit: the probes hit, in ascending order. */
    static final class Hit {

        final String name;
        final long id;
        final int probeCount;
        final int[] probes;

        private Hit(String name, long id, int probeCount, int[] probes) {
            this.name = name;
            this.id = id;
            this.probeCount = probeCount;
            this.probes = probes;
        }
    }

    // One of the app's classes as JaCoCo holds it.
    private static final class Slot {

        final String name;
        final long id;
        final boolean[] probes;

        private Slot(String name, long id, boolean[] probes) {
            this.name = name;
            this.id = id;
            this.probes = probes;
        }
    }

    private static final Comparator<Slot> BY_NAME_THEN_ID = new Comparator<Slot>() {
        @Override
        public int compare(Slot a, Slot b) {
            int byName = a.name.compareTo(b.name);
            return byName != 0 ? byName : Long.compare(a.id, b.id);
        }
    };

    // JaCoCo's ExecutionDataStore: JaCoCo locks on it to add a class or to
    // read or reset them all, and so does refresh.
    private final Object store;
    // Its map of class id to ExecutionData.
    private final Map<?, ?> entries;
    private final int workers;
    private final int minPerWorker;
    // The threads that scan the parts other than the caller's: workers - 1
    // of them, made on the first pass that splits, and stopped after a
    // minute idle.
    private ThreadPoolExecutor pool;

    // Read and written under this object's lock (pass is synchronized).
    private Method getName;
    private Method getId;
    private Method getProbes;
    // The store's ids already looked at, the app's classes and the others.
    private final Set<Object> seen = new HashSet<>();
    // The store's size when refresh last looked at it: classes are only
    // ever added to it.
    private int known;
    // The app's classes JaCoCo has seen, sorted by name then id, and their
    // probe arrays at the same index.
    private Slot[] slots = new Slot[0];
    private boolean[][] arrays = new boolean[0][];
    private int maxProbes;

    ProbeScan(Object store, Map<?, ?> entries, int workers, int minPerWorker) {
        this.store = store;
        this.entries = entries;
        this.workers = Math.max(1, Math.min(MAX_WORKERS, workers));
        this.minPerWorker = Math.max(1, minPerWorker);
    }

    /**
     * locate finds the store of the JaCoCo agent object that RT.getAgent()
     * returned, and makes a ProbeScan over it that uses min(4, cores / 2)
     * threads. It throws a ReflectiveOperationException or a
     * RuntimeException when the agent is not laid out as JaCoCo's.
     */
    static ProbeScan locate(Object agent) throws ReflectiveOperationException {
        Object data = fieldOfType(agent, "RuntimeData");
        Object store = fieldOfType(data, "ExecutionDataStore");
        // A field of a type named Map that is not java.util.Map throws a
        // ClassCastException here, a refusal too.
        Map<?, ?> entries = (Map<?, ?>) fieldOfType(store, "Map");
        return new ProbeScan(store, entries, defaultWorkers(), MIN_CLASSES_PER_WORKER);
    }

    // defaultWorkers is min(4, cores / 2), and at least 1: the app runs the
    // test's request on the other cores, and the scan must not slow it down.
    static int defaultWorkers() {
        return Math.max(1, Math.min(MAX_WORKERS, Runtime.getRuntime().availableProcessors() / 2));
    }

    // fieldOfType returns the value of owner's one instance field whose type
    // has the simple name typeName, looking in its class and
    // then its superclasses. Two such fields in one class, or none, or a null
    // value, throw: the layout is not the one this was written for.
    private static Object fieldOfType(Object owner, String typeName) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            Field found = null;
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().getSimpleName().equals(typeName)) {
                    continue;
                }
                if (found != null) {
                    throw new NoSuchFieldException("two " + typeName + " fields in " + type.getName());
                }
                found = field;
            }
            if (found != null) {
                found.setAccessible(true);
                Object value = found.get(owner);
                if (value == null) {
                    throw new NoSuchFieldException(type.getName() + "." + found.getName() + " is null");
                }
                return value;
            }
        }
        throw new NoSuchFieldException("no " + typeName + " field in " + owner.getClass().getName());
    }

    /**
     * LostHitsException says a pass failed after it may have cleared probes:
     * the hits it cleared are gone, so the END it served must not be taken,
     * and the scan must not be used again (JaCoCo's reset clears the rest).
     * Every part of the pass has ended when it is thrown.
     */
    static final class LostHitsException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        LostHitsException(Throwable cause) {
            super("a part of the probe scan failed after it may have cleared probes", cause);
        }
    }

    /**
     * pass returns the app's classes that were hit since the last pass, sorted
     * by name then id, and clears those hits. app is the VM names of the app's
     * classes; it must be the same set on every call, as a class is looked up
     * in it once, when JaCoCo first holds it.
     *
     * <p>It fails in one of two ways. Reading JaCoCo's classes fails before
     * any probe is cleared: a ReflectiveOperationException, or a
     * RuntimeException other than LostHitsException (an Error there is
     * wrapped in one), and JaCoCo's dump still holds every hit. A part of the
     * scan fails (an Error, such as out of memory): LostHitsException, once
     * every part has ended. After either failure the scan must not be used
     * again: a failed read of JaCoCo's classes leaves out classes it will
     * not look up again.
     */
    synchronized List<Hit> pass(Set<String> app) throws ReflectiveOperationException {
        try {
            refresh(app);
        } catch (Error e) {
            throw new IllegalStateException("reading JaCoCo's classes failed", e);
        }
        final Slot[] slots = this.slots;
        final boolean[][] arrays = this.arrays;
        final int maxProbes = this.maxProbes;
        // The caller scans the first part, and the pool the others. A part
        // the pool cannot take (no thread can be made) is scanned on the
        // caller's thread: a FutureTask runs once, so a part the pool queued
        // anyway is not scanned twice.
        int parts = Math.max(1, Math.min(workers, slots.length / minPerWorker));
        int size = (slots.length + parts - 1) / parts;
        List<FutureTask<List<Hit>>> tasks = new ArrayList<>(parts);
        for (int part = 0; part < parts; part++) {
            final int from = Math.min(slots.length, part * size);
            final int to = Math.min(slots.length, from + size);
            tasks.add(new FutureTask<>(() -> scan(slots, arrays, maxProbes, from, to)));
        }
        for (int part = 1; part < parts; part++) {
            submit(tasks.get(part));
        }
        tasks.get(0).run();
        return awaitAll(tasks);
    }

    // submit hands task to the pool, or runs it here when the pool cannot
    // take it: creating the pool or its thread fails (out of memory, or the
    // container's limit on threads).
    private void submit(FutureTask<List<Hit>> task) {
        try {
            pool().execute(task);
        } catch (Throwable t) {
            task.run();
        }
    }

    // awaitAll waits for every part, so that no part still clears probes once
    // pass has returned, and returns their hits in the order of the parts.
    // When a part failed, it throws LostHitsException with the first failure,
    // once every part has ended.
    private static List<Hit> awaitAll(List<FutureTask<List<Hit>>> parts) {
        List<Hit> hits = new ArrayList<>();
        Throwable failure = null;
        boolean interrupted = false;
        try {
            for (FutureTask<List<Hit>> part : parts) {
                while (true) {
                    try {
                        hits.addAll(part.get());
                        break;
                    } catch (InterruptedException e) {
                        // Wait on: the part is clearing probes. Interrupted
                        // again once every part is done.
                        interrupted = true;
                    } catch (ExecutionException e) {
                        if (failure == null) {
                            failure = e.getCause();
                        }
                        break;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) {
            throw new LostHitsException(failure);
        }
        return hits;
    }

    // scan notes and clears the hits of slots [from, to). A probe is cleared
    // only once read as hit, so a hit that races the scan is in it or is
    // left for the next one. maxProbes is the most probes any class has.
    private static List<Hit> scan(Slot[] slots, boolean[][] arrays, int maxProbes, int from, int to) {
        List<Hit> hits = null;
        int[] scratch = new int[maxProbes];
        for (int k = from; k < to; k++) {
            boolean[] probes = arrays[k];
            int count = 0;
            for (int i = 0; i < probes.length; i++) {
                if (probes[i]) {
                    probes[i] = false;
                    scratch[count++] = i;
                }
            }
            if (count > 0) {
                if (hits == null) {
                    hits = new ArrayList<>();
                }
                Slot slot = slots[k];
                hits.add(new Hit(slot.name, slot.id, probes.length, Arrays.copyOf(scratch, count)));
            }
        }
        return hits == null ? Collections.<Hit>emptyList() : hits;
    }

    // refresh adds the app's classes JaCoCo has come to hold since the last
    // call. The store only grows, so an unchanged size means nothing new.
    private void refresh(Set<String> app) throws ReflectiveOperationException {
        List<Object> added;
        synchronized (store) {
            int size = entries.size();
            if (size == known) {
                return;
            }
            added = new ArrayList<>(Math.max(0, size - known));
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                if (seen.add(entry.getKey())) {
                    added.add(entry.getValue());
                }
            }
            known = size;
        }
        List<Slot> fresh = new ArrayList<>();
        for (Object data : added) {
            if (getName == null) {
                getName = data.getClass().getMethod("getName");
                getId = data.getClass().getMethod("getId");
                getProbes = data.getClass().getMethod("getProbes");
            }
            String name = (String) getName.invoke(data);
            if (app.contains(name)) {
                boolean[] probes = (boolean[]) getProbes.invoke(data);
                fresh.add(new Slot(name, (Long) getId.invoke(data), probes));
                maxProbes = Math.max(maxProbes, probes.length);
            }
        }
        Slot[] grown = Arrays.copyOf(slots, slots.length + fresh.size());
        for (int i = 0; i < fresh.size(); i++) {
            grown[slots.length + i] = fresh.get(i);
        }
        Arrays.sort(grown, BY_NAME_THEN_ID);
        boolean[][] grownArrays = new boolean[grown.length][];
        for (int i = 0; i < grown.length; i++) {
            grownArrays[i] = grown[i].probes;
        }
        slots = grown;
        arrays = grownArrays;
    }

    // classes is how many of the app's classes the scan holds.
    synchronized int classes() {
        return slots.length;
    }

    private ExecutorService pool() {
        if (pool == null) {
            final AtomicInteger made = new AtomicInteger();
            ThreadPoolExecutor created = new ThreadPoolExecutor(workers - 1, workers - 1, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(), runnable -> {
                        Thread thread = new Thread(runnable, "keploy-java-dedup-scan-" + made.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });
            created.allowCoreThreadTimeOut(true);
            pool = created;
        }
        return pool;
    }
}

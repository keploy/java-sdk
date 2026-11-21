package io.keploy.dedup;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AppWarmup runs the static initializers of the app's own classes once,
 * before the first test's coverage window, through the class loaders the app
 * loads them with. It runs on a thread of its own: the first START waits for
 * it for a bounded time, then calls {@link #stop()}, which ends it after the
 * class it is initializing, so a slow initializer never holds a START's
 * answer longer than that.
 *
 * <p>The loaders, in the order a class not loaded yet is tried with:
 * <ol>
 * <li>the loaders that defined the app's classes already loaded (from the
 * JVM's {@link Instrumentation}), the one that defined the most first: a
 * Spring Boot fat jar's own loader, or a servlet container's web app
 * loader, which the system loader cannot reach;</li>
 * <li>the context loaders of the live threads, the oldest thread first (the
 * app's main thread, when it is still running): the launcher sets them to the
 * app's loader, and they are all there is without an Instrumentation;</li>
 * <li>the system loader, then the loader of this SDK.</li>
 * </ol>
 * A class already loaded is initialized through the loader that defined it;
 * of a class loaded in two loaders, the copy of the loader that defined more
 * of the app's classes. A class not loaded yet is tried with the next loader
 * only when a loader cannot find it: one a loader found but could not link
 * or initialize is skipped, never loaded again in another loader, where its
 * static initializer would run a second time.
 * Each class is initialized with the thread's context loader set to the
 * loader it is initialized through, as on the app's own threads: a static
 * initializer that looks services up through the context loader finds the
 * app's.
 */
final class AppWarmup {

    private final Instrumentation instrumentation;
    private volatile boolean stopped;
    private volatile int initialized;
    private volatile int skipped;
    private volatile int notReached;
    // What the warmup is doing, for the log when a START stops waiting:
    // -2 before run, -1 while it finds the loaders, else the index in names
    // of the class it is initializing. names is set before at leaves -2.
    private volatile List<String> names;
    private volatile int at = -2;

    // instrumentation is null when the SDK was not started as a -javaagent.
    AppWarmup(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    /** stop ends the warmup after the class it is initializing. */
    void stop() {
        stopped = true;
    }

    int initialized() {
        return initialized;
    }

    int skipped() {
        return skipped;
    }

    /** notReached is how many classes a {@link #stop()} left uninitialized. */
    int notReached() {
        return notReached;
    }

    /**
     * doing says what the warmup is doing now, for a log line: reading the
     * app's class files (before {@link #run}), finding the loaders, or the
     * class it is initializing.
     */
    String doing() {
        int i = at;
        if (i == -2) {
            return "reading the app's class files";
        }
        if (i == -1) {
            return "finding the app's class loaders";
        }
        List<String> all = names;
        return "initializing " + all.get(i) + " (class " + (i + 1) + " of " + all.size() + ")";
    }

    /**
     * run initializes classNames (binary names, com.acme.Shop$Item) in order,
     * on the calling thread, until done or stopped. Only that thread writes
     * the counts.
     */
    void run(List<String> classNames) {
        if (classNames.isEmpty()) {
            return;
        }
        Thread self = Thread.currentThread();
        ClassLoader saved = self.getContextClassLoader();
        try {
            names = classNames;
            at = -1;
            Map<String, Class<?>> loaded = new HashMap<>();
            List<ClassLoader> loaders = loaders(loadedClasses(), new HashSet<>(classNames), loaded,
                    contextLoaders(), ClassLoader.getSystemClassLoader(), AppWarmup.class.getClassLoader());
            for (int i = 0; i < classNames.size(); i++) {
                if (stopped) {
                    notReached = classNames.size() - i;
                    return;
                }
                String name = classNames.get(i);
                at = i;
                Class<?> type = loaded.get(name);
                boolean done = type != null
                        ? initialize(self, name, type.getClassLoader()) == Outcome.INITIALIZED
                        : initializeWithAny(self, name, loaders);
                if (done) {
                    initialized++;
                } else {
                    skipped++;
                }
            }
        } finally {
            self.setContextClassLoader(saved);
        }
    }

    private Class<?>[] loadedClasses() {
        if (instrumentation == null) {
            return new Class<?>[0];
        }
        try {
            return instrumentation.getAllLoadedClasses();
        } catch (RuntimeException e) {
            return new Class<?>[0];
        }
    }

    private enum Outcome { INITIALIZED, NOT_FOUND, FAILED }

    // initializeWithAny tries the next loader only for a class the last one
    // could not find. One it found, and could not link or initialize, is
    // left to lazy init: another loader that sees its bytes would define a
    // second copy and run its static initializer again.
    private static boolean initializeWithAny(Thread self, String name, List<ClassLoader> loaders) {
        for (ClassLoader loader : loaders) {
            Outcome outcome = initialize(self, name, loader);
            if (outcome != Outcome.NOT_FOUND) {
                return outcome == Outcome.INITIALIZED;
            }
        }
        return false;
    }

    private static Outcome initialize(Thread self, String name, ClassLoader loader) {
        try {
            self.setContextClassLoader(loader);
            Class.forName(name, true, loader);
            return Outcome.INITIALIZED;
        } catch (ClassNotFoundException e) {
            return Outcome.NOT_FOUND;
        } catch (Throwable ignored) {
            // Its <clinit> threw, or it could not be linked: left to lazy
            // init.
            return Outcome.FAILED;
        }
    }

    /**
     * loaders orders the loaders a class not loaded yet is tried with (see
     * the class comment), each once, and puts each of the app's classes
     * already loaded in {@code loadedApp}, by name: of a name loaded in two
     * loaders, the copy of the loader that comes first.
     *
     * @param loadedClasses the classes the JVM has loaded
     * @param appClasses the app's classes, by binary name
     * @param loadedApp filled with the app's classes found in loadedClasses
     * @param contextLoaders the live threads' context loaders, in order
     * @param last the loaders to try after all others
     */
    static List<ClassLoader> loaders(Class<?>[] loadedClasses, Set<String> appClasses,
            Map<String, Class<?>> loadedApp, List<ClassLoader> contextLoaders, ClassLoader... last) {
        final Map<ClassLoader, Integer> defined = new HashMap<>();
        List<ClassLoader> definers = new ArrayList<>();
        List<Class<?>> app = new ArrayList<>();
        for (Class<?> type : loadedClasses) {
            ClassLoader loader = type.getClassLoader();
            if (loader == null || !appClasses.contains(type.getName())) {
                continue;
            }
            app.add(type);
            Integer count = defined.get(loader);
            if (count == null) {
                definers.add(loader);
                count = 0;
            }
            defined.put(loader, count + 1);
        }
        // Stable: loaders that defined as many keep the order they were met.
        Collections.sort(definers, new Comparator<ClassLoader>() {
            @Override
            public int compare(ClassLoader a, ClassLoader b) {
                return Integer.compare(defined.get(b), defined.get(a));
            }
        });
        // A name loaded in two loaders (Spring Boot DevTools' restart loader
        // and the system loader, or two web apps): the copy of the loader
        // that defined more of the app's classes is the app's.
        Map<ClassLoader, Integer> rank = new HashMap<>();
        for (int i = 0; i < definers.size(); i++) {
            rank.put(definers.get(i), i);
        }
        for (Class<?> type : app) {
            Class<?> kept = loadedApp.get(type.getName());
            if (kept == null || rank.get(type.getClassLoader()) < rank.get(kept.getClassLoader())) {
                loadedApp.put(type.getName(), type);
            }
        }
        List<ClassLoader> ordered = new ArrayList<>(definers);
        for (ClassLoader loader : contextLoaders) {
            addOnce(ordered, loader);
        }
        for (ClassLoader loader : last) {
            addOnce(ordered, loader);
        }
        return ordered;
    }

    private static void addOnce(List<ClassLoader> loaders, ClassLoader loader) {
        if (loader != null && !loaders.contains(loader)) {
            loaders.add(loader);
        }
    }

    // contextLoaders is the live threads' context loaders, the oldest thread
    // (lowest id) first, each once.
    static List<ClassLoader> contextLoaders() {
        List<Thread> threads;
        try {
            threads = liveThreads();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        Collections.sort(threads, new Comparator<Thread>() {
            @Override
            public int compare(Thread a, Thread b) {
                return Long.compare(a.getId(), b.getId());
            }
        });
        List<ClassLoader> loaders = new ArrayList<>();
        for (Thread thread : threads) {
            addOnce(loaders, thread.getContextClassLoader());
        }
        return loaders;
    }

    // liveThreads enumerates the root thread group: unlike
    // Thread.getAllStackTraces, it takes no safepoint and walks no stack,
    // which on an app with a thousand threads is a pause of the whole app.
    static List<Thread> liveThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return liveThreads(root, root.activeCount() + 16);
    }

    // liveThreads enumerates root's threads into an array of size, doubled
    // while the threads fill it (some may have started since it was sized).
    static List<Thread> liveThreads(ThreadGroup root, int size) {
        Thread[] threads = new Thread[size];
        int n = root.enumerate(threads, true);
        while (n == threads.length) {
            threads = new Thread[threads.length * 2];
            n = root.enumerate(threads, true);
        }
        List<Thread> live = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            live.add(threads[i]);
        }
        return live;
    }
}

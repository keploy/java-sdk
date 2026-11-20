package org.example.servers;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.interceptor.AsyncExecutionInterceptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * AsyncWork hands work that runs AsyncTail after a sleep to Spring, the
 * ways an app's request does: an event published to an async multicaster
 * (SimpleAsyncTaskExecutor, as Fineract's), a task given to a
 * ThreadPoolTaskExecutor (submit, execute, submitListenable), a task
 * given to a SimpleAsyncTaskExecutor with a concurrency limit (which wraps
 * it before doExecute), and an {@code @Async} call that returns nothing or
 * a CompletableFuture (Spring's async interceptor over a plain
 * java.util.concurrent pool, which only the interceptor's hand-off
 * counts). "scheduled" has a scheduler's poller publish the event instead,
 * while the request waits until it has, and "simplescheduled" has the
 * poller of Spring 6.1's SimpleAsyncTaskScheduler (a SimpleAsyncTaskExecutor
 * itself, which starts each run in doExecute) do so. Loaded only when a
 * request asks for it: an app without Spring on its class path never loads
 * it.
 */
public final class AsyncWork {

    private static final SimpleApplicationEventMulticaster EVENTS = new SimpleApplicationEventMulticaster();
    private static final ThreadPoolTaskExecutor POOL = new ThreadPoolTaskExecutor();
    private static final ThreadPoolTaskScheduler SCHEDULER = new ThreadPoolTaskScheduler();
    private static final SimpleAsyncTaskExecutor THROTTLED = new SimpleAsyncTaskExecutor("app-throttled-");
    private static final AtomicReference<Fire> FIRE = new AtomicReference<>();
    private static final AtomicReference<Fire> SIMPLE_FIRE = new AtomicReference<>();
    private static final Worker ASYNC;

    static {
        SimpleAsyncTaskExecutor events = new SimpleAsyncTaskExecutor("app-event-");
        events.setDaemon(true);
        EVENTS.setTaskExecutor(events);
        EVENTS.addApplicationListener(new Listener());
        POOL.setCorePoolSize(2);
        POOL.setDaemon(true);
        POOL.initialize();
        THROTTLED.setDaemon(true);
        THROTTLED.setConcurrencyLimit(4);
        ExecutorService plain = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "app-async");
            thread.setDaemon(true);
            return thread;
        });
        ProxyFactory factory = new ProxyFactory(new SleepyWorker());
        factory.setInterfaces(Worker.class);
        factory.addAdvice(new AsyncExecutionInterceptor(plain));
        ASYNC = (Worker) factory.getProxy();
        SCHEDULER.setDaemon(true);
        SCHEDULER.setThreadNamePrefix("app-poller-");
        SCHEDULER.initialize();
        SCHEDULER.scheduleWithFixedDelay(() -> poll(FIRE), 10);
        startSimplePoller();
    }

    // On Spring 6.1+, a SimpleAsyncTaskScheduler's poller, whose trigger
    // thread starts each run in SimpleAsyncTaskExecutor.doExecute.
    private static void startSimplePoller() {
        Class<?> type;
        try {
            type = Class.forName("org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler");
        } catch (ClassNotFoundException e) {
            return;
        }
        try {
            Object scheduler = type.getConstructor().newInstance();
            type.getMethod("setDaemon", boolean.class).invoke(scheduler, true);
            type.getMethod("setThreadNamePrefix", String.class).invoke(scheduler, "app-simple-poller-");
            Method fixedRate = type.getMethod("scheduleAtFixedRate", Runnable.class, Duration.class);
            fixedRate.invoke(scheduler, (Runnable) () -> poll(SIMPLE_FIRE), Duration.ofMillis(20));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private AsyncWork() {
    }

    /** hand hands work that sleeps millis, then runs AsyncTail, to Spring the way named. */
    public static void hand(String how, long millis) throws Exception {
        switch (how) {
            case "event":
                EVENTS.multicastEvent(new Done(millis));
                break;
            case "pool":
                POOL.submit((Callable<Object>) () -> {
                    sleepThenTail(millis);
                    return null;
                });
                break;
            case "execute":
                POOL.execute(() -> sleepThenTail(millis));
                break;
            case "listenable":
                POOL.submitListenable(() -> sleepThenTail(millis));
                break;
            case "async":
                ASYNC.work(millis);
                break;
            case "completable":
                ASYNC.later(millis);
                break;
            case "throttled":
                THROTTLED.execute(() -> sleepThenTail(millis));
                break;
            case "scheduled":
                fire(FIRE, millis);
                break;
            case "simplescheduled":
                fire(SIMPLE_FIRE, millis);
                break;
            default:
                throw new IllegalArgumentException(how);
        }
    }

    // fire has the poller that reads next publish the event, and waits until it has.
    private static void fire(AtomicReference<Fire> next, long millis) throws InterruptedException {
        Fire fire = new Fire(millis);
        next.set(fire);
        if (!fire.published.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the poller did not publish");
        }
    }

    private static void poll(AtomicReference<Fire> next) {
        Fire fire = next.getAndSet(null);
        if (fire != null) {
            EVENTS.multicastEvent(new Done(fire.millis));
            fire.published.countDown();
        }
    }

    static void sleepThenTail(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        AsyncTail.ran();
    }

    /** Worker is what the async interceptor's proxy runs on its pool. */
    public interface Worker {
        void work(long millis);

        CompletableFuture<Void> later(long millis);
    }

    private static final class SleepyWorker implements Worker {
        @Override
        public void work(long millis) {
            sleepThenTail(millis);
        }

        @Override
        public CompletableFuture<Void> later(long millis) {
            sleepThenTail(millis);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class Done extends ApplicationEvent {
        private static final long serialVersionUID = 1L;
        private final long millis;

        Done(long millis) {
            super("app");
            this.millis = millis;
        }
    }

    private static final class Listener implements ApplicationListener<Done> {
        @Override
        public void onApplicationEvent(Done event) {
            sleepThenTail(event.millis);
        }
    }

    private static final class Fire {
        private final long millis;
        private final CountDownLatch published = new CountDownLatch(1);

        Fire(long millis) {
            this.millis = millis;
        }
    }
}

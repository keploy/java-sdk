package io.fakeexternal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongConsumer;

/**
 * Simulates third-party external library event loops (such as Netty,
 * {@code java.net.http.HttpClient}, an AWS SDK async client, or a Kafka broker
 * client) living outside the application package ({@code org.example.servers}).
 */
public final class FakeExternalHttpClient {

    private static final LinkedBlockingQueue<Runnable> LOOP = new LinkedBlockingQueue<>();
    private static final LinkedBlockingQueue<Long> BROKER = new LinkedBlockingQueue<>();

    static {
        Thread externalLoop = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    LOOP.take().run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "fake-external-http-loop");
        externalLoop.setDaemon(true);
        externalLoop.start();
    }

    private FakeExternalHttpClient() {
    }

    public static void runAsync(final long delayMs, final Runnable task) {
        LOOP.offer(() -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            task.run();
        });
    }

    public static CompletableFuture<String> fetchAsync(final long delayMs, final String body) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        runAsync(delayMs, () -> future.complete(body));
        return future;
    }

    public static void startBrokerConsumer(final long deliveryDelayMs, final LongConsumer onRecord) {
        Thread container = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Long rec = BROKER.take();
                    if (deliveryDelayMs > 0) {
                        Thread.sleep(deliveryDelayMs);
                    }
                    onRecord.accept(rec.longValue());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }
        }, "fake-kafka-container");
        container.setDaemon(true);
        container.start();
    }

    public static void publishToBroker(final long payloadMs) {
        BROKER.offer(Long.valueOf(payloadMs));
    }
}

package io.keploy.dedup;

import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;

/**
 * A JaCoCo tcpserver: it answers each dump or reset with an empty dump, but
 * a dump holds hit alone when hit is set.
 * dropNext closes that many connections first with no answer, as a JaCoCo
 * that went away does.
 * onDump, when set, runs as each dump is read, before its answer.
 * firstReads keeps, per connection, how many bytes its first read got: a
 * client whose command left in one write sends it all in one.
 */
final class FakeJacoco implements Closeable {

    final ServerSocket server;
    final AtomicInteger dropNext = new AtomicInteger();
    final AtomicInteger served = new AtomicInteger();
    final AtomicInteger accepted = new AtomicInteger();
    // holdMillis holds each answer that long, as a JaCoCo busy with a big dump does.
    volatile long holdMillis;
    // hit, when set, is the class execution each dump returns.
    volatile ExecutionData hit;
    volatile Runnable onDump;
    final List<Integer> firstReads = new CopyOnWriteArrayList<>();

    FakeJacoco() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "fake-jacoco");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                accepted.incrementAndGet();
                socket.setSoTimeout(30000);
                if (dropNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                    continue;
                }
                PushbackInputStream in = new PushbackInputStream(socket.getInputStream(), 64);
                byte[] first = new byte[64];
                int got = in.read(first);
                firstReads.add(got);
                if (got > 0) {
                    in.unread(first, 0, got);
                }
                RemoteControlWriter writer = new RemoteControlWriter(socket.getOutputStream());
                RemoteControlReader reader = new RemoteControlReader(in);
                reader.setRemoteCommandVisitor((dump, reset) -> {
                    hold();
                    Runnable each = onDump;
                    if (dump && each != null) {
                        each.run();
                    }
                    served.incrementAndGet();
                    ExecutionData data = hit;
                    if (dump && data != null) {
                        writer.visitClassExecution(data);
                    }
                    writer.sendCmdOk();
                });
                reader.read();
            } catch (IOException e) {
                // Closed, or the client went away.
            }
        }
    }

    private void hold() {
        try {
            Thread.sleep(holdMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}

package gg.auroramc.aurora.lifecycle;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CommandTreeGateTest {
    @Test void bootstrapDetectionUsesTheExactServerThread() throws Exception {
        var craft = new BootstrapServer(Thread.currentThread());
        assertTrue(CommandTreeGate.isStartupThread(craft));
        var other = Executors.newSingleThreadExecutor();
        try {
            assertFalse(other.submit(() -> CommandTreeGate.isStartupThread(craft)).get(2, TimeUnit.SECONDS));
        } finally { other.shutdownNow(); assertTrue(other.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test void bootstrapDetectionDoesNotAcceptAnotherTickThread() throws Exception {
        assertFalse(CommandTreeGate.isStartupThread(new BootstrapServer(new Thread("fixture-other-tick"))));
    }

    public static final class BootstrapServer {
        private final BootstrapCore core;
        BootstrapServer(Thread thread) { core = new BootstrapCore(thread); }
        public BootstrapCore getServer() { return core; }
    }

    public static final class BootstrapCore {
        private final Thread thread;
        BootstrapCore(Thread thread) { this.thread = thread; }
        public Thread getRunningThread() { return thread; }
    }

    @Test void queuedCommandReadersRunOnlyAfterTheWholeTreeChange() throws Exception {
        var pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            var children = new LinkedHashMap<Integer, Integer>();
            for (int i = 0; i < 40; i++) children.put(i, i);
            var readers = new java.util.ArrayList<Future<Integer>>();
            CommandTreeGate.run(pool, release -> {
                for (int i = 0; i < 50; i++) readers.add(pool.submit(children::hashCode));
                assertTrue(readers.stream().noneMatch(Future::isDone));
                children.clear();
                for (int i = 100; i < 300; i++) children.put(i, i * 3);
                release.run();
            });
            int expected = children.hashCode();
            for (var reader : readers) assertEquals(expected, reader.get(2, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test void failedChangeReleasesCommandBuilders() throws Exception {
        var pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            assertThrows(IllegalStateException.class, () -> CommandTreeGate.run(pool, release -> { throw new IllegalStateException("fixture"); }));
            assertEquals(42, pool.submit(() -> 42).get(2, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); }
    }
}

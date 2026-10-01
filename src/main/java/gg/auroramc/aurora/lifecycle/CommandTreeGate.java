package gg.auroramc.aurora.lifecycle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shiroha 26.2 builds command packets on two workers while command-map writes are global. */
final class CommandTreeGate {
    static ThreadPoolExecutor pool() throws ReflectiveOperationException {
        var type = Class.forName("net.minecraft.commands.Commands");
        var value = RuntimeAccess.get(type, "COMMAND_SENDING_POOL");
        if (!(value instanceof ThreadPoolExecutor pool) || pool.getCorePoolSize() != 2 || pool.getMaximumPoolSize() != 2
                || !(pool.getQueue() instanceof LinkedBlockingQueue<?>)) {
            throw new IllegalStateException("Unsupported command sending executor; command changes were not applied");
        }
        return pool;
    }

    static void run(Plugin schedulerOwner, Runnable change) throws Exception {
        run(pool(), release -> {
            var done = new CompletableFuture<Void>();
            var started = new AtomicBoolean();
            var task = Bukkit.getGlobalRegionScheduler().run(schedulerOwner, ignored -> {
                started.set(true);
                try { change.run(); done.complete(null); }
                catch (Throwable failure) { done.completeExceptionally(failure); }
                finally { release.run(); }
            });
            try { done.get(30, TimeUnit.SECONDS); }
            catch (TimeoutException timeout) {
                if (!started.get()) {
                    task.cancel();
                    if (task.getExecutionState() == io.papermc.paper.threadedregions.scheduler.ScheduledTask.ExecutionState.CANCELLED) release.run();
                }
                throw timeout;
            } catch (InterruptedException stopped) {
                task.cancel();
                if (task.getExecutionState() == io.papermc.paper.threadedregions.scheduler.ScheduledTask.ExecutionState.CANCELLED) release.run();
                else {
                    // An interruption must not release readers while a global mutation is still running.
                    try { done.join(); } catch (CompletionException failure) { stopped.addSuppressed(failure); }
                }
                Thread.currentThread().interrupt();
                throw stopped;
            }
        });
    }

    @FunctionalInterface interface Change { void apply(Runnable release) throws Exception; }

    static void run(ThreadPoolExecutor pool, Change change) throws Exception {
        // All plugin instances synchronize on this core-owned executor, including a replacement instance.
        synchronized (pool) {
            if (pool.isShutdown()) throw new IllegalStateException("Command sending executor is stopping");
            var arrived = new CountDownLatch(pool.getMaximumPoolSize());
            var released = new CountDownLatch(1);
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) pool.execute(() -> {
                arrived.countDown();
                try { released.await(); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            });
            boolean ready;
            try { ready = arrived.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException stopped) { released.countDown(); throw stopped; }
            if (!ready) {
                released.countDown();
                throw new TimeoutException("Command builders did not finish; command changes were not applied");
            }
            try { change.apply(released::countDown); }
            catch (Exception | Error failure) {
                // The global callback owns release after it starts; an acquisition failure never mutates the tree.
                if (!(failure instanceof TimeoutException)) released.countDown();
                throw failure;
            }
        }
    }

    private CommandTreeGate() { }
}

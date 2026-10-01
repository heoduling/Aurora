package gg.auroramc.aurora.expansions.region;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BlockMarkerCleanerQueueTest {
    final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    final ArrayDeque<Runnable> rescanCallbacks = new ArrayDeque<>();
    final Map<Long, Chunk> loaded = new LinkedHashMap<>();
    final Set<Long> visited = new HashSet<>();
    final UUID worldId = UUID.randomUUID();
    final BlockMarkerCleaner cleaner = new BlockMarkerCleaner(null);
    Server previous;
    World world;
    boolean region, worldAvailable = true, stopDuringSubmission;
    int snapshots, peakPending;

    void setup(int count, boolean empty) throws Exception {
        var data = proxy(PersistentDataContainer.class, (p, m, a) -> {
            assertTrue(region, "PDC accessed outside its region");
            return switch (m.getName()) {
                case "isEmpty" -> empty;
                case "getKeys" -> Set.of(new NamespacedKey("other_plugin", "keep"));
                default -> throw new AssertionError("Foreign data must remain unchanged: " + m);
            };
        });
        world = proxy(World.class, (p, m, a) -> switch (m.getName()) {
            case "getUID" -> worldId;
            case "getLoadedChunks" -> {
                assertFalse(region, "Loaded index must be read on the global region");
                snapshots++;
                yield loaded.values().toArray(Chunk[]::new);
            }
            case "isChunkLoaded" -> loaded.containsKey(pack((int) a[0], (int) a[1]));
            case "getChunkAt" -> {
                assertTrue(region);
                long key = pack((int) a[0], (int) a[1]);
                assertTrue(loaded.containsKey(key), "Cleaner must not load an absent chunk");
                visited.add(key);
                yield loaded.get(key);
            }
            default -> throw new AssertionError(m);
        });
        for (int i = 0; i < count; i++) addChunk(i - count / 2, -i, data);
        var global = proxy(GlobalRegionScheduler.class, scheduler(false));
        var regional = proxy(RegionScheduler.class, scheduler(true));
        var async = proxy(AsyncScheduler.class, scheduler(false));
        var server = proxy(Server.class, (p, m, a) -> switch (m.getName()) {
            case "getWorlds" -> worldAvailable ? List.of(world) : List.of();
            case "getWorld" -> worldAvailable && worldId.equals(a[0]) ? world : null;
            case "getGlobalRegionScheduler" -> global;
            case "getRegionScheduler" -> regional;
            case "getAsyncScheduler" -> async;
            case "isOwnedByCurrentRegion" -> region;
            default -> throw new AssertionError(m);
        });
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        previous = (Server) field.get(null);
        field.set(null, server);
    }

    Chunk addChunk(int x, int z, PersistentDataContainer data) {
        var chunk = proxy(Chunk.class, (p, m, a) -> switch (m.getName()) {
            case "getX" -> x;
            case "getZ" -> z;
            case "getWorld" -> world;
            case "getPersistentDataContainer" -> data;
            default -> throw new AssertionError(m);
        });
        loaded.put(pack(x, z), chunk);
        return chunk;
    }

    InvocationHandler scheduler(boolean onRegion) {
        return (p, m, args) -> {
            assertTrue(m.getName().equals("run") || m.getName().equals("runDelayed"));
            if (!onRegion && m.getName().equals("run") && stopDuringSubmission) {
                cleaner.close();
                throw new IllegalPluginAccessException("Plugin disabled before global submission");
            }
            var cancelled = new AtomicBoolean();
            var task = proxy(ScheduledTask.class, (q, method, a) -> {
                if (method.getName().equals("cancel")) {
                    cancelled.set(true);
                    return method.getReturnType().getEnumConstants()[0];
                }
                throw new AssertionError(method);
            });
            for (var argument : args) if (argument instanceof Consumer<?> callback) {
                @SuppressWarnings("unchecked") var action = (Consumer<ScheduledTask>) callback;
                if (!onRegion && m.getName().equals("runDelayed") && args[args.length - 1] == TimeUnit.SECONDS) {
                    assertEquals(30L, args[args.length - 2]);
                    // Run even a cancelled delayed callback to verify the cleaner rejects it itself.
                    rescanCallbacks.add(() -> action.accept(task));
                    return task;
                }
                callbacks.add(() -> {
                    if (cancelled.get()) return;
                    region = onRegion;
                    try { action.accept(task); } finally { region = false; }
                });
                return task;
            }
            throw new AssertionError("No callback");
        };
    }

    void apply(boolean value) throws Exception {
        var method = BlockMarkerCleaner.class.getDeclaredMethod("apply", boolean.class);
        method.setAccessible(true);
        method.invoke(cleaner, value);
    }

    void request(Chunk chunk) {
        region = true;
        try { cleaner.request(chunk); } finally { region = false; }
    }

    void next() {
        assertFalse(callbacks.isEmpty());
        callbacks.removeFirst().run();
        peakPending = Math.max(peakPending, cleaner.status().pendingChunks());
        assertTrue(cleaner.status().pendingChunks() <= 4096, "Queue exceeded its bound");
    }

    void drain() {
        for (int executed = 0; !callbacks.isEmpty(); executed++) {
            assertTrue(executed < 150000, "Callbacks failed to reach idle");
            next();
        }
        assertEquals(0, cleaner.status().pendingChunks());
        assertFalse(cleaner.isScanning());
        assertNull(readField("scan"), "Coordinate snapshot was retained after completion");
    }

    Object readField(String name) {
        try {
            var field = BlockMarkerCleaner.class.getDeclaredField(name);
            field.setAccessible(true);
            var value = field.get(cleaner);
            return value instanceof AtomicReference<?> reference ? reference.get() : value;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @AfterEach
    void restoreServer() throws Exception {
        cleaner.close();
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, previous);
    }

    @Test
    void continuesPastQueueLimitWithoutLoadingChunksOrTouchingForeignData() throws Exception {
        setup(6000, false);
        apply(true);
        drain();
        assertEquals(6000, visited.size());
        assertEquals(6000, cleaner.status().completedChunks());
        assertEquals(0, cleaner.status().queueSkippedChunks());
        assertEquals(1, snapshots);
    }

    @Test
    void emptyChunksAlsoContinuePastQueueLimitAndReleaseTheSnapshot() throws Exception {
        setup(5000, true);
        apply(true);
        drain();
        assertEquals(5000, visited.size());
        assertEquals(0, cleaner.status().completedChunks());
        assertEquals(5000, cleaner.getCheckedChunks());
        assertTrue(cleaner.statusMessage().contains("检查完成 5000 次（含空区块）"));
        assertTrue(cleaner.statusMessage().contains("存储项净减少 0"));
        assertTrue(cleaner.statusMessage().contains("当前队列处理完毕"));
        assertEquals(1, snapshots);
    }

    @Test
    void coalescesOverflowLoadEventsAndRecoversChunksAbsentFromTheFirstSnapshot() throws Exception {
        setup(6000, false);
        apply(true);
        next();
        assertEquals(1, snapshots);
        for (var chunk : loaded.values()) {
            if (cleaner.status().pendingChunks() == 4096) break;
            request(chunk);
        }
        assertEquals(4096, cleaner.status().pendingChunks());
        var data = loaded.values().iterator().next().getPersistentDataContainer();
        request(addChunk(-987654, -123456, data));
        request(addChunk(987654, -123456, data));
        assertEquals(2, cleaner.status().queueSkippedChunks());
        drain();
        assertEquals(6002, visited.size());
        assertEquals(2, snapshots);
    }

    @Test
    void duplicateRequestsDoNotTriggerOverflowOrRepeatedScans() throws Exception {
        setup(5000, false);
        apply(true);
        for (var chunk : loaded.values()) {
            if (cleaner.status().pendingChunks() == 4096) break;
            request(chunk);
        }
        var first = loaded.values().iterator().next();
        for (int i = 0; i < 100; i++) request(first);
        assertEquals(0, cleaner.status().queueSkippedChunks());
        drain();
        assertEquals(5000, visited.size());
        assertEquals(1, snapshots);
    }

    @Test
    void offReleasesQueuedTasksAndScanThenOnCompletesAnew() throws Exception {
        setup(6000, false);
        apply(true);
        next();
        assertNotNull(readField("scan"));
        apply(false);
        assertFalse(cleaner.isScanning());
        assertNull(readField("scan"));
        drain();
        assertTrue(visited.isEmpty());
        apply(true);
        drain();
        assertEquals(6000, visited.size());
    }

    @Test
    void closeStopsDeferredCallbacksAndDropsScanState() throws Exception {
        setup(6000, false);
        apply(true);
        next();
        cleaner.close();
        request(loaded.values().iterator().next());
        drain();
        assertFalse(cleaner.isEnabled());
        assertTrue(visited.isEmpty());
    }

    @Test
    void disappearingWorldDoesNotGetLoadedByTheRemainingScan() throws Exception {
        setup(5000, false);
        apply(true);
        next();
        worldAvailable = false;
        drain();
        assertTrue(visited.isEmpty());
        assertEquals(1, snapshots);
    }

    @Test
    void unloadDefersWorkWithoutLoadingTheChunkAndReloadResumesIt() throws Exception {
        setup(1, false);
        var chunk = loaded.values().iterator().next();
        apply(true);
        next();
        loaded.clear();
        region = true;
        try { cleaner.onUnload(new ChunkUnloadEvent(chunk, true)); }
        finally { region = false; }
        drain();
        assertTrue(visited.isEmpty(), "An unloaded chunk must not be loaded for cleanup");
        assertTrue(cleaner.statusMessage().contains("区块卸载暂缓 1 次（下次加载继续）"));
        loaded.put(pack(chunk.getX(), chunk.getZ()), chunk);
        request(chunk);
        drain();
        assertEquals(1, visited.size());
        assertEquals(1, cleaner.getCheckedChunks());
    }

    @Test
    void repeatsLoadedChunkChecksAfterEachIdleDelay() throws Exception {
        setup(3, false);
        apply(true);
        drain();
        assertEquals(3, cleaner.getCheckedChunks());
        assertEquals(1, rescanCallbacks.size());
        rescanCallbacks.removeFirst().run();
        drain();
        assertEquals(6, cleaner.getCheckedChunks());
        assertEquals(2, snapshots);
        assertEquals(1, rescanCallbacks.size());
        rescanCallbacks.removeFirst().run();
        drain();
        assertEquals(9, cleaner.getCheckedChunks());
        assertEquals(3, snapshots);
    }

    @Test
    void offAndCloseCancelIdleRescanIncludingLateCallbacks() throws Exception {
        setup(2, false);
        apply(true);
        drain();
        assertNotNull(readField("rescanTask"));
        apply(false);
        assertNull(readField("rescanTask"));
        rescanCallbacks.removeFirst().run();
        drain();
        assertEquals(1, snapshots);
        apply(true);
        drain();
        cleaner.close();
        assertNull(readField("rescanTask"));
        rescanCallbacks.removeFirst().run();
        drain();
        assertEquals(2, snapshots);
    }

    @Test
    void lateCancelledTimerCannotClearTheReplacementTimer() throws Exception {
        setup(1, false);
        apply(true);
        drain();
        var oldCallback = rescanCallbacks.removeFirst();
        apply(false);
        apply(true);
        drain();
        var replacement = readField("rescanTask");
        oldCallback.run();
        assertSame(replacement, readField("rescanTask"));
        drain();
        assertEquals(2, snapshots);
        rescanCallbacks.removeFirst().run();
        drain();
        assertEquals(3, snapshots);
    }

    @Test
    void closingDuringTimerWakeRejectsSubmissionWithoutLeavingTheWakeGateSet() throws Exception {
        setup(1, false);
        apply(true);
        drain();
        stopDuringSubmission = true;
        assertDoesNotThrow(() -> rescanCallbacks.removeFirst().run());
        drain();
        assertFalse(cleaner.isEnabled());
        assertNull(readField("rescanTask"));
        assertFalse(((AtomicBoolean) readField("wakePending")).get());
        assertEquals(1, snapshots);
    }

    @Test
    void unloadedActiveChunkDoesNotBlockOtherLoadedChunks() throws Exception {
        setup(4, false);
        apply(true);
        next();
        Object job = readField("active");
        var idField = job.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        Object id = idField.get(job);
        var xMethod = id.getClass().getDeclaredMethod("x"); xMethod.setAccessible(true);
        var zMethod = id.getClass().getDeclaredMethod("z"); zMethod.setAccessible(true);
        long key = pack((int) xMethod.invoke(id), (int) zMethod.invoke(id));
        var chunk = loaded.remove(key);
        region = true;
        try { cleaner.onUnload(new ChunkUnloadEvent(chunk, true)); }
        finally { region = false; }
        drain();
        assertEquals(3, visited.size());
        assertFalse(visited.contains(key));
        loaded.put(key, chunk);
        request(chunk);
        drain();
        assertEquals(4, visited.size());
    }

    static long pack(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) return switch (m.getName()) {
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                case "toString" -> "queue-test-" + type.getSimpleName();
                default -> throw new AssertionError(m);
            };
            return handler.invoke(p, m, a);
        }));
    }
}

package gg.auroramc.aurora.expansions.region;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BlockMarkerCleanerQueueTest {
    final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    final Map<Long, Chunk> loaded = new LinkedHashMap<>();
    final Set<Long> visited = new HashSet<>();
    final UUID worldId = UUID.randomUUID();
    final BlockMarkerCleaner cleaner = new BlockMarkerCleaner(null);
    Server previous;
    World world;
    boolean region, worldAvailable = true;
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
        var server = proxy(Server.class, (p, m, a) -> switch (m.getName()) {
            case "getWorlds" -> worldAvailable ? List.of(world) : List.of();
            case "getWorld" -> worldAvailable && worldId.equals(a[0]) ? world : null;
            case "getGlobalRegionScheduler" -> global;
            case "getRegionScheduler" -> regional;
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
            return field.get(cleaner);
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

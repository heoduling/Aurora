package gg.auroramc.aurora.expansions.region;

import org.bukkit.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BlockMarkerCleanerTest {
    record Value(PersistentDataType<?, ?> type, Object value) {}
    final Map<NamespacedKey, Value> values = new HashMap<>();
    final RegionExpansion region = new RegionExpansion();
    PersistentDataContainer data;
    World world;
    Consumer<NamespacedKey> beforeRemoval = key -> {};

    @BeforeEach
    void setup() {
        values.clear();
        data = proxy(PersistentDataContainer.class, (p, m, a) -> switch (m.getName()) {
            case "set" -> { values.put((NamespacedKey) a[0], new Value((PersistentDataType<?, ?>) a[1], a[2])); yield null; }
            case "remove" -> { beforeRemoval.accept((NamespacedKey) a[0]); values.remove(a[0]); yield null; }
            case "get" -> {
                var value = values.get(a[0]);
                yield value != null && value.type() == a[1] ? value.value() : null;
            }
            case "has" -> {
                var value = values.get(a[0]);
                yield value != null && (a.length == 1 || value.type() == a[1]);
            }
            case "getKeys" -> Set.copyOf(values.keySet());
            case "isEmpty" -> values.isEmpty();
            default -> throw new AssertionError(m);
        });
        world = proxy(World.class, (p, m, a) -> switch (m.getName()) {
            case "hashCode" -> 123456789;
            case "equals" -> p == a[0];
            case "toString", "getName" -> "cleaner-unit-world";
            case "getChunkAt" -> proxy(Chunk.class, (c, method, args) -> {
                if (method.getName().equals("getPersistentDataContainer")) return data;
                throw new AssertionError(method);
            });
            default -> throw new AssertionError("Cleaner must not scan blocks or load worlds: " + m);
        });
    }

    @Test
    void preservesCollisionRawLocationAndFalseOverrides() {
        var a = new Location(world, 0, 64, 2);
        var b = new Location(world, 0, 60, 11);
        var raw = new Location(world, .25, 64.25, .25, 13.3f, -28.9f);
        assertEquals(a.hashCode(), b.hashCode());
        addBucket(a.hashCode(), 4);
        addBucket(raw.hashCode(), 4);
        for (int y = 64; y < 72; y++) data.set(new NamespacedKey("aurora", "placed_v2_0_" + y + "_2"), PersistentDataType.BYTE, (byte) 0);
        var checks = List.of(a, b, raw, new Location(world, .75, 64.9, 2.75, 17f, 42f), new Location(world, 7, -64, 7));
        var expected = checks.stream().map(region::isPlacedBlock).toList();
        assertEquals(List.of(false, true, true, false, false), expected);
        var work = complete();
        assertTrue(work.legacy >= 4);
        assertEquals(8, work.clears);
        assertFalse(data.has(old(a.hashCode())));
        assertFalse(data.has(old(raw.hashCode())));
        assertEquals(expected, checks.stream().map(region::isPlacedBlock).toList());
        region.addPlacedBlock(a);
        assertTrue(region.isPlacedBlock(a));
        region.removePlacedBlock(a);
        assertFalse(region.isPlacedBlock(a));
        assertTrue(region.isPlacedBlock(b));
    }

    @Test
    void publishesCompatibilityBeforeRemovingAnySource() {
        addBucket(123456, 8);
        beforeRemoval = key -> {
            Integer hash = CompactBlockMarkers.legacyHash(key);
            if (hash != null) assertTrue(CompactBlockMarkers.hasLegacy(data, hash), "source removed before compatibility membership");
        };
        assertEquals(8, complete().legacy);
    }

    @Test
    void deletesOnlyRedundantFalseFlagsWhenNoLegacyDependencyExists() {
        var falseKey = new NamespacedKey("aurora", "placed_v2_1_-64_1");
        var trueKey = new NamespacedKey("aurora", "placed_v2_2_90_2");
        var unrelated = new NamespacedKey("other_plugin", "keep");
        data.set(falseKey, PersistentDataType.BYTE, (byte) 0);
        data.set(trueKey, PersistentDataType.BYTE, (byte) 1);
        data.set(CompactBlockMarkers.CLEARED[17], PersistentDataType.LONG_ARRAY, new long[]{-16367});
        data.set(unrelated, PersistentDataType.STRING, "unchanged");
        assertFalse(region.isPlacedBlock(new Location(world, 1, -64, 1)));
        var work = complete();
        assertEquals(2, work.deleted);
        assertFalse(data.has(falseKey));
        assertFalse(data.has(CompactBlockMarkers.CLEARED[17]));
        assertTrue(region.isPlacedBlock(new Location(world, 2, 90, 2)));
        assertEquals("unchanged", data.get(unrelated, PersistentDataType.STRING));
    }

    @Test
    void retainsSparseGroupsUnknownValuesAndForeignData() {
        var hash = new Location(world, 0, 60, 11).hashCode();
        data.set(old(hash), PersistentDataType.BYTE, (byte) 0); // Legacy lookup is presence-based, even for an unknown value.
        data.set(old(42), PersistentDataType.STRING, "unknown-format");
        var clear = new NamespacedKey("aurora", "placed_v2_0_60_11");
        data.set(clear, PersistentDataType.BYTE, (byte) 0);
        data.set(old(123456), PersistentDataType.BYTE, (byte) 1);
        var foreign = new NamespacedKey("foreign", "keep");
        data.set(foreign, PersistentDataType.STRING, "keep");
        complete();
        assertEquals(5, values.size());
        assertFalse(region.isPlacedBlock(new Location(world, 0, 60, 11)));
        assertEquals("unknown-format", data.get(old(42), PersistentDataType.STRING));
        assertEquals("keep", data.get(foreign, PersistentDataType.STRING));
    }

    @Test
    void placementAfterCollectionIsNotOverwritten() {
        var location = new Location(world, 0, 64, 2);
        addBucket(location.hashCode(), 4);
        for (int y = 64; y < 72; y++) data.set(new NamespacedKey("aurora", "placed_v2_0_" + y + "_2"), PersistentDataType.BYTE, (byte) 0);
        var work = new BlockMarkerCleaner.Work(data.getKeys());
        while (work.committing == null) assertFalse(work.step(data));
        region.addPlacedBlock(location);
        drain(work);
        assertTrue(region.isPlacedBlock(location));
        region.removePlacedBlock(location);
        assertFalse(region.isPlacedBlock(location));
    }

    @Test
    void interruptedCompactionCanRestartWithoutLosingMembership() {
        for (int hash = 0; hash < 10000; hash++) data.set(old(hash), PersistentDataType.BYTE, (byte) 1);
        var first = new BlockMarkerCleaner.Work(data.getKeys());
        int steps = 0;
        while (first.legacy == 0 && steps++ < 10000) assertFalse(first.step(data));
        assertTrue(first.legacy > 0);
        assertTrue(first.legacy < 10000);
        assertAllHashesPresent(10000);
        complete();
        assertAllHashesPresent(10000);
        assertEquals(256, values.size());
        for (var value : values.values()) assertTrue(((int[]) value.value()).length <= CompactBlockMarkers.MAX_VALUES);
        var after = Map.copyOf(values);
        var second = complete();
        assertEquals(0, second.legacy);
        assertEquals(after, values);
    }

    @Test
    void archiveCapacityKeepsOverflowSourceKeys() {
        addBucket(12345, 200);
        var hashes = data.getKeys().stream().map(CompactBlockMarkers::legacyHash).toList();
        var work = complete();
        assertEquals(128, work.legacy);
        assertEquals(72, work.skipped);
        assertEquals(73, values.size());
        for (int hash : hashes) assertTrue(data.has(old(hash)) || CompactBlockMarkers.hasLegacy(data, hash));
    }

    @Test
    void malformedArchiveNeverCausesSourceRemoval() {
        int hash = 12345;
        addBucket(hash, 4);
        var target = CompactBlockMarkers.LEGACY[CompactBlockMarkers.bucket(hash)];
        data.set(target, PersistentDataType.STRING, "foreign-or-corrupt");
        assertEquals(0, complete().legacy);
        assertEquals(5, values.size());
        assertEquals("foreign-or-corrupt", data.get(target, PersistentDataType.STRING));
    }

    @Test
    void signedYAndNegativeChunkOverridesRemainIndependent() {
        addBucket(12345, 4);
        int[] heights = {-2048, -65, -64, -1, 0, 2047, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int y : heights) data.set(new NamespacedKey("aurora", "placed_v2_15_" + y + "_15"), PersistentDataType.BYTE, (byte) 0);
        assertEquals(8, complete().clears);
        for (int y : heights) {
            var location = new Location(world, -1.25, y, -1.25, 11f, 27f);
            // -1.25 floors to local coordinate 14, so verify the intended local coordinate 15 separately.
            var target = new Location(world, -.25, y, -.25, 11f, 27f);
            assertFalse(region.isPlacedBlock(target));
            region.addPlacedBlock(target);
            assertTrue(region.isPlacedBlock(target));
            region.removePlacedBlock(target);
            assertFalse(region.isPlacedBlock(target));
            assertFalse(region.isPlacedBlock(location));
        }
    }

    void assertAllHashesPresent(int count) {
        for (int hash = 0; hash < count; hash++) assertTrue(data.has(old(hash)) || CompactBlockMarkers.hasLegacy(data, hash), "lost hash " + hash);
    }

    void addBucket(int first, int count) {
        int bucket = CompactBlockMarkers.bucket(first), found = 1;
        data.set(old(first), PersistentDataType.BYTE, (byte) 1);
        for (int hash = 0; found < count; hash++) {
            if (hash == first || CompactBlockMarkers.bucket(hash) != bucket || data.has(old(hash))) continue;
            data.set(old(hash), PersistentDataType.BYTE, (byte) 1); found++;
        }
    }

    BlockMarkerCleaner.Work complete() {
        var work = new BlockMarkerCleaner.Work(data.getKeys());
        drain(work);
        return work;
    }

    void drain(BlockMarkerCleaner.Work work) {
        for (int i = 0; i < 100000; i++) if (work.step(data)) return;
        fail("Cleaner work failed to terminate");
    }

    static NamespacedKey old(int hash) { return new NamespacedKey("aurora", Integer.toHexString(hash)); }
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

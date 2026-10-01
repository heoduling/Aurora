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
    void compactsCollectedLegacyKeysBeforeFinishingTheChunkScan() {
        addBucket(12345, 4);
        var ordered = new LinkedHashSet<>(values.keySet());
        for (int i = 0; i < 4096; i++) {
            var key = new NamespacedKey("foreign", "untouched_" + i);
            data.set(key, PersistentDataType.STRING, "keep");
            ordered.add(key);
        }
        beforeRemoval = key -> {
            Integer hash = CompactBlockMarkers.legacyHash(key);
            if (hash != null) assertTrue(CompactBlockMarkers.hasLegacy(data, hash));
        };
        var work = new BlockMarkerCleaner.Work(ordered);
        for (int i = 0; work.legacy == 0 && i < 8; i++) assertFalse(work.step(data));
        assertEquals(4, work.legacy, "Collected records must be compacted while the remaining keys are still being scanned");
        assertTrue(work.keys.hasNext(), "Cleaner waited for the full chunk scan before committing");
        assertEquals("keep", data.get(new NamespacedKey("foreign", "untouched_4095"), PersistentDataType.STRING));
        drain(work);
    }

    @Test
    void removesArchivedLegacySourceBeforeCheckingTheNextKey() {
        int hash = 12345;
        var source = old(hash);
        data.set(CompactBlockMarkers.LEGACY[CompactBlockMarkers.bucket(hash)], PersistentDataType.INTEGER_ARRAY,
                new int[]{hash, hash + 1, hash + 2, hash + 3});
        data.set(source, PersistentDataType.BYTE, (byte) 1);
        var ordered = new LinkedHashSet<NamespacedKey>();
        ordered.add(source);
        for (int i = 0; i < 4096; i++) {
            var key = new NamespacedKey("foreign", "keep_immediate_" + i);
            data.set(key, PersistentDataType.STRING, "keep");
            ordered.add(key);
        }
        var work = new BlockMarkerCleaner.Work(checkRemovalBeforeNextKey(ordered, source));
        assertFalse(work.step(data, Long.MAX_VALUE));
        assertEquals(1, work.legacy);
        assertTrue(work.keys.hasNext());
        assertTrue(CompactBlockMarkers.hasLegacy(data, hash));
        drain(work);
    }

    @Test
    void removesArchivedFalseSourceBeforeCheckingTheNextKeyAndLateLegacy() {
        var source = new NamespacedKey("aurora", "placed_v2_0_64_2");
        data.set(CompactBlockMarkers.CLEARED[2], PersistentDataType.LONG_ARRAY,
                new long[]{16386, 16642, 16898, 17154});
        data.set(source, PersistentDataType.BYTE, (byte) 0);
        var ordered = new LinkedHashSet<NamespacedKey>();
        ordered.add(source);
        for (int i = 0; i < 4096; i++) {
            var key = new NamespacedKey("foreign", "keep_immediate_" + i);
            data.set(key, PersistentDataType.STRING, "keep");
            ordered.add(key);
        }
        var unknownLegacy = old(new Location(world, 0, 64, 2).hashCode());
        data.set(unknownLegacy, PersistentDataType.STRING, "keep");
        ordered.add(unknownLegacy);
        var work = new BlockMarkerCleaner.Work(checkRemovalBeforeNextKey(ordered, source));
        assertFalse(work.step(data, Long.MAX_VALUE));
        assertEquals(1, work.clears);
        assertEquals(0, work.deleted);
        assertTrue(work.keys.hasNext());
        assertFalse(region.isPlacedBlock(new Location(world, 0, 64, 2)));
        drain(work);
        assertEquals("keep", data.get(unknownLegacy, PersistentDataType.STRING));
        assertFalse(region.isPlacedBlock(new Location(world, 0, 64, 2)));
    }

    Set<NamespacedKey> checkRemovalBeforeNextKey(Set<NamespacedKey> ordered, NamespacedKey source) {
        return checkRemovalBeforeNextKey(ordered, source, 1);
    }

    Set<NamespacedKey> checkRemovalBeforeNextKey(Set<NamespacedKey> ordered, NamespacedKey source, int minimumCheckedKeys) {
        return new AbstractSet<>() {
            @Override public int size() { return ordered.size(); }
            @Override public Iterator<NamespacedKey> iterator() {
                var iterator = ordered.iterator();
                return new Iterator<>() {
                    int checked;
                    @Override public boolean hasNext() { return iterator.hasNext(); }
                    @Override public NamespacedKey next() {
                        if (checked++ >= minimumCheckedKeys) assertFalse(data.has(source), "Eligible source must be removed before checking the next key");
                        return iterator.next();
                    }
                };
            }
        };
    }

    @Test
    void newArchiveIsCommittedOnTheFourthSourceBeforeCheckingTheFifthKey() {
        addBucket(12345, 4);
        var sources = new LinkedHashSet<>(values.keySet());
        var ordered = new LinkedHashSet<>(sources);
        for (int i = 0; i < 4096; i++) {
            var key = new NamespacedKey("foreign", "keep_immediate_" + i);
            data.set(key, PersistentDataType.STRING, "keep");
            ordered.add(key);
        }
        beforeRemoval = key -> {
            Integer hash = CompactBlockMarkers.legacyHash(key);
            if (hash != null) assertTrue(CompactBlockMarkers.hasLegacy(data, hash));
        };
        var work = new BlockMarkerCleaner.Work(checkRemovalBeforeNextKey(ordered, sources.iterator().next(), 4));
        assertFalse(work.step(data, Long.MAX_VALUE));
        assertEquals(4, work.legacy);
        for (var key : sources) assertFalse(data.has(key));
        assertTrue(work.keys.hasNext());
        drain(work);
    }

    @Test
    void earlyFalseArchiveIsRemovedOnceNoLegacyDependencyIsProven() {
        var ordered = new LinkedHashSet<NamespacedKey>();
        for (int y = 64; y < 68; y++) {
            var key = new NamespacedKey("aurora", "placed_v2_0_" + y + "_2");
            data.set(key, PersistentDataType.BYTE, (byte) 0);
            ordered.add(key);
        }
        for (int i = 0; i < 4096; i++) {
            var key = new NamespacedKey("foreign", "keep_immediate_" + i);
            data.set(key, PersistentDataType.STRING, "keep");
            ordered.add(key);
        }
        int before = values.size();
        var work = new BlockMarkerCleaner.Work(ordered);
        assertFalse(work.step(data, Long.MAX_VALUE));
        assertEquals(4, work.clears);
        assertEquals(0, work.deleted);
        assertEquals(1, work.createdArchives);
        assertTrue(data.has(CompactBlockMarkers.CLEARED[2]));
        assertTrue(work.keys.hasNext());
        drain(work);
        assertFalse(data.has(CompactBlockMarkers.CLEARED[2]));
        assertEquals(1, work.deleted);
        assertEquals(4, before - values.size());
        assertEquals(before - values.size(), work.legacy + work.clears + work.deleted - work.createdArchives);
        assertEquals("keep", data.get(new NamespacedKey("foreign", "keep_immediate_4095"), PersistentDataType.STRING));
    }

    @Test
    void falseOverridesAreRemovedEarlyWhileLateLegacyDependenciesStayProtected() {
        var ordered = new LinkedHashSet<NamespacedKey>();
        for (int y = 64; y < 72; y++) {
            var key = new NamespacedKey("aurora", "placed_v2_0_" + y + "_2");
            data.set(key, PersistentDataType.BYTE, (byte) 0);
            ordered.add(key);
        }
        for (int i = 0; i < 256; i++) {
            var key = new NamespacedKey("foreign", "keep_" + i);
            data.set(key, PersistentDataType.STRING, "keep");
            ordered.add(key);
        }
        var unknownLegacy = old(new Location(world, 0, 64, 2).hashCode());
        data.set(unknownLegacy, PersistentDataType.STRING, "keep-presence-based-legacy");
        ordered.add(unknownLegacy);
        var work = new BlockMarkerCleaner.Work(ordered);
        assertFalse(work.step(data, Long.MAX_VALUE));
        assertEquals(0, work.deleted);
        assertEquals(8, work.clears);
        assertTrue(work.keys.hasNext());
        for (int y = 64; y < 72; y++) assertFalse(data.has(new NamespacedKey("aurora", "placed_v2_0_" + y + "_2")));
        assertFalse(region.isPlacedBlock(new Location(world, 0, 64, 2)));
        drain(work);
        assertEquals(0, work.deleted);
        assertEquals(8, work.clears);
        assertEquals(1, work.createdArchives);
        assertEquals("keep-presence-based-legacy", data.get(unknownLegacy, PersistentDataType.STRING));
        assertFalse(region.isPlacedBlock(new Location(world, 0, 64, 2)));
    }

    @Test
    void countsNewArchiveKeysSoNetReductionMatchesStoredKeys() {
        addBucket(12345, 8);
        int before = values.size();
        var work = complete();
        assertEquals(1, work.createdArchives);
        assertEquals(before - values.size(), work.legacy + work.clears + work.deleted - work.createdArchives);
        var repeat = complete();
        assertEquals(0, repeat.createdArchives);
        assertEquals(0, repeat.legacy + repeat.clears + repeat.deleted);
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
        assertEquals(1, work.clears); // The source was already covered by the existing archive during discovery.
        assertEquals(1, work.deleted); // After the full dependency check, the redundant archive is removed.
        assertEquals(2, work.clears + work.deleted - work.createdArchives);
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

    @Test
    void deletesAirPlacementBeforeExaminingTheNextKey() {
        var source = new NamespacedKey("aurora", "placed_v2_15_-64_2");
        data.set(source, PersistentDataType.BYTE, (byte) 1);
        var ordered = new LinkedHashSet<NamespacedKey>();
        ordered.add(source);
        for (int i = 0; i < 4096; i++) {
            var key = new NamespacedKey("foreign", "keep_air_" + i);
            data.set(key, PersistentDataType.STRING, "keep");
            ordered.add(key);
        }
        assertFalse(BlockMarkerCleaner.Work.hasLegacySources(ordered));
        var work = new BlockMarkerCleaner.Work(checkRemovalBeforeNextKey(ordered, source), false);
        assertFalse(work.step(data, Long.MAX_VALUE, coordinate -> {
            assertEquals(-64, (int) (coordinate >> 8));
            assertEquals(15, (int) (coordinate >> 4) & 15);
            assertEquals(2, (int) coordinate & 15);
            return true;
        }));
        assertEquals(1, work.expiredDeleted);
        assertEquals(1, work.deleted);
        assertTrue(work.collecting);
        assertFalse(region.isPlacedBlock(new Location(world, 15, -64, 2)));
        drain(work);
        assertEquals(4096, values.size());
    }

    @Test
    void deletesRedundantFalseFlagAndArchiveDuringDiscoveryWithoutLegacy() {
        var source = new NamespacedKey("aurora", "placed_v2_1_64_1");
        data.set(source, PersistentDataType.BYTE, (byte) 0);
        data.set(CompactBlockMarkers.CLEARED[17], PersistentDataType.LONG_ARRAY, new long[]{16401});
        var ordered = new LinkedHashSet<>(List.of(source, CompactBlockMarkers.CLEARED[17], old(42)));
        // The last key is absent: inventory is based on the actual PDC snapshot, not arbitrary names.
        var work = new BlockMarkerCleaner.Work(checkRemovalBeforeNextKey(ordered, source), false);
        work.step(data, Long.MAX_VALUE, coordinate -> false);
        assertFalse(data.has(source));
        assertFalse(data.has(CompactBlockMarkers.CLEARED[17]));
        assertEquals(2, work.deleted);
        assertEquals(0, work.expiredDeleted);
    }

    @Test
    void keepsNonAirPlacementsAndUnrecognizedRecords() {
        var live = new NamespacedKey("aurora", "placed_v2_1_64_1");
        var wrongType = new NamespacedKey("aurora", "placed_v2_2_64_2");
        var invalid = new NamespacedKey("aurora", "placed_v2_16_64_0");
        var foreign = new NamespacedKey("foreign", "placed_v2_3_64_3");
        data.set(live, PersistentDataType.BYTE, (byte) 1);
        data.set(wrongType, PersistentDataType.STRING, "keep");
        data.set(invalid, PersistentDataType.BYTE, (byte) 1);
        data.set(foreign, PersistentDataType.BYTE, (byte) 1);
        var before = Map.copyOf(values);
        var work = new BlockMarkerCleaner.Work(data.getKeys(), false);
        for (int i = 0; i < 10; i++) if (work.step(data, Long.MAX_VALUE, coordinate -> false)) break;
        assertEquals(before, values);
        assertEquals(0, work.expiredDeleted);
    }

    @Test
    void retainsShieldForLateLegacyCollisionAndRawLocationHash() {
        var air = new Location(world, 0, 64, 2);
        var collision = new Location(world, 0, 60, 11);
        var raw = new Location(world, .25, 64.25, 2.25, 13.3f, -28.9f);
        assertEquals(air.hashCode(), collision.hashCode());
        var source = new NamespacedKey("aurora", "placed_v2_0_64_2");
        data.set(source, PersistentDataType.BYTE, (byte) 1);
        data.set(old(air.hashCode()), PersistentDataType.BYTE, (byte) 1);
        data.set(old(raw.hashCode()), PersistentDataType.STRING, "presence-is-legacy");
        var ordered = new LinkedHashSet<>(List.of(source, old(air.hashCode()), old(raw.hashCode())));
        var work = new BlockMarkerCleaner.Work(ordered, BlockMarkerCleaner.Work.hasLegacySources(ordered));
        work.step(data, Long.MAX_VALUE, coordinate -> true);
        assertEquals(1, work.legacyShields);
        assertEquals(0, work.expiredDeleted);
        assertEquals((byte) 0, data.get(source, PersistentDataType.BYTE));
        drain(work);
        assertFalse(region.isPlacedBlock(air));
        assertFalse(region.isPlacedBlock(raw));
        assertTrue(region.isPlacedBlock(collision));
        assertTrue(data.has(old(raw.hashCode()), PersistentDataType.STRING));
    }

    @Test
    void deletesAirOverrideAlreadyProtectedByCompatibilityArchive() {
        var air = new Location(world, 0, 64, 2);
        var source = new NamespacedKey("aurora", "placed_v2_0_64_2");
        data.set(source, PersistentDataType.BYTE, (byte) 1);
        data.set(CompactBlockMarkers.LEGACY[CompactBlockMarkers.bucket(air.hashCode())], PersistentDataType.INTEGER_ARRAY,
                new int[]{air.hashCode()});
        data.set(CompactBlockMarkers.CLEARED[2], PersistentDataType.LONG_ARRAY, new long[]{CompactBlockMarkers.coordinate(air)});
        var work = new BlockMarkerCleaner.Work(data.getKeys(), true);
        work.step(data, Long.MAX_VALUE, coordinate -> true);
        drain(work);
        assertEquals(1, work.expiredDeleted);
        assertEquals(0, work.legacyShields);
        assertFalse(data.has(source));
        assertTrue(CompactBlockMarkers.isCleared(data, air));
        assertFalse(region.isPlacedBlock(air));
    }

    @Test
    void removesEmptyTypedArchivesButRetainsMalformedLegacyArchive() {
        data.set(CompactBlockMarkers.LEGACY[0], PersistentDataType.INTEGER_ARRAY, new int[0]);
        data.set(CompactBlockMarkers.CLEARED[0], PersistentDataType.LONG_ARRAY, new long[0]);
        data.set(CompactBlockMarkers.LEGACY[1], PersistentDataType.STRING, "unknown");
        data.set(CompactBlockMarkers.LEGACY[2], PersistentDataType.INTEGER_ARRAY, new int[]{2, 1});
        var work = complete();
        assertEquals(2, work.emptyArchives);
        assertEquals(2, work.deleted);
        assertEquals(2, values.size());
        assertEquals("unknown", data.get(CompactBlockMarkers.LEGACY[1], PersistentDataType.STRING));
        assertArrayEquals(new int[]{2, 1}, data.get(CompactBlockMarkers.LEGACY[2], PersistentDataType.INTEGER_ARRAY));
    }

    @Test
    void rechecksSourceValueAfterAirInspection() {
        var source = new NamespacedKey("aurora", "placed_v2_1_64_1");
        data.set(source, PersistentDataType.BYTE, (byte) 1);
        var work = new BlockMarkerCleaner.Work(data.getKeys(), false);
        work.step(data, Long.MAX_VALUE, coordinate -> {
            data.set(source, PersistentDataType.STRING, "changed-since-snapshot");
            return true;
        });
        drain(work);
        assertEquals(0, work.expiredDeleted);
        assertEquals("changed-since-snapshot", data.get(source, PersistentDataType.STRING));
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

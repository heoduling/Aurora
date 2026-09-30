package gg.auroramc.aurora.expansions.region;

import gg.auroramc.aurora.api.events.region.RegionBlockBreakEvent;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RegionBlockTest {
    private static final Deque<Consumer<ScheduledTask>> scheduled = new ArrayDeque<>();
    private static final List<Event> emitted = new ArrayList<>();
    private final RegionExpansion expansion = new RegionExpansion();
    private final RegionBlockListener listener = new RegionBlockListener(null, expansion);
    private final Map<String, Cell> cells = new HashMap<>();
    private final Map<String, PersistentDataContainer> chunks = new HashMap<>();
    private World world;

    @BeforeAll
    static void server() throws Exception {
        var scheduler = proxy(RegionScheduler.class, (p, m, a) -> {
            if (!m.getName().equals("run")) throw new AssertionError("Unexpected scheduler: " + m);
            for (var arg : a) {
                if (arg instanceof Consumer<?> callback) {
                    @SuppressWarnings("unchecked") var task = (Consumer<ScheduledTask>) callback;
                    scheduled.add(task);
                }
            }
            return null;
        });
        var manager = proxy(PluginManager.class, (p, m, a) -> {
            if (m.getName().equals("callEvent")) { emitted.add((Event) a[0]); return null; }
            throw new AssertionError(m);
        });
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, proxy(Server.class, (p, m, a) -> switch (m.getName()) {
            case "getLogger" -> Logger.getLogger("RegionBlockTest");
            case "getName" -> "Folia";
            case "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "26.2";
            case "getRegionScheduler" -> scheduler;
            case "getPluginManager" -> manager;
            case "isPrimaryThread" -> true;
            default -> throw new AssertionError(m);
        }));
    }

    @BeforeEach
    void world() {
        scheduled.clear();
        emitted.clear();
        world = proxy(World.class, (p, m, a) -> switch (m.getName()) {
            case "hashCode" -> 123456789;
            case "equals" -> p == a[0];
            case "toString", "getName" -> "marker-test";
            case "getChunkAt" -> {
                int x = a[0] instanceof Location l ? l.getBlockX() >> 4 : (int) a[0];
                int z = a[0] instanceof Location l ? l.getBlockZ() >> 4 : (int) a[1];
                var data = chunks.computeIfAbsent(x + ":" + z, k -> data(new HashMap<>()));
                yield proxy(Chunk.class, (c, method, args) -> switch (method.getName()) {
                    case "getPersistentDataContainer" -> data;
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getZ" -> z;
                    default -> throw new AssertionError(method);
                });
            }
            case "getBlockAt" -> a[0] instanceof Location l
                    ? cell(l.getBlockX(), l.getBlockY(), l.getBlockZ()).block
                    : cell((int) a[0], (int) a[1], (int) a[2]).block;
            default -> throw new AssertionError(m);
        });
    }

    @Test
    void collidingLegacyCoordinatesAreIndependentForNewWrites() {
        var a = cell(0, 64, 2).block;
        var b = cell(0, 60, 11).block;
        assertEquals(a.getLocation().hashCode(), b.getLocation().hashCode());
        expansion.addPlacedBlock(a);
        assertTrue(expansion.isPlacedBlock(a));
        assertFalse(expansion.isPlacedBlock(b));
        expansion.addPlacedBlock(b);
        expansion.removePlacedBlock(b);
        assertTrue(expansion.isPlacedBlock(a));
        assertFalse(expansion.isPlacedBlock(b));
    }

    @Test
    void clearingLegacyCollisionKeepsOtherPositionAndForeignData() {
        var a = cell(0, 64, 2).block;
        var b = cell(0, 60, 11).block;
        var data = a.getChunk().getPersistentDataContainer();
        var legacy = new NamespacedKey("aurora", Integer.toHexString(a.getLocation().hashCode()));
        var foreign = new NamespacedKey("other_plugin", "data");
        data.set(legacy, PersistentDataType.BYTE, (byte) 1);
        data.set(foreign, PersistentDataType.STRING, "keep");
        assertTrue(expansion.isPlacedBlock(a));
        assertTrue(expansion.isPlacedBlock(b));
        expansion.removePlacedBlock(b);
        assertTrue(expansion.isPlacedBlock(a));
        assertFalse(expansion.isPlacedBlock(b));
        assertTrue(data.has(legacy));
        assertEquals("keep", data.get(foreign, PersistentDataType.STRING));
        expansion.addPlacedBlock(b);
        assertTrue(expansion.isPlacedBlock(b));
    }

    @Test
    void blockIdentityIgnoresFractionsAndOrientationAcrossNegativeChunks() {
        var location = new Location(world, -0.1, -63.2, -16.1, 90, 40);
        var block = cell(-1, -64, -17).block;
        expansion.addPlacedBlock(location);
        assertTrue(expansion.isPlacedBlock(block));
        assertFalse(expansion.isPlacedBlock(cell(15, -64, -17).block));
        assertFalse(expansion.isPlacedBlock(cell(-1, -63, -17).block));
        expansion.removePlacedBlock(block);
        assertFalse(expansion.isPlacedBlock(location));
        assertTrue(block.getChunk().getPersistentDataContainer().isEmpty());
    }

    @Test
    void fractionalRemovalOverridesCanonicalLegacyKey() {
        var block = cell(-1, 64, -1).block;
        block.getChunk().getPersistentDataContainer().set(new NamespacedKey("aurora",
                Integer.toHexString(block.getLocation().hashCode())), PersistentDataType.BYTE, (byte) 1);
        var location = new Location(world, -0.2, 64.2, -0.2, 30, 10);
        assertTrue(expansion.isPlacedBlock(location));
        expansion.removePlacedBlock(location);
        assertFalse(expansion.isPlacedBlock(block));
    }

    @Test
    void extensionSnapshotsMixedOverlappingChainAndClearsSideSource() {
        var a = cell(1, 64, 0).block;
        var b = cell(2, 64, 0).block;
        var side = cell(1, 65, 0).block;
        expansion.addPlacedBlock(a);
        expansion.addPlacedBlock(side);
        expansion.addPlacedBlock(cell(3, 64, 0).block); // stale destination
        fire(new BlockPistonExtendEvent(cell(0, 64, 0).block, List.of(a, b, side), BlockFace.EAST));
        assertFalse(expansion.isPlacedBlock(a));
        assertTrue(expansion.isPlacedBlock(b));
        assertFalse(expansion.isPlacedBlock(cell(3, 64, 0).block));
        assertFalse(expansion.isPlacedBlock(side));
        assertTrue(expansion.isPlacedBlock(cell(2, 65, 0).block));
    }

    @Test
    void retractionClearsEverySourceAndUsesEventMovementDirection() {
        var a = cell(2, 64, 0).block;
        var side = cell(2, 65, 0).block;
        expansion.addPlacedBlock(a);
        expansion.addPlacedBlock(side);
        fire(new BlockPistonRetractEvent(cell(0, 64, 0).block, List.of(a, side), BlockFace.WEST));
        assertFalse(expansion.isPlacedBlock(a));
        assertFalse(expansion.isPlacedBlock(side));
        assertTrue(expansion.isPlacedBlock(cell(1, 64, 0).block));
        assertTrue(expansion.isPlacedBlock(cell(1, 65, 0).block));
    }

    @Test
    void pistonBreakReactionDoesNotCreateDestinationMarker() {
        var flower = cell(15, 64, 0);
        flower.reaction = PistonMoveReaction.BREAK;
        expansion.addPlacedBlock(flower.block);
        fire(new BlockPistonExtendEvent(cell(14, 64, 0).block, List.of(flower.block), BlockFace.EAST));
        assertFalse(expansion.isPlacedBlock(flower.block));
        assertFalse(expansion.isPlacedBlock(cell(16, 64, 0).block));
        flower.reaction = PistonMoveReaction.MOVE;
        expansion.addPlacedBlock(flower.block);
        fire(new BlockPistonExtendEvent(cell(14, 64, 0).block, List.of(flower.block), BlockFace.EAST));
        assertTrue(expansion.isPlacedBlock(cell(16, 64, 0).block));
    }

    @Test
    void destructiveExplosionsOnlyClearTheirFinalBlockList() {
        var destroyed = cell(0, 64, 0).block;
        var preserved = cell(1, 64, 0).block;
        for (var result : ExplosionResult.values()) {
            expansion.addPlacedBlock(destroyed);
            expansion.addPlacedBlock(preserved);
            fire(new BlockExplodeEvent(destroyed, state(Material.STONE), List.of(destroyed), 1, result));
            boolean destructive = result == ExplosionResult.DESTROY || result == ExplosionResult.DESTROY_WITH_DECAY;
            assertEquals(!destructive, expansion.isPlacedBlock(destroyed), result.name());
            assertTrue(expansion.isPlacedBlock(preserved));
        }
        expansion.addPlacedBlock(destroyed);
        fire(new EntityExplodeEvent(falling(Material.SAND, new HashMap<>()), destroyed.getLocation(),
                List.of(destroyed), 1, ExplosionResult.DESTROY));
        assertFalse(expansion.isPlacedBlock(destroyed));
        assertTrue(expansion.isPlacedBlock(preserved));
    }

    @Test
    void burnAndRemovalFadeClearMarkersButSolidFadeKeepsProvenance() {
        var block = cell(0, 64, 0).block;
        expansion.addPlacedBlock(block);
        fire(new BlockBurnEvent(block, null));
        assertFalse(expansion.isPlacedBlock(block));
        for (var type : List.of(Material.AIR, Material.WATER, Material.LAVA, Material.DIRT)) {
            expansion.addPlacedBlock(block);
            fire(new BlockFadeEvent(block, state(type)));
            assertEquals(type == Material.DIRT, expansion.isPlacedBlock(block));
        }
    }

    @Test
    void cancelledEventsKeepProvenanceAndDoNotScheduleWork() {
        var block = cell(1, 64, 0).block;
        expansion.addPlacedBlock(block);
        var events = List.of(
                new BlockPistonExtendEvent(cell(0, 64, 0).block, List.of(block), BlockFace.EAST),
                new BlockPistonRetractEvent(cell(0, 64, 0).block, List.of(block), BlockFace.WEST),
                new BlockBurnEvent(block, null), new BlockFadeEvent(block, state(Material.AIR)),
                new BlockExplodeEvent(block, state(Material.STONE), List.of(block), 1, ExplosionResult.DESTROY),
                new EntityExplodeEvent(falling(Material.SAND, new HashMap<>()), block.getLocation(), List.of(block), 1, ExplosionResult.DESTROY),
                new EntityChangeBlockEvent(falling(Material.SAND, new HashMap<>()), block, blockData(Material.AIR)),
                new BlockBreakEvent(block, player()));
        for (var event : events) { ((Cancellable) event).setCancelled(true); fire(event); }
        assertTrue(expansion.isPlacedBlock(block));
        assertTrue(scheduled.isEmpty());
        assertTrue(emitted.isEmpty());
    }

    @Test
    void placedFallingBlocksCarryProvenanceThroughAirAndWaterTransitions() {
        for (var fluid : List.of(Material.AIR, Material.WATER)) {
            var source = cell(15, 80, 0);
            source.type = Material.SAND;
            expansion.addPlacedBlock(source.block);
            var entityData = new HashMap<NamespacedKey, Value>();
            fire(new EntityChangeBlockEvent(falling(Material.SAND, entityData), source.block, blockData(fluid)));
            assertFalse(expansion.isPlacedBlock(source.block));
            assertFalse(entityData.isEmpty());
            // A new entity handle with the same PDC also models reload; there is no in-memory entity cache.
            var landing = cell(16, 64, 0);
            landing.type = Material.AIR;
            fire(new EntityChangeBlockEvent(falling(Material.SAND, entityData), landing.block, blockData(Material.SAND)));
            assertTrue(entityData.isEmpty());
            landing.type = Material.SAND;
            tick();
            assertTrue(expansion.isPlacedBlock(landing.block));
            expansion.removePlacedBlock(landing.block);
        }
    }

    @Test
    void failedLandingDoesNotMarkAirAndNaturalLandingClearsStaleDestination() {
        var source = cell(0, 80, 0);
        source.type = Material.GRAVEL;
        expansion.addPlacedBlock(source.block);
        var entityData = new HashMap<NamespacedKey, Value>();
        var falling = falling(Material.GRAVEL, entityData);
        fire(new EntityChangeBlockEvent(falling, source.block, blockData(Material.AIR)));
        var target = cell(0, 64, 0);
        target.type = Material.AIR;
        fire(new EntityChangeBlockEvent(falling, target.block, blockData(Material.GRAVEL)));
        tick();
        assertFalse(expansion.isPlacedBlock(target.block));
        expansion.addPlacedBlock(target.block);
        fire(new EntityChangeBlockEvent(falling(Material.GRAVEL, new HashMap<>()), target.block, blockData(Material.GRAVEL)));
        target.type = Material.GRAVEL;
        tick();
        assertFalse(expansion.isPlacedBlock(target.block));
    }

    @Test
    void blockBreakPreservesDropTimeProvenanceUntilNextTick() {
        var block = cell(0, 64, 0).block;
        expansion.addPlacedBlock(block);
        fire(new BlockBreakEvent(block, player()));
        assertFalse(((RegionBlockBreakEvent) emitted.getFirst()).isNatural());
        assertTrue(expansion.isPlacedBlock(block), "BlockDropItemEvent must still see the placed marker");
        tick();
        assertFalse(expansion.isPlacedBlock(block));
        emitted.clear();
        fire(new BlockBreakEvent(block, player()));
        assertTrue(((RegionBlockBreakEvent) emitted.getFirst()).isNatural());
    }

    private void fire(Event event) {
        for (var method : RegionBlockListener.class.getMethods()) {
            var handler = method.getAnnotation(EventHandler.class);
            if (handler == null || !method.getParameterTypes()[0].isInstance(event)) continue;
            if (handler.ignoreCancelled() && event instanceof Cancellable c && c.isCancelled()) continue;
            try { method.invoke(listener, event); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e.getCause()); }
        }
    }

    private static void tick() {
        while (!scheduled.isEmpty()) scheduled.removeFirst().accept(null);
    }

    private Cell cell(int x, int y, int z) {
        return cells.computeIfAbsent(x + ":" + y + ":" + z, k -> new Cell(x, y, z));
    }

    private final class Cell {
        Material type = Material.STONE;
        PistonMoveReaction reaction = PistonMoveReaction.MOVE;
        final Block block;
        Cell(int x, int y, int z) {
            block = proxy(Block.class, (p, m, a) -> switch (m.getName()) {
                case "getLocation" -> new Location(world, x, y, z);
                case "getWorld" -> world;
                case "getChunk" -> world.getChunkAt(x >> 4, z >> 4);
                case "getType" -> type;
                case "getBlockData" -> blockData(type);
                case "getPistonMoveReaction" -> reaction;
                case "getRelative" -> {
                    var face = (BlockFace) a[0];
                    int distance = a.length > 1 ? (int) a[1] : 1;
                    yield cell(x + face.getModX() * distance, y + face.getModY() * distance, z + face.getModZ() * distance).block;
                }
                case "getX" -> x;
                case "getY" -> y;
                case "getZ" -> z;
                case "equals" -> p == a[0];
                case "hashCode" -> Objects.hash(x, y, z);
                case "toString" -> x + ":" + y + ":" + z;
                default -> throw new AssertionError(m);
            });
        }
    }

    private static Player player() {
        return proxy(Player.class, (p, m, a) -> { throw new AssertionError(m); });
    }

    private static BlockData blockData(Material material) {
        return proxy(BlockData.class, (p, m, a) -> {
            if (m.getName().equals("getMaterial")) return material;
            throw new AssertionError(m);
        });
    }

    private static BlockState state(Material material) {
        return proxy(BlockState.class, (p, m, a) -> {
            if (m.getName().equals("getType")) return material;
            throw new AssertionError(m);
        });
    }

    private static FallingBlock falling(Material material, Map<NamespacedKey, Value> values) {
        var data = data(values);
        return proxy(FallingBlock.class, (p, m, a) -> switch (m.getName()) {
            case "getBlockData" -> blockData(material);
            case "getPersistentDataContainer" -> data;
            default -> throw new AssertionError("Entity access outside native event: " + m);
        });
    }

    private record Value(PersistentDataType<?, ?> type, Object value) {}

    private static PersistentDataContainer data(Map<NamespacedKey, Value> values) {
        return proxy(PersistentDataContainer.class, (p, m, a) -> switch (m.getName()) {
            case "set" -> { values.put((NamespacedKey) a[0], new Value((PersistentDataType<?, ?>) a[1], a[2])); yield null; }
            case "remove" -> { values.remove(a[0]); yield null; }
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
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

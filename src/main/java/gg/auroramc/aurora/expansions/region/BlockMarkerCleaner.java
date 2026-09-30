package gg.auroramc.aurora.expansions.region;

import gg.auroramc.aurora.Aurora;
import gg.auroramc.aurora.api.message.Chat;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

/** One loaded chunk at a time, with no idle timer, chunk tickets or block-height scan. */
public final class BlockMarkerCleaner implements Listener {
    private static final int MAX_PENDING = 4096;
    private final Aurora plugin;
    private final Map<ChunkId, Job> jobs = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(MAX_PENDING);
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final LongAdder chunks = new LongAdder(), legacy = new LongAdder(), clears = new LongAdder();
    private final LongAdder deleted = new LongAdder(), skipped = new LongAdder(), queueSkipped = new LongAdder();
    private final AtomicLong maxBatchNanos = new AtomicLong();
    private final AtomicLong maxSnapshotNanos = new AtomicLong();
    private final LongAdder batchCount = new LongAdder(), totalBatchNanos = new LongAdder();
    private volatile boolean enabled, closed, saving;
    private volatile Job active;

    public BlockMarkerCleaner(Aurora plugin) { this.plugin = plugin; }

    public boolean isEnabled() { return enabled && !closed; }
    public boolean isSaving() { return saving; }

    public record Status(boolean enabled, int pendingChunks, long completedChunks, long compactedLegacyKeys,
                         long compactedClearKeys, long deletedKeys, long skippedKeys, long queueSkippedChunks,
                         double maxBatchMillis, double maxSnapshotMillis, long batches, double meanBatchMillis) {}

    public Status status() {
        return new Status(isEnabled(), jobs.size(), chunks.sum(), legacy.sum(), clears.sum(), deleted.sum(),
                skipped.sum(), queueSkipped.sum(), maxBatchNanos.get() / 1_000_000.0,
                maxSnapshotNanos.get() / 1_000_000.0, batchCount.sum(),
                totalBatchNanos.sum() / 1_000_000.0 / Math.max(1, batchCount.sum()));
    }

    public void reload() {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            if (closed) return;
            if (saving) {
                // A reload during an in-flight command must not resurrect the previous file value.
                Aurora.getLibConfig().getBlockTracker().setCleanerEnabled(enabled);
                return;
            }
            apply(Boolean.TRUE.equals(Aurora.getLibConfig().getBlockTracker().getCleanerEnabled()));
        });
    }

    private void apply(boolean value) {
        enabled = value;
        if (!value) { cancelJobs(); return; }
        for (var world : Bukkit.getWorlds()) for (var chunk : world.getLoadedChunks()) {
            enqueue(new ChunkId(world.getUID(), chunk.getX(), chunk.getZ()));
        }
    }

    /** Call on the owning region; only coordinates leave this callback. */
    public void request(Chunk chunk) {
        if (!isEnabled() || chunk.getPersistentDataContainer().isEmpty()) return;
        enqueue(new ChunkId(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    private void enqueue(ChunkId id) {
        if (!isEnabled() || jobs.containsKey(id)) return;
        if (!slots.tryAcquire()) { queueSkipped.increment(); return; }
        var job = new Job(id);
        if (jobs.putIfAbsent(id, job) != null) { slots.release(); return; }
        if (!isEnabled()) discard(job);
        wake();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) { request(event.getChunk()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(ChunkUnloadEvent event) {
        var chunk = event.getChunk();
        var job = jobs.get(new ChunkId(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
        if (job != null) { discard(job); wake(); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID id = event.getWorld().getUID();
        for (var job : jobs.values()) if (job.id.world().equals(id)) discard(job);
        wake();
    }

    private boolean current(Job job) {
        return isEnabled() && !job.cancelled && jobs.get(job.id) == job;
    }

    private void wake() {
        if (!isEnabled() || !wakePending.compareAndSet(false, true)) return;
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            wakePending.set(false);
            if (!isEnabled()) return;
            if (active != null && current(active)) return;
            active = null;
            for (var job : jobs.values()) {
                if (!current(job)) continue;
                active = job;
                schedule(job);
                break;
            }
        });
    }

    private void schedule(Job job) {
        if (!current(job)) return;
        var world = Bukkit.getWorld(job.id.world());
        if (world == null) { finish(job); return; }
        var task = Bukkit.getRegionScheduler().runDelayed(plugin, world, job.id.x(), job.id.z(), t -> step(job), 1);
        job.task = task;
        if (!current(job)) task.cancel();
    }

    private void step(Job job) {
        if (!current(job)) return;
        var world = Bukkit.getWorld(job.id.world());
        if (world == null || !world.isChunkLoaded(job.id.x(), job.id.z())) { finish(job); return; }
        long start = System.nanoTime();
        try {
            assert Bukkit.isOwnedByCurrentRegion(world, job.id.x(), job.id.z()) : "Cleaner chunk owner mismatch";
            var data = world.getChunkAt(job.id.x(), job.id.z()).getPersistentDataContainer();
            if (job.work == null) {
                if (data.isEmpty()) { finish(job); return; }
                // The native getKeys call copies all keys. It cannot itself be split across ticks.
                long snapshotStart = System.nanoTime();
                job.work = new Work(data.getKeys());
                maxSnapshotNanos.accumulateAndGet(System.nanoTime() - snapshotStart, Math::max);
            } else if (job.work.step(data)) {
                chunks.increment();
                account(job);
                finish(job);
                return;
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, "Cleaner failed for " + job.id + "; unprocessed source keys retained", failure);
            finish(job);
            return;
        } finally {
            account(job);
            long elapsed = System.nanoTime() - start;
            maxBatchNanos.accumulateAndGet(elapsed, Math::max);
            totalBatchNanos.add(elapsed); batchCount.increment();
        }
        schedule(job);
    }

    private void discard(Job job) {
        job.cancelled = true;
        if (jobs.remove(job.id, job)) slots.release();
        var task = job.task;
        if (task != null) task.cancel();
    }

    private void finish(Job job) { discard(job); wake(); }

    private void account(Job job) {
        if (job.work == null) return;
        legacy.add(job.work.legacy - job.countedLegacy); clears.add(job.work.clears - job.countedClears);
        deleted.add(job.work.deleted - job.countedDeleted); skipped.add(job.work.skipped - job.countedSkipped);
        job.countedLegacy = job.work.legacy; job.countedClears = job.work.clears;
        job.countedDeleted = job.work.deleted; job.countedSkipped = job.work.skipped;
    }

    private void cancelJobs() {
        for (var job : jobs.values()) discard(job);
        active = null;
    }

    public void close() { closed = true; enabled = false; cancelJobs(); }

    public void command(CommandSender sender, String action) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            if (closed) return;
            if (action.equalsIgnoreCase("status")) {
                var s = status();
                reply(sender, "&eCleaner：" + (s.enabled() ? "开启" : "关闭") + "；待处理区块 " + s.pendingChunks()
                        + "；本次运行已处理 " + s.completedChunks() + "，收拢旧键 " + s.compactedLegacyKeys()
                        + "，收拢覆盖键 " + s.compactedClearKeys() + "，删除失效键 " + s.deletedKeys()
                        + "，保留/跳过 " + s.skippedKeys() + "，队列满跳过区块 " + s.queueSkippedChunks()
                        + "；最高批次 " + String.format(Locale.ROOT, "%.2f", s.maxBatchMillis()) + " ms");
                return;
            }
            if (!action.equalsIgnoreCase("on") && !action.equalsIgnoreCase("off")) {
                reply(sender, "&e用法：/aurora cleaner on|off|status"); return;
            }
            if (saving) { reply(sender, "&e正在保存上一次开关操作，请稍后再试。"); return; }
            boolean value = action.equalsIgnoreCase("on");
            apply(value);
            Aurora.getLibConfig().getBlockTracker().setCleanerEnabled(value);
            saving = true;
            // Only this fresh, private YAML instance is touched by the async file operation.
            Path config = plugin.getDataFolder().toPath().resolve("config.yml");
            Bukkit.getAsyncScheduler().runNow(plugin, ioTask -> {
                Exception failure = null;
                Path temporary = null;
                try {
                    var yaml = new YamlConfiguration();
                    yaml.load(config.toFile());
                    yaml.set("block-tracker.cleaner-enabled", value);
                    temporary = Files.createTempFile(config.getParent(), "aurora-cleaner-", ".tmp");
                    Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
                    try { Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING); }
                } catch (Exception error) {
                    failure = error;
                    plugin.getLogger().log(Level.SEVERE, "Failed to save cleaner switch", error);
                } finally {
                    if (temporary != null) try { Files.deleteIfExists(temporary); }
                    catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Failed to remove cleaner config temporary file", error); }
                }
                String message = failure == null ? "&aCleaner 已" + (value ? "开启" : "关闭") + "，设置已保存。"
                        : "&cCleaner 当前已" + (value ? "开启" : "关闭") + "，但保存失败；重启会读取文件中的设置，请查看日志。";
                if (closed || !plugin.isEnabled()) { saving = false; return; }
                Bukkit.getGlobalRegionScheduler().run(plugin, completed -> { saving = false; reply(sender, message); });
            });
        });
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) player.getScheduler().run(plugin, task -> Chat.sendMessage(player, message), null);
        else Chat.sendMessage(sender, message);
    }

    private record ChunkId(UUID world, int x, int z) {}
    private static final class Job {
        final ChunkId id;
        volatile boolean cancelled;
        volatile ScheduledTask task;
        Work work; // Confined to this chunk's region callbacks.
        long countedLegacy, countedClears, countedDeleted, countedSkipped;
        Job(ChunkId id) { this.id = id; }
    }

    record Entry(NamespacedKey source, boolean legacy, long value) {}
    private static final class Group {
        final NamespacedKey target;
        final boolean legacy;
        final List<Entry> entries = new ArrayList<>(), selected = new ArrayList<>();
        int cursor;
        long[] base;
        Group(NamespacedKey target, boolean legacy) { this.target = target; this.legacy = legacy; }
    }

    /** The real migration algorithm, separated from scheduling so compatibility can be regression-tested. */
    static final class Work {
        static final int KEYS_PER_STEP = 64;
        final Iterator<NamespacedKey> keys;
        final Map<NamespacedKey, Group> groups = new LinkedHashMap<>();
        final List<NamespacedKey> clearArchives = new ArrayList<>();
        Iterator<Group> committing;
        Iterator<NamespacedKey> removingArchives;
        Group group;
        boolean hasLegacy;
        long legacy, clears, deleted, skipped;

        Work(Set<NamespacedKey> keys) { this.keys = keys.iterator(); }

        boolean step(PersistentDataContainer data) {
            long deadline = System.nanoTime() + 1_000_000;
            int touched = 0;
            if (committing == null) {
                while (keys.hasNext() && touched++ < KEYS_PER_STEP) {
                    var key = keys.next();
                    Integer hash = CompactBlockMarkers.legacyHash(key);
                    if (hash != null) {
                        hasLegacy |= data.has(key);
                        if (matches(data, key, (byte) 1)) add(CompactBlockMarkers.LEGACY[CompactBlockMarkers.bucket(hash)], true, key, hash);
                        else skipped++;
                    } else if (CompactBlockMarkers.archiveIndex(key, CompactBlockMarkers.LEGACY) >= 0) {
                        hasLegacy |= !data.has(key, PersistentDataType.INTEGER_ARRAY)
                                || data.get(key, PersistentDataType.INTEGER_ARRAY).length > 0;
                    } else if (CompactBlockMarkers.archiveIndex(key, CompactBlockMarkers.CLEARED) >= 0) {
                        if (data.has(key, PersistentDataType.LONG_ARRAY)) clearArchives.add(key);
                        else skipped++;
                    } else {
                        Long coordinate = CompactBlockMarkers.clearCoordinate(key);
                        if (coordinate != null && matches(data, key, (byte) 0))
                            add(CompactBlockMarkers.CLEARED[(int) (long) coordinate & 255], false, key, coordinate);
                    }
                    if (System.nanoTime() >= deadline) return false;
                }
                if (keys.hasNext()) return false;
                committing = groups.values().iterator();
                return false;
            }
            while (touched++ < KEYS_PER_STEP) {
                if (group == null) {
                    if (!committing.hasNext()) break;
                    group = committing.next();
                    if (!prepare(data)) { group = null; continue; }
                }
                if (group.cursor < group.entries.size()) {
                    var entry = group.entries.get(group.cursor++);
                    if (!matches(data, entry.source(), entry.legacy() ? (byte) 1 : (byte) 0)) skipped++;
                    else if (!entry.legacy() && !hasLegacy) { data.remove(entry.source()); deleted++; }
                    else if (group.selected.size() < CompactBlockMarkers.MAX_VALUES
                            && (Arrays.binarySearch(group.base, entry.value()) >= 0
                            || group.base.length + group.selected.size() < CompactBlockMarkers.MAX_VALUES)) group.selected.add(entry);
                    else skipped++;
                } else {
                    commit(data);
                    group = null;
                    return false; // At most one atomic archive write (128 source keys) per callback.
                }
                if (System.nanoTime() >= deadline) return false;
            }
            if (group != null || committing.hasNext()) return false;
            if (!hasLegacy) {
                if (removingArchives == null) removingArchives = clearArchives.iterator();
                while (removingArchives.hasNext() && touched++ < KEYS_PER_STEP) {
                    var key = removingArchives.next();
                    if (data.has(key, PersistentDataType.LONG_ARRAY)) { data.remove(key); deleted++; }
                    if (System.nanoTime() >= deadline) return false;
                }
                if (removingArchives.hasNext()) return false;
            }
            return true;
        }

        private void add(NamespacedKey target, boolean legacy, NamespacedKey source, long value) {
            groups.computeIfAbsent(target, key -> new Group(key, legacy)).entries.add(new Entry(source, legacy, value));
        }

        private boolean prepare(PersistentDataContainer data) {
            if (!group.legacy && !hasLegacy) { group.base = new long[0]; return true; }
            if (!data.has(group.target)) {
                if (group.entries.size() < CompactBlockMarkers.MIN_VALUES) { skipped += group.entries.size(); return false; }
                group.base = new long[0]; return true;
            }
            if (group.legacy && data.has(group.target, PersistentDataType.INTEGER_ARRAY)) {
                int[] values = data.get(group.target, PersistentDataType.INTEGER_ARRAY);
                if (CompactBlockMarkers.sorted(values)) {
                    group.base = new long[values.length];
                    for (int i = 0; i < values.length; i++) group.base[i] = values[i];
                    return true;
                }
            } else if (!group.legacy && data.has(group.target, PersistentDataType.LONG_ARRAY)) {
                long[] values = data.get(group.target, PersistentDataType.LONG_ARRAY);
                if (CompactBlockMarkers.sorted(values)) { group.base = values; return true; }
            }
            skipped += group.entries.size(); // Unknown/corrupt archive: never remove its source records.
            return false;
        }

        private void commit(PersistentDataContainer data) {
            if (!group.legacy && !hasLegacy) return;
            int selectedCount = group.selected.size();
            group.selected.removeIf(entry -> !matches(data, entry.source(), entry.legacy() ? (byte) 1 : (byte) 0));
            skipped += selectedCount - group.selected.size();
            if (group.selected.isEmpty()) return;
            // Small primitive buffers avoid stream boxing/class initialization on a region tick.
            long[] combined = Arrays.copyOf(group.base, group.base.length + group.selected.size());
            for (int i = 0; i < group.selected.size(); i++) combined[group.base.length + i] = group.selected.get(i).value();
            Arrays.sort(combined);
            int unique = 0;
            for (long value : combined) if (unique == 0 || value != combined[unique - 1]) combined[unique++] = value;
            long[] values = Arrays.copyOf(combined, unique);
            if (group.base.length == 0 && values.length < CompactBlockMarkers.MIN_VALUES) {
                skipped += group.selected.size(); return; // Small groups save less than the archive-key overhead.
            }
            // Publish compatibility membership BEFORE removing any source key. An interrupted job is safe to retry.
            if (!Arrays.equals(values, group.base)) {
                if (group.legacy) {
                    int[] hashes = new int[values.length];
                    for (int i = 0; i < values.length; i++) hashes[i] = (int) values[i];
                    data.set(group.target, PersistentDataType.INTEGER_ARRAY, hashes);
                } else data.set(group.target, PersistentDataType.LONG_ARRAY, values);
            }
            for (var entry : group.selected) {
                data.remove(entry.source());
                if (entry.legacy()) legacy++; else clears++;
            }
        }

        private static boolean matches(PersistentDataContainer data, NamespacedKey key, byte value) {
            return data.has(key, PersistentDataType.BYTE) && data.get(key, PersistentDataType.BYTE) == value;
        }
    }
}

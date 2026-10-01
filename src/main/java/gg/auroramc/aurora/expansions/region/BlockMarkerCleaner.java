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
import org.bukkit.plugin.IllegalPluginAccessException;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongPredicate;
import java.util.logging.Level;

/** One loaded chunk at a time; a completed round is followed by a delayed loaded-chunk recheck. */
public final class BlockMarkerCleaner implements Listener {
    private static final int MAX_PENDING = 4096;
    private static final long BATCH_NANOS = 1_000_000;
    private static final int MAX_STEPS_PER_BATCH = 8;
    private static final long RESCAN_SECONDS = 30;
    private final Aurora plugin;
    private final Map<ChunkId, Job> jobs = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(MAX_PENDING);
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final AtomicBoolean rescanRequested = new AtomicBoolean();
    private final LongAdder chunks = new LongAdder(), legacy = new LongAdder(), clears = new LongAdder();
    private final LongAdder deleted = new LongAdder(), skipped = new LongAdder(), queueSkipped = new LongAdder();
    private final LongAdder emptyChunks = new LongAdder(), createdArchives = new LongAdder(), unloaded = new LongAdder();
    private final LongAdder expiredDeleted = new LongAdder(), legacyShields = new LongAdder(), emptyArchives = new LongAdder();
    private final AtomicLong maxBatchNanos = new AtomicLong();
    private final AtomicLong maxSnapshotNanos = new AtomicLong();
    private final LongAdder batchCount = new LongAdder(), totalBatchNanos = new LongAdder();
    private volatile boolean enabled, closed, saving;
    private volatile Job active;
    private volatile LoadedScan scan; // Cursor belongs to the global region; only UUIDs/packed coordinates are retained.
    private final AtomicReference<ScheduledTask> rescanTask = new AtomicReference<>();

    public BlockMarkerCleaner(Aurora plugin) { this.plugin = plugin; }

    public boolean isEnabled() { return enabled && !closed; }
    public boolean isSaving() { return saving; }
    public boolean isScanning() { return isEnabled() && (scan != null || rescanRequested.get()); }
    public long getCheckedChunks() { return chunks.sum() + emptyChunks.sum(); }
    public long getNetRemovedKeys() { return legacy.sum() + clears.sum() + deleted.sum() - createdArchives.sum(); }
    public long getExpiredMarkersDeleted() { return expiredDeleted.sum(); }
    public long getLegacyShieldsCreated() { return legacyShields.sum(); }
    public long getEmptyArchivesDeleted() { return emptyArchives.sum(); }

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
        cancelRescan();
        enabled = value;
        if (!value) { cancelJobs(); return; }
        rescanRequested.set(false);
        scan = new LoadedScan();
        wake();
    }

    /** Call on the owning region; only coordinates leave this callback. */
    public void request(Chunk chunk) {
        if (!isEnabled() || chunk.getPersistentDataContainer().isEmpty()) return;
        if (!enqueue(new ChunkId(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ())) && isEnabled()) {
            queueSkipped.increment();
            // A load event has no cursor. One further loaded-chunk scan recovers overflow without a second queue.
            rescanRequested.set(true);
        }
        wake();
    }

    private boolean enqueue(ChunkId id) {
        if (!isEnabled()) return false;
        if (jobs.containsKey(id)) return true;
        if (!slots.tryAcquire()) return false;
        var job = new Job(id);
        if (jobs.putIfAbsent(id, job) != null) { slots.release(); return true; }
        if (!isEnabled()) discard(job);
        return true;
    }

    /** Refill on the global region, never retaining Chunk/World objects or advancing past a full queue. */
    private void fillQueue() {
        if (slots.availablePermits() == 0) return;
        if (scan == null && rescanRequested.getAndSet(false)) {
            scan = new LoadedScan();
            if (!isEnabled()) { scan = null; return; }
        }
        var cursor = scan;
        if (cursor == null) return;
        long deadline = System.nanoTime() + 1_000_000;
        for (int examined = 0; isEnabled() && scan == cursor && slots.availablePermits() > 0
                && examined < MAX_PENDING; examined++) {
            var id = cursor.peek();
            if (id == null) { scan = null; return; }
            if (!enqueue(id)) return;
            cursor.advance();
            if (System.nanoTime() >= deadline) return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) { request(event.getChunk()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(ChunkUnloadEvent event) {
        var chunk = event.getChunk();
        var job = jobs.get(new ChunkId(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
        if (job != null) { unloaded.increment(); discard(job); wake(); }
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
        try { Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            wakePending.set(false);
            if (!isEnabled()) return;
            fillQueue();
            if (active != null && current(active)) return;
            active = null;
            for (var job : jobs.values()) {
                if (!current(job)) continue;
                active = job;
                schedule(job);
                break;
            }
            if (active == null && isScanning()) wake();
            else if (active == null) scheduleRescan();
        }); } catch (IllegalPluginAccessException stopped) {
            wakePending.set(false);
            if (isEnabled() && plugin.isEnabled()) throw stopped;
        }
    }

    private void scheduleRescan() {
        if (!isEnabled() || rescanTask.get() != null) return;
        // This core keeps cancelled global delayed tasks (and their plugin) until the deadline.
        // Native async cancellation drops the owner entry immediately; only flags cross back.
        var task = Bukkit.getAsyncScheduler().runDelayed(plugin, completed -> {
            if (!rescanTask.compareAndSet(completed, null) || !isEnabled()) return;
            rescanRequested.set(true);
            wake();
        }, RESCAN_SECONDS, TimeUnit.SECONDS);
        rescanTask.set(task);
        if (!isEnabled()) cancelRescan();
    }

    private void cancelRescan() {
        var task = rescanTask.getAndSet(null);
        if (task != null) task.cancel();
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
        if (world == null) { finish(job); return; }
        if (!world.isChunkLoaded(job.id.x(), job.id.z())) { unloaded.increment(); finish(job); return; }
        long start = System.nanoTime();
        long deadline = start + BATCH_NANOS;
        try {
            assert Bukkit.isOwnedByCurrentRegion(world, job.id.x(), job.id.z()) : "Cleaner chunk owner mismatch";
            var chunk = world.getChunkAt(job.id.x(), job.id.z());
            var data = chunk.getPersistentDataContainer();
            if (job.work == null) {
                if (data.isEmpty()) { emptyChunks.increment(); finish(job); return; }
                // The native getKeys call copies all keys. It cannot itself be split across ticks.
                long snapshotStart = System.nanoTime();
                var keys = data.getKeys();
                // A name-only dependency inventory, not a scan of world blocks. Unknown legacy
                // values also count: the public Location reader supports raw hashes.
                job.work = new Work(keys, Work.hasLegacySources(keys));
                maxSnapshotNanos.accumulateAndGet(System.nanoTime() - snapshotStart, Math::max);
            }
            // Use the remaining batch budget now, including on the first visit. Small chunks
            // need not wait a separate tick for each collection/commit/finish phase.
            for (int step = 0; step < MAX_STEPS_PER_BATCH && current(job) && System.nanoTime() < deadline; step++) {
                if (job.work.step(data, deadline, coordinate -> {
                    int y = (int) (coordinate >> 8);
                    return y >= world.getMinHeight() && y < world.getMaxHeight()
                            && chunk.getBlock((int) (coordinate >> 4) & 15, y, (int) coordinate & 15).getType().isAir();
                })) {
                    chunks.increment();
                    account(job);
                    finish(job);
                    return;
                }
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
        createdArchives.add(job.work.createdArchives - job.countedArchives);
        expiredDeleted.add(job.work.expiredDeleted - job.countedExpired);
        legacyShields.add(job.work.legacyShields - job.countedShields);
        emptyArchives.add(job.work.emptyArchives - job.countedEmptyArchives);
        job.countedLegacy = job.work.legacy; job.countedClears = job.work.clears;
        job.countedDeleted = job.work.deleted; job.countedSkipped = job.work.skipped;
        job.countedArchives = job.work.createdArchives;
        job.countedExpired = job.work.expiredDeleted;
        job.countedShields = job.work.legacyShields;
        job.countedEmptyArchives = job.work.emptyArchives;
    }

    private void cancelJobs() {
        cancelRescan();
        scan = null;
        rescanRequested.set(false);
        for (var job : jobs.values()) discard(job);
        active = null;
    }

    public void close() { closed = true; enabled = false; cancelJobs(); }

    public void command(CommandSender sender, String action) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            if (closed) return;
            if (action.equalsIgnoreCase("status")) {
                reply(sender, statusMessage());
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

    String statusMessage() {
        var s = status();
        String state = !s.enabled() ? "已暂停" : isScanning() || s.pendingChunks() > 0
                ? "仍有区块待检查/处理" : "当前队列处理完毕，30 秒后复查已加载区块";
        return "&eCleaner：" + (s.enabled() ? "开启" : "关闭") + "；边检查边清理；等待区块 " + s.pendingChunks()
                + "\n&e检查完成 " + getCheckedChunks() + " 次（含空区块）；" + state
                + "\n&a实际删除空气残留标记 " + getExpiredMarkersDeleted() + "；转为旧格式兼容屏蔽 " + getLegacyShieldsCreated() + " 次"
                + "\n&a删除无用存储键 " + s.deletedKeys() + "（含空数组 " + getEmptyArchivesDeleted() + "）；存储项净减少 " + getNetRemovedKeys()
                + "\n&a整理并移除旧存储项 " + (s.compactedLegacyKeys() + s.compactedClearKeys()) + "（原信息保留）"
                + "\n&e无法确认失效的旧哈希保留，不计入空气残留删除"
                + "\n&e区块卸载暂缓 " + unloaded.sum() + " 次（下次加载继续）；最高单批 "
                + String.format(Locale.ROOT, "%.2f", s.maxBatchMillis()) + " ms";
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) player.getScheduler().run(plugin, task -> Chat.sendMessage(player, message), null);
        else Chat.sendMessage(sender, message);
    }

    private record ChunkId(UUID world, int x, int z) {
        // JDK 25's generated record equals adapter leaves a global soft reference to this
        // plugin's class loader. Compare the same fields directly so hot unload can release it.
        @Override public boolean equals(Object other) {
            return other instanceof ChunkId id && x == id.x && z == id.z && Objects.equals(world, id.world);
        }
    }
    private static final class LoadedScan {
        final Iterator<UUID> worlds = Bukkit.getWorlds().stream().map(org.bukkit.World::getUID).toList().iterator();
        UUID world;
        long[] coordinates;
        int index;

        ChunkId peek() {
            while (coordinates == null) {
                if (!worlds.hasNext()) return null;
                world = worlds.next();
                var loadedWorld = Bukkit.getWorld(world);
                if (loadedWorld == null) continue;
                var loaded = loadedWorld.getLoadedChunks();
                if (loaded.length == 0) continue;
                coordinates = new long[loaded.length];
                for (int i = 0; i < loaded.length; i++)
                    coordinates[i] = ((long) loaded[i].getX() << 32) | (loaded[i].getZ() & 0xffffffffL);
                index = 0;
            }
            long coordinate = coordinates[index];
            return new ChunkId(world, (int) (coordinate >> 32), (int) coordinate);
        }

        void advance() {
            if (++index == coordinates.length) { coordinates = null; world = null; }
        }
    }
    private static final class Job {
        final ChunkId id;
        volatile boolean cancelled;
        volatile ScheduledTask task;
        Work work; // Confined to this chunk's region callbacks.
        long countedLegacy, countedClears, countedDeleted, countedSkipped, countedArchives;
        long countedExpired, countedShields, countedEmptyArchives;
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
        void reset() { entries.clear(); selected.clear(); cursor = 0; base = null; }
    }

    /** The real migration algorithm, separated from scheduling so compatibility can be regression-tested. */
    static final class Work {
        static final int KEYS_PER_STEP = 64;
        final Iterator<NamespacedKey> keys;
        final boolean legacyPossible;
        final Map<NamespacedKey, Group> groups = new LinkedHashMap<>();
        final List<NamespacedKey> clearArchives = new ArrayList<>();
        Iterator<Group> committing;
        Iterator<NamespacedKey> removingArchives;
        Group group;
        boolean hasLegacy;
        boolean collecting = true;
        long legacy, clears, deleted, skipped, createdArchives;
        long expiredDeleted, legacyShields, emptyArchives;

        Work(Set<NamespacedKey> keys) { this(keys, true); }

        Work(Set<NamespacedKey> keys, boolean legacyPossible) {
            this.keys = keys.iterator();
            this.legacyPossible = legacyPossible;
        }

        static boolean hasLegacySources(Set<NamespacedKey> keys) {
            return keys.stream().anyMatch(key -> CompactBlockMarkers.legacyHash(key) != null
                    || CompactBlockMarkers.archiveIndex(key, CompactBlockMarkers.LEGACY) >= 0);
        }

        boolean step(PersistentDataContainer data) {
            return step(data, System.nanoTime() + BATCH_NANOS);
        }

        boolean step(PersistentDataContainer data, long deadline) {
            return step(data, deadline, coordinate -> false);
        }

        boolean step(PersistentDataContainer data, long deadline, LongPredicate isAir) {
            int touched = 0;
            if (committing == null) {
                while (keys.hasNext() && touched < KEYS_PER_STEP
                        && (touched == 0 || System.nanoTime() < deadline)) {
                    touched++;
                    var key = keys.next();
                    Integer hash = CompactBlockMarkers.legacyHash(key);
                    if (hash != null) {
                        hasLegacy |= data.has(key);
                        if (matches(data, key, (byte) 1)) collect(data, CompactBlockMarkers.LEGACY[CompactBlockMarkers.bucket(hash)], true, key, hash);
                        else skipped++;
                    } else if (CompactBlockMarkers.archiveIndex(key, CompactBlockMarkers.LEGACY) >= 0) {
                        if (data.has(key, PersistentDataType.INTEGER_ARRAY)
                                && data.get(key, PersistentDataType.INTEGER_ARRAY).length == 0) {
                            data.remove(key); deleted++; emptyArchives++;
                            continue;
                        }
                        hasLegacy |= !data.has(key, PersistentDataType.INTEGER_ARRAY)
                                || data.get(key, PersistentDataType.INTEGER_ARRAY).length > 0;
                    } else if (CompactBlockMarkers.archiveIndex(key, CompactBlockMarkers.CLEARED) >= 0) {
                        if (data.has(key, PersistentDataType.LONG_ARRAY)
                                && data.get(key, PersistentDataType.LONG_ARRAY).length == 0) {
                            data.remove(key); deleted++; emptyArchives++;
                            continue;
                        }
                        if (!legacyPossible && data.has(key, PersistentDataType.LONG_ARRAY)) {
                            data.remove(key); deleted++;
                            continue;
                        }
                        if (data.has(key, PersistentDataType.LONG_ARRAY)) clearArchives.add(key);
                        else skipped++;
                    } else {
                        Long coordinate = CompactBlockMarkers.clearCoordinate(key);
                        if (coordinate != null && matches(data, key, (byte) 1) && isAir.test(coordinate)
                                && matches(data, key, (byte) 1)) {
                            if (!legacyPossible || CompactBlockMarkers.isCleared(data, coordinate)) {
                                data.remove(key); deleted++; expiredDeleted++;
                                continue; // Remove before checking another key, not at the end of the scan.
                            }
                            // Removing this override could resurrect an old integer or raw Location
                            // hash. Cancel the stale placement state but retain its required shield.
                            data.set(key, PersistentDataType.BYTE, (byte) 0);
                            legacyShields++;
                        }
                        if (coordinate != null && matches(data, key, (byte) 0)) {
                            if (!legacyPossible) { data.remove(key); deleted++; }
                            else collect(data, CompactBlockMarkers.CLEARED[(int) (long) coordinate & 255], false, key, coordinate);
                        }
                    }
                    if (System.nanoTime() >= deadline) break;
                }
                if (keys.hasNext()) return false;
                collecting = false;
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
                    select(data, group.entries.get(group.cursor++));
                } else {
                    commit(data);
                    group = null;
                    return false; // One leftover group per final-pass step; discovery commits eligible sources inline.
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

        private void collect(PersistentDataContainer data, NamespacedKey target, boolean legacy, NamespacedKey source, long value) {
            var collected = groups.computeIfAbsent(target, key -> new Group(key, legacy));
            collected.entries.add(new Entry(source, legacy, value));
            // Reuse an existing bounded archive immediately. A new archive still needs four
            // sources to avoid increasing storage for sparse data; commit on the fourth key,
            // before checking any subsequent key, without a deferred commit phase.
            if (collected.entries.size() < CompactBlockMarkers.MIN_VALUES && !data.has(target)) return;
            group = collected;
            if (prepare(data)) {
                for (var entry : group.entries) select(data, entry);
                commit(data);
            }
            collected.reset();
            group = null;
        }

        private void select(PersistentDataContainer data, Entry entry) {
            if (!matches(data, entry.source(), entry.legacy() ? (byte) 1 : (byte) 0)) skipped++;
            else if (!entry.legacy() && !collecting && !hasLegacy) { data.remove(entry.source()); deleted++; }
            else if (group.selected.size() < CompactBlockMarkers.MAX_VALUES
                    && (Arrays.binarySearch(group.base, entry.value()) >= 0
                    || group.base.length + group.selected.size() < CompactBlockMarkers.MAX_VALUES)) group.selected.add(entry);
            else skipped++;
        }

        private boolean prepare(PersistentDataContainer data) {
            if (group.entries.isEmpty()) return false;
            if (!group.legacy && !collecting && !hasLegacy) { group.base = new long[0]; return true; }
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
            if (!group.legacy && !collecting && !hasLegacy) return;
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
                boolean created = !data.has(group.target);
                if (group.legacy) {
                    int[] hashes = new int[values.length];
                    for (int i = 0; i < values.length; i++) hashes[i] = (int) values[i];
                    data.set(group.target, PersistentDataType.INTEGER_ARRAY, hashes);
                } else data.set(group.target, PersistentDataType.LONG_ARRAY, values);
                if (created) {
                    createdArchives++;
                    if (!group.legacy) clearArchives.add(group.target);
                }
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

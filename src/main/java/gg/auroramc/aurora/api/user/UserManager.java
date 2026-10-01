package gg.auroramc.aurora.api.user;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import gg.auroramc.aurora.Aurora;
import gg.auroramc.aurora.api.events.user.AuroraUserLoadedEvent;
import gg.auroramc.aurora.api.events.user.AuroraUserUnloadedEvent;
import gg.auroramc.aurora.api.message.Text;
import gg.auroramc.aurora.api.user.migration.StorageMigrator;
import gg.auroramc.aurora.api.user.storage.LatencyMeasure;
import gg.auroramc.aurora.api.user.storage.SaveReason;
import gg.auroramc.aurora.api.user.storage.UserStorage;
import gg.auroramc.aurora.api.user.storage.YamlStorage;
import gg.auroramc.aurora.api.user.storage.sql.MySqlStorage;
import gg.auroramc.aurora.expansions.leaderboard.LeaderboardExpansion;
import gg.auroramc.aurora.expansions.leaderboard.storage.sqlite.SqliteLeaderboardStorage;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

public class UserManager implements Listener {
    @Getter
    private volatile UserStorage storage;
    private final ConcurrentHashMap<UUID, Object> playerLocks = new ConcurrentHashMap<>();
    @Getter
    private final Set<Class<? extends UserDataHolder>> dataHolders = new CopyOnWriteArraySet<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;
    private final Set<CompletableFuture<?>> operations = ConcurrentHashMap.newKeySet();
    private ScheduledTask autoSaveTask;
    private ScheduledTask leaderboardUpdateTask;
    @Getter
    private final LatencyMeasure loadLatencyMeasure = new LatencyMeasure();
    @Getter
    private final LatencyMeasure saveLatencyMeasure = new LatencyMeasure();
    @Getter
    private final LatencyMeasure syncFlagLatencyMeasure = new LatencyMeasure();
    private StorageMigrator migrator = new StorageMigrator(this);

    // Stores actual online users data
    private final Cache<UUID, AuroraUser> cache = CacheBuilder.newBuilder().build();


    public UserManager() {
        Bukkit.getPluginManager().registerEvents(this, Aurora.getInstance());

        if (Aurora.getLibConfig().getStorageType().equals("mysql")) {
            storage = new MySqlStorage();
        } else {
            storage = new YamlStorage();
        }

        autoSaveTask();
        leaderboardUpdateTask();
    }

    public CompletableFuture<Boolean> attemptMigration(int threadCount) {
        migrator.markMigrating();

        return CompletableFuture.supplyAsync(() -> {
            stopTasksAndSaveAllData(false);
            cache.invalidateAll();

            Bukkit.getGlobalRegionScheduler().run(Aurora.getInstance(), (task) -> {
                Bukkit.getOnlinePlayers().forEach(player -> player.kick(
                        Text.component(Aurora.getMsg(player).getKickedByDbMigration())
                ));
            });

            UserStorage newStorage = this.storage;
            String newStorageId = storage instanceof MySqlStorage ? "yaml" : "mysql";
            boolean success = false;

            if (storage instanceof MySqlStorage mySqlStorage) {
                newStorage = new YamlStorage();
                success = migrator.migrateUserData(mySqlStorage, (YamlStorage) newStorage, threadCount);
            } else if (storage instanceof YamlStorage yamlStorage) {
                newStorage = new MySqlStorage();
                success = migrator.migrateUserData(yamlStorage, (MySqlStorage) newStorage, threadCount);
            }

            if (success) {
                storage.dispose();
                storage = newStorage;
                if (newStorage instanceof MySqlStorage mySqlStorage) {
                    Aurora.getExpansionManager().getExpansion(LeaderboardExpansion.class).setStorage(mySqlStorage);
                } else {
                    Aurora.getExpansionManager().getExpansion(LeaderboardExpansion.class).setStorage(new SqliteLeaderboardStorage());
                }
                Aurora.getLibConfig().setStorageType(newStorageId);
                Aurora.getLibConfig().saveChanges();
                autoSaveTask();
                leaderboardUpdateTask();
            }

            migrator.markFinished();

            return success;
        });
    }

    public boolean saveUserData(AuroraUser user, SaveReason reason) {
        synchronized (getPlayerLock(user.getUniqueId())) {
            var result = storage.saveUser(user, reason);
            if (result && reason == SaveReason.QUIT && !Bukkit.isStopping() && !Aurora.isDisabling()) {
                Aurora.logger().debug("Saved user " + user.getUniqueId() + " into storage");
                Bukkit.getGlobalRegionScheduler().run(Aurora.getInstance(),
                        (task) -> Bukkit.getPluginManager().callEvent(new AuroraUserUnloadedEvent(user)));
            }
            return result;
        }
    }

    private void autoSaveTask() {
        if (closing) return;
        this.autoSaveTask = Bukkit.getAsyncScheduler().runDelayed(Aurora.getInstance(), (task) -> {
            if (closing) return;
            var values = cache.asMap().values();
            var toSave = values.stream().filter(u -> u.isLoaded() && u.isDirty()).toList();
            if (!toSave.isEmpty()) {
                var successCount = storage.bulkSaveUsers(toSave, SaveReason.AUTO_SAVE);
                var all = toSave.size();
                if (!Bukkit.getOnlinePlayers().isEmpty() && !values.isEmpty()) {
                    Aurora.logger().info("Auto background saved user data for " + successCount + "/" + all + " online players");
                }
            }
            autoSaveTask();
        }, Aurora.getLibConfig().getUserAutoSaveInMinutes(), TimeUnit.MINUTES);
    }

    private void leaderboardUpdateTask() {
        if (closing) return;
        this.leaderboardUpdateTask = Bukkit.getAsyncScheduler().runDelayed(Aurora.getInstance(), (task) -> {
            if (closing) return;
            var lbm = Aurora.getExpansionManager().getExpansion(LeaderboardExpansion.class);
            var values = cache.asMap().values();
            var toUpdate = new HashMap<UUID, Collection<String>>();
            for (var user : values) {
                var dirtyBoards = user.getDirtyLeaderboards();
                if (!user.isLoaded() || dirtyBoards.isEmpty()) {
                    continue;
                }
                user.updateOriginalLeaderboardDataFromCurrent();
                toUpdate.put(user.getUniqueId(), dirtyBoards.keySet());
            }
            if (toUpdate.isEmpty()) {
                lbm.updateLeaderBoards();
                leaderboardUpdateTask();
                return;
            }
            track(lbm.bulkUpdateUsers(toUpdate).thenRunAsync(() -> { if (!closing) lbm.updateLeaderBoards(); }).thenRun(this::leaderboardUpdateTask));
        }, 5, TimeUnit.MINUTES);
    }

    private Object getPlayerLock(UUID playerId) {
        return playerLocks.computeIfAbsent(playerId, k -> new Object());
    }

    public <T extends UserDataHolder> void registerUserDataHolder(Class<T> clazz) {
        if (!dataHolders.add(clazz)) return;
        // A dependent plugin may be enabled after Aurora has restored online users.
        for (var user : cache.asMap().values()) {
            if (user.isLoaded()) user.initMissingData(clazz);
        }
    }

    public void loadOnlinePlayers() {
        Bukkit.getGlobalRegionScheduler().run(Aurora.getInstance(), task -> {
            if (closing) return;
            for (var player : Bukkit.getOnlinePlayers()) {
                var cached = cache.getIfPresent(player.getUniqueId());
                if (cached == null || !cached.isLoaded()) loadUser(player.getUniqueId());
            }
        });
    }

    public void beginHotUnload() {
        closing = true;
        if (autoSaveTask != null) autoSaveTask.cancel();
        if (leaderboardUpdateTask != null) leaderboardUpdateTask.cancel();
    }

    public int getActiveLoads() { return loading.size(); }

    public int getActiveOperations() { return operations.size(); }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        operations.add(future);
        future.whenComplete((value, failure) -> operations.remove(future));
        return future;
    }

    /**
     * Checks if the user is in the cache
     * Use this if you are working with offline players and load data inside a CompletableFuture!
     *
     * @param uuid player's uuid
     * @return whether the players data is loaded into the cache or not
     */
    public boolean isUserCached(UUID uuid) {
        return cache.getIfPresent(uuid) != null;
    }

    /**
     * Gets the user from cache
     *
     * @param player player to get user data for
     * @return loaded AuroraUser
     */
    public AuroraUser getUser(OfflinePlayer player) {
        return getUser(player.getUniqueId());
    }

    /**
     * Gets the user from cache
     *
     * @param player player to get user data for
     * @return loaded AuroraUser
     */
    public AuroraUser getUser(Player player) {
        return getUser(player.getUniqueId());
    }

    /**
     * Gets the user from cache.
     *
     * @param uuid player's uuid to load data for
     * @return loaded AuroraUser
     */
    public AuroraUser getUser(UUID uuid) {
        var user = cache.getIfPresent(uuid);
        if (user != null) return user;

        // Try to compute atomically, preventing race
        // In very rare scenarios it was possible that loadUser was put the actual
        // loaded user into cache after the getIfPresent check here.
        // This computeIfAbsent instead if put will hopefully prevent that
        return cache.asMap().computeIfAbsent(uuid, (key) -> {
            var fakeUser = new AuroraUser(key, false);
            fakeUser.initData(null, dataHolders);
            Aurora.logger().debug("Created fake user " + key + " in cache");
            return fakeUser;
        });
    }

    /**
     * Loads user into the cache.
     *
     * @param uuid player's uuid to load data for
     */
    public void loadUser(UUID uuid) {
        if (closing || !loading.add(uuid)) return;
        track(CompletableFuture.runAsync(() -> {
            if (closing) { loading.remove(uuid); return; }
            synchronized (getPlayerLock(uuid)) {
                var retained = cache.getIfPresent(uuid);
                if (retained != null && retained.isLoaded() && Bukkit.getPlayer(uuid) != null) {
                    // A failed or unfinished quit still owns the latest data; a rejoin must not replace it from storage.
                    loading.remove(uuid);
                    Bukkit.getGlobalRegionScheduler().run(Aurora.getInstance(),
                            (task) -> { if (!closing) Bukkit.getPluginManager().callEvent(new AuroraUserLoadedEvent(retained)); });
                    return;
                }
                var lbm = Aurora.getExpansionManager().getExpansion(LeaderboardExpansion.class);

                storage.loadUser(uuid, dataHolders, user -> {
                    try {
                    if (closing || !user.isLoaded() || Bukkit.getPlayer(uuid) == null) return;

                    var maybeUser = cache.getIfPresent(uuid);

                    if (maybeUser != null && !maybeUser.isLoaded()) {
                        lbm.loadUser(user.getUniqueId()).thenAcceptAsync(maybeUser.getLeaderboardEntries()::putAll);

                        maybeUser.loadFromUser(user);
                        Aurora.logger().debug("Updated user " + user.getUniqueId() + " in cache");

                        Bukkit.getGlobalRegionScheduler().run(Aurora.getInstance(),
                                (task) -> { if (!closing) Bukkit.getPluginManager().callEvent(new AuroraUserLoadedEvent(maybeUser)); });
                    } else {
                        lbm.loadUser(user.getUniqueId()).thenAcceptAsync(user.getLeaderboardEntries()::putAll);

                        cache.put(uuid, user);
                        Aurora.logger().debug("Loaded user " + user.getUniqueId() + " into cache");

                        Bukkit.getGlobalRegionScheduler().run(Aurora.getInstance(),
                                (task) -> { if (!closing) Bukkit.getPluginManager().callEvent(new AuroraUserLoadedEvent(user)); });
                    }
                    } finally { loading.remove(uuid); }
                });
            }
        }).exceptionally(failure -> { loading.remove(uuid); Aurora.getInstance().getLogger().log(java.util.logging.Level.SEVERE, "Failed to load user " + uuid, failure); return null; }));
    }

    /**
     * Loads user directly from storage.
     *
     * @param uuid player's uuid to load data for
     */
    public CompletableFuture<AuroraUser> loadUserFromStorage(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> storage.loadUser(uuid, dataHolders));
    }

    public void purgeUserData(UUID uuid) {
        if (closing) return;
        track(CompletableFuture.runAsync(() -> {
            if (closing) return;
            synchronized (getPlayerLock(uuid)) {
                if (cache.getIfPresent(uuid) != null) {
                    var oldUser = cache.getIfPresent(uuid);
                    cache.invalidate(uuid);
                    var newUser = new AuroraUser(uuid, true);
                    newUser.initData(new YamlConfiguration(), dataHolders);
                    cache.put(uuid, newUser);
                    storage.purgeUser(uuid);
                    Bukkit.getGlobalRegionScheduler().run(Aurora.getInstance(), (task) -> {
                        Bukkit.getPluginManager().callEvent(new AuroraUserUnloadedEvent(oldUser));
                        Bukkit.getPluginManager().callEvent(new AuroraUserLoadedEvent(newUser));
                    });
                } else {
                    storage.purgeUser(uuid);
                }
            }
        }));
    }

    /**
     * Stop cacheTTL heartbeat task for online players and autosave task.
     * Saves all cached data sync.
     * Should be used only in onDisable()
     */
    public void stopTasksAndSaveAllData(boolean dispose) {
        if (autoSaveTask != null) autoSaveTask.cancel();
        if (leaderboardUpdateTask != null) leaderboardUpdateTask.cancel();
        storage.bulkSaveUsers(cache.asMap().values().stream().filter(AuroraUser::isLoaded).toList(), SaveReason.QUIT);
        var lbm = Aurora.getExpansionManager().getExpansion(LeaderboardExpansion.class);
        lbm.bulkUpdateUsers(cache.asMap().values().stream().filter(AuroraUser::isLoaded).collect(HashMap::new, (m, u) -> m.put(u.getUniqueId(), u.getDirtyLeaderboards().keySet()), HashMap::putAll)).join();
        if (dispose) storage.dispose();
    }

    public void invalidate(Player player) {
        if (closing) return;
        var user = cache.getIfPresent(player.getUniqueId());
        if (user == null) return;
        track(CompletableFuture.supplyAsync(() -> {
            var lbm = Aurora.getExpansionManager().getExpansion(LeaderboardExpansion.class);
            try {
                lbm.updateUser(user, Collections.emptyList()).join();
            } catch (Exception failure) {
                Aurora.getInstance().getLogger().log(java.util.logging.Level.SEVERE,
                        "Failed to update leaderboards for quitting user " + user.getUniqueId() + "; still saving player data", failure);
            }
            return saveUserData(user, SaveReason.QUIT);
        }).thenAcceptAsync(success -> {
            if (!success) {
                Aurora.getInstance().getLogger().severe("Failed to save quitting user " + user.getUniqueId() + "; retaining cached data for retry");
                return;
            }
            var currentPlayer = user.getPlayer();
            if (currentPlayer == null || !currentPlayer.isOnline()) {
                if (!cache.asMap().remove(user.getUniqueId(), user)) return;
                Aurora.logger().debug("Removed user " + user.getUniqueId() + " from cache");
                playerLocks.remove(user.getUniqueId());
            } else {
                Aurora.logger().debug("Failed to remove user " + user.getUniqueId() + " from cache, because player is still online");
            }
        }).exceptionally(failure -> {
            Aurora.getInstance().getLogger().log(java.util.logging.Level.SEVERE,
                    "Failed to persist quitting user " + user.getUniqueId() + "; retaining cached data for retry", failure);
            return null;
        }));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent e) {
        loadUser(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerPreJoin(AsyncPlayerPreLoginEvent e) {
        if (migrator.isMigrating()) {
            e.kickMessage(Text.component("&cUnder maintenance, please try again later."));
            e.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if (migrator.isMigrating()) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(Aurora.getInstance(),
                (task) -> invalidate(e.getPlayer()), 1);
    }
}

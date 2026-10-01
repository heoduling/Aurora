package gg.auroramc.aurora.lifecycle;

import gg.auroramc.aurora.Aurora;
import gg.auroramc.aurora.api.menu.AuroraMenu;
import gg.auroramc.aurora.expansions.leaderboard.LeaderboardExpansion;
import gg.auroramc.aurora.expansions.worldguard.WorldGuardExpansion;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Prepares ordinary PlugMan commands while the plugins can still schedule player-owned cleanup. */
public final class HotReload implements Listener {
    private final Aurora plugin;
    private final Set<CompletableFuture<?>> commandChanges = ConcurrentHashMap.newKeySet();
    private final Map<Plugin, Object> guards = new IdentityHashMap<>();
    private volatile boolean busy;
    private volatile boolean paused;
    private volatile Plugin allowedUnload;
    private boolean disablingOnly;
    private volatile Throwable commandFailure;

    public HotReload(Aurora plugin) {
        this.plugin = plugin;
        releaseInitializationTrace(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void releaseInitializationTrace(Plugin loaded) { UnloadReferences.releaseInitializationTrace(loaded); }

    public boolean isPaused() { return paused; }

    public boolean isReady() {
        if (commandFailure != null) throw new IllegalStateException("Command registration failed", commandFailure);
        return commandChanges.isEmpty() && Aurora.getUserManager().getActiveLoads() == 0
                && !Aurora.getUserManager().hasUnreadyOnlineUsers();
    }

    public CompletableFuture<Void> changeCommands(Runnable change) {
        var result = new CompletableFuture<Void>();
        commandChanges.add(result);
        result.whenComplete((value, failure) -> commandChanges.remove(result));
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try { CommandTreeGate.run(plugin, change); result.complete(null); }
            catch (Throwable failure) {
                commandFailure = failure;
                result.completeExceptionally(failure);
                plugin.getLogger().log(Level.SEVERE, "Failed to update command registrations", failure);
            }
        });
        return result;
    }

    public void detach(Plugin dependent) {
        Aurora.getUserManager().detachDataHolders(dependent.getClass().getClassLoader());
        Aurora.getExpansionManager().getExpansion(LeaderboardExpansion.class).detachBoards(dependent.getClass().getClassLoader());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void playerCommand(PlayerCommandPreprocessEvent event) {
        if (handle(event.getPlayer(), event.getMessage())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void consoleCommand(ServerCommandEvent event) {
        if (handle(event.getSender(), event.getCommand())) event.setCancelled(true);
    }

    private boolean bridgeActive() {
        return Bukkit.getPluginManager().isPluginEnabled("AuroraHotSwap");
    }

    private boolean handle(CommandSender sender, String input) {
        if (bridgeActive()) return false;
        var parts = input.strip().replaceFirst("^/", "").split("\\s+");
        if (parts.length == 0) return false;
        var command = Bukkit.getCommandMap().getCommand(parts[0].toLowerCase(Locale.ROOT));
        var owner = command instanceof PluginIdentifiableCommand owned ? owned.getPlugin() : null;
        if (owner != null && Set.of("Aurora", "AuroraQuests").contains(owner.getName()) && paused) {
            reply(sender, "Aurora 正在保存或等待修复数据库，任务暂时不可操作。");
            return true;
        }
        if (owner == null || !owner.getName().equals("PlugManX") || parts.length < 3) return false;
        var operation = parts[1].toLowerCase(Locale.ROOT);
        if (!Set.of("unload", "reload", "restart", "disable", "enable", "load").contains(operation)) return false;
        var targetName = parts[2].toLowerCase(Locale.ROOT).replaceFirst("\\.jar$", "");
        if (!command.testPermissionSilent(sender) || !sender.hasPermission("plugman." + operation)) return false;
        if (targetName.equals("all") || targetName.equals("*")) {
            reply(sender, "请分别操作 Aurora 或 AuroraQuests，让插件先保存数据再卸载。");
            return true;
        }
        boolean loading = operation.equals("load") || operation.equals("enable");
        if (!targetName.equals("aurora") && !targetName.equals("auroraquests")
                && !(loading && (targetName.startsWith("aurora-") || targetName.startsWith("auroraquests-")))) return false;
        if (busy && (operation.equals("load") || operation.equals("enable"))) {
            reply(sender, "Aurora 正在保存和清理，请等待完成后加载。");
            return true;
        }
        if (operation.equals("load")) return false;
        var target = Bukkit.getPluginManager().getPlugin(targetName.equals("aurora") ? "Aurora" : "AuroraQuests");
        if (target == null || (operation.equals("enable") && target.isEnabled())) return false;
        synchronized (this) {
            if (busy) { reply(sender, "Aurora 正在处理上一条更新指令，请等待完成。"); return true; }
            busy = true;
        }
        reply(sender, "正在保存和清理 Aurora；玩家可以留在服务器，请等待完成提示。");
        var senderId = sender instanceof Player player ? player.getUniqueId() : null;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                perform(owner, target, operation, task);
                reply(senderId, owner, "Aurora 保存和清理完成；已执行 " + operation + " " + target.getName() + "。加载新版后在线玩家会自动恢复。");
            }
            catch (Throwable failure) {
                plugin.getLogger().log(Level.SEVERE, "Hot unload stopped; player data retained and plugins were not intentionally continued", failure);
                reply(senderId, owner, "Aurora 更新停止：" + failure.getMessage() + "。若是数据库保存失败，恢复连接后重试原指令。");
            } finally { busy = false; }
        });
        return true;
    }

    private void perform(Plugin plugman, Plugin target, String operation, ScheduledTask coordinator) throws Exception {
        if (Runtime.version().feature() != 25 || !Bukkit.getVersion().equals("26.2-DEV-9daa83b (MC: 26.2)")) throw new IllegalStateException("Unverified server version: " + Bukkit.getVersion());
        if (!plugman.getDescription().getVersion().equals("3.1.0-Beta.2")) throw new IllegalStateException("Unverified PlugManX version");
        CommandTreeGate.pool();
        var managerType = Class.forName("core.com.rylinaux.plugman.plugins.PluginManager", true, plugman.getClass().getClassLoader());
        var manager = RuntimeAccess.call(RuntimeAccess.call(plugman, "getServiceRegistry"), "get", managerType);
        var main = target == plugin;
        var quests = main ? Bukkit.getPluginManager().getPlugin("AuroraQuests") : target;
        var pair = new ArrayList<Plugin>();
        if (quests != null) pair.add(quests);
        if (main) pair.add(plugin);
        if (operation.equals("disable") && main) throw new IllegalStateException("Use plugman unload Aurora so its replacement can be loaded safely");
        for (var old : pair) if ((boolean) RuntimeAccess.call(manager, "isIgnored", old.getName())) {
            throw new IllegalStateException("PlugMan ignores this plugin: " + old.getName());
        }
        for (var other : Bukkit.getPluginManager().getPlugins()) {
            if (other == quests || other == plugin || !other.isEnabled()) continue;
            for (var old : pair) if (other.getDescription().getDepend().contains(old.getName())) {
                throw new IllegalStateException("Dependent plugin still enabled: " + other.getName());
            }
        }
        await(() -> commandChanges.isEmpty() && Aurora.getUserManager().getActiveLoads() == 0
                && Aurora.getUserManager().getActiveOperations() == 0 && !Aurora.getUserManager().hasUnreadyOnlineUsers(), "Player data is still loading");
        if (commandFailure != null) throw new IllegalStateException("Command registration failed", commandFailure);
        var ids = new HashSet<UUID>();
        var runningTasks = new ArrayList<ScheduledTask>();
        var metrics = new ArrayList<ExecutorService>();
        waitFor(global(plugman, () -> {
            for (var player : Bukkit.getOnlinePlayers()) { ids.add(player.getUniqueId()); unchecked(() -> EntityTaskDrain.validate(player)); }
            paused = true;
            Aurora.setDisabling(true);
            for (var old : pair) unchecked(() -> {
                var metric = RuntimeAccess.get(old, "metrics");
                if (metric != null) metrics.add((ExecutorService) RuntimeAccess.get(RuntimeAccess.get(metric, "metricsBase"), "scheduler"));
            });
            if (main) plugin.beginHotUnload();
            else Aurora.getUserManager().beginHotUnload();
            if (quests != null) unchecked(() -> RuntimeAccess.call(quests, "beginHotUnload"));
            if (main) {
                var wg = Aurora.getExpansionManager().getExpansion(WorldGuardExpansion.class);
                if (wg != null) wg.stopNewSessions();
            }
            Aurora.getMenuManager().getRefresher().stopRefreshing();
            unchecked(() -> {
                for (var value : (Set<?>) RuntimeAccess.get(Bukkit.getAsyncScheduler(), "tasks")) {
                    var task = (ScheduledTask) value;
                    if (task != coordinator && (task.getOwningPlugin() == plugin || pair.contains(task.getOwningPlugin()))) runningTasks.add(task);
                }
            });
            for (var old : pair) {
                Bukkit.getAsyncScheduler().cancelTasks(old);
                Bukkit.getGlobalRegionScheduler().cancelTasks(old);
            }
        }));
        for (var executor : metrics) executor.shutdownNow();
        for (var executor : metrics) if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new TimeoutException("Plugin metrics still running");
        var threads = UnloadReferences.threads();
        var groups = new HashSet<ThreadGroup>();
        for (var thread : threads) if (thread.getThreadGroup() != null) groups.add(thread.getThreadGroup());
        var beans = new ArrayList<Class<?>>();
        if (quests != null) {
            for (var name : List.of("org.quartz.simpl.SimpleThreadPool", "org.quartz.simpl.RAMJobStore")) {
                beans.add(Class.forName(name, false, quests.getClass().getClassLoader()));
            }
            var repository = Class.forName("org.quartz.impl.SchedulerRepository", true, quests.getClass().getClassLoader());
            var scheduler = RuntimeAccess.call(RuntimeAccess.call(repository, "getInstance"), "lookup", "DefaultQuartzScheduler");
            if (scheduler != null) waitFor(CompletableFuture.runAsync(() -> unchecked(() -> RuntimeAccess.call(scheduler, "shutdown", true))));
        }
        await(() -> Aurora.getUserManager().getActiveLoads() == 0 && Aurora.getUserManager().getActiveOperations() == 0 && !Aurora.getUserManager().isSaveTaskRunning()
                && runningTasks.stream().noneMatch(HotReload::running), "Player storage or plugin callbacks are still active");
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (var id : ids) futures.add(onPlayer(plugman, id, player -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof AuroraMenu) player.closeInventory();
            Aurora.getMenuManager().cleanInventory(player, Aurora.getMenuManager().getDupeFixer().getMarker());
            if (quests != null) unchecked(() -> RuntimeAccess.call(RuntimeAccess.call(quests, "getProfileManager"), "destroyProfile", id));
            if (main) {
                var wg = Aurora.getExpansionManager().getExpansion(WorldGuardExpansion.class);
                if (wg != null) wg.detachPlayer(player);
            }
        }, 1));
        waitFor(CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)));
        futures.clear();
        var owners = Set.copyOf(pair);
        for (var id : ids) futures.add(onPlayer(plugman, id, player -> unchecked(() -> EntityTaskDrain.cancel(player, owners)), 4));
        waitFor(CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)));
        threads.addAll(UnloadReferences.threads());
        UnloadReferences.clearContexts(pair, threads);
        Aurora.getUserManager().saveForHotUnload();
        if (main) {
            Aurora.getUserManager().getStorage().dispose();
            var boards = Aurora.getExpansionManager().getExpansion(LeaderboardExpansion.class);
            if (!Aurora.getLibConfig().getStorageType().equals("mysql")) boards.dispose();
            plugin.markHotUnloadSaved();
        }
        var files = new HashMap<String, String>();
        for (var old : pair) files.put(old.getName(), ((File) RuntimeAccess.get(old, "file")).getName().replaceFirst("\\.jar$", ""));
        CommandTreeGate.run(plugman, () -> {
            for (var old : pair) {
                if (old != plugin && !operation.equals("disable")) detach(old);
                allowedUnload = old;
                disablingOnly = operation.equals("disable");
                try { unchecked(() -> {
                    var wrapped = RuntimeAccess.call(manager, "getPluginByName", old.getName());
                    var result = RuntimeAccess.call(manager, operation.equals("disable") ? "disable" : "unload", wrapped);
                    if (!(boolean) RuntimeAccess.call(result, "success")) throw new IllegalStateException("PlugMan rejected " + old.getName());
                    if (!operation.equals("disable")) removeGuard(old);
                    UnloadReferences.clearHelp(Set.of(old.getClass().getClassLoader()));
                    if (!operation.equals("disable") && Bukkit.getPluginManager().getPlugin(old.getName()) != null) throw new IllegalStateException("Plugin remains registered: " + old.getName());
                }); } finally { allowedUnload = null; disablingOnly = false; }
            }
            if (Set.of("reload", "restart", "enable").contains(operation)) {
                var loading = new ArrayList<>(pair); Collections.reverse(loading);
                for (var old : loading) unchecked(() -> {
                    var result = RuntimeAccess.call(manager, "load", files.get(old.getName()));
                    if (!(boolean) RuntimeAccess.call(result, "success")) throw new IllegalStateException("Failed to load " + old.getName());
                });
            }
            if (!main) {
                Aurora.setDisabling(false);
                Aurora.getUserManager().resumeAfterHotUnload();
                paused = false;
            }
        });
        if (Set.of("reload", "restart", "enable").contains(operation)) await(() -> {
            for (var old : pair) {
                var current = Bukkit.getPluginManager().getPlugin(old.getName());
                if (current == null || !current.isEnabled()) return false;
                try {
                    if (old.getName().equals("AuroraQuests") && !(boolean) RuntimeAccess.call(current, "isHotReloadReady")) return false;
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not verify restored plugin", failure); }
            }
            return isReadyForCurrentMain();
        }, "Replacement commands or online player profiles did not become ready");
        waitFor(UnloadReferences.flushBeans(beans, groups));
        plugin.getLogger().info("Hot unload completed: " + target.getName() + "; operation=" + operation);
    }

    private static boolean isReadyForCurrentMain() {
        try {
            var current = Bukkit.getPluginManager().getPlugin("Aurora");
            return current != null && current.isEnabled()
                    && (boolean) RuntimeAccess.call(RuntimeAccess.call(current, "getHotReload"), "isReady");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not verify restored Aurora", failure); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(AsyncPlayerPreLoginEvent event) {
        if (paused && !bridgeActive()) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Aurora 更新处理中，请稍后再试。");
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if (paused && event.getView().getTopInventory().getHolder(false) instanceof AuroraMenu) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if (paused && event.getView().getTopInventory().getHolder(false) instanceof AuroraMenu) event.setCancelled(true);
    }

    @EventHandler public void enabled(PluginEnableEvent event) {
        if (Set.of("Aurora", "AuroraQuests").contains(event.getPlugin().getName()) && !bridgeActive()) guard(event.getPlugin());
    }

    @EventHandler public void disabled(PluginDisableEvent event) {
        if (!disablingOnly) removeGuard(event.getPlugin());
        if (event.getPlugin().getName().equals("AuroraQuests") && !Bukkit.isStopping() && bridgeActive()) detach(event.getPlugin());
        if (event.getPlugin().getName().equals("AuroraHotSwap")) {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                guard(plugin);
                var quests = Bukkit.getPluginManager().getPlugin("AuroraQuests");
                if (quests != null && quests.isEnabled()) guard(quests);
            });
        }
    }

    private void guard(Plugin guarded) {
        var plugman = Bukkit.getPluginManager().getPlugin("PlugManX");
        if (plugman == null) return;
        try {
            var api = Class.forName("bukkit.com.rylinaux.plugman.api.PlugManAPI", true, plugman.getClass().getClassLoader());
            var type = Class.forName("bukkit.com.rylinaux.plugman.api.GentleUnload", true, plugman.getClass().getClassLoader());
            var callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
                case "askingForGentleUnload" -> allowedUnload == guarded;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Aurora prepared unload guard";
                default -> throw new UnsupportedOperationException(method.getName());
            });
            if ((boolean) RuntimeAccess.call(api, "pleaseAddMeToGentleUnload", guarded, callback)) guards.put(guarded, callback);
        } catch (ReflectiveOperationException failure) { plugin.getLogger().log(Level.WARNING, "Could not register PlugMan unload guard", failure); }
    }

    @SuppressWarnings("unchecked") private void removeGuard(Plugin guarded) {
        var expected = guards.remove(guarded);
        var plugman = Bukkit.getPluginManager().getPlugin("PlugManX");
        if (expected == null || plugman == null) return;
        unchecked(() -> {
            var api = Class.forName("bukkit.com.rylinaux.plugman.api.PlugManAPI", false, plugman.getClass().getClassLoader());
            ((Map<Plugin, Object>) RuntimeAccess.get(api, "gentleUnloads")).remove(guarded, expected);
        });
    }

    public void close() {
        for (var guarded : new ArrayList<>(guards.keySet())) removeGuard(guarded);
    }

    private CompletableFuture<Void> onPlayer(Plugin owner, UUID id, Consumer<Player> action, long delay) {
        var done = new CompletableFuture<Void>();
        var player = Bukkit.getPlayer(id);
        if (player == null) { done.complete(null); return done; }
        try {
            var task = player.getScheduler().runDelayed(owner, ignored -> {
                try { if (player.isOnline()) action.accept(player); done.complete(null); }
                catch (Throwable failure) { done.completeExceptionally(failure); }
            }, () -> done.complete(null), delay);
            if (task == null) done.complete(null);
        } catch (Throwable failure) { done.completeExceptionally(failure); }
        return done;
    }

    private CompletableFuture<Void> global(Plugin owner, Runnable action) {
        var done = new CompletableFuture<Void>();
        try { Bukkit.getGlobalRegionScheduler().run(owner, task -> {
            try { action.run(); done.complete(null); }
            catch (Throwable failure) { done.completeExceptionally(failure); }
        }); } catch (Throwable failure) { done.completeExceptionally(failure); }
        return done;
    }

    private static void await(java.util.function.BooleanSupplier ready, String error) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new TimeoutException(error);
            Thread.sleep(25);
        }
    }

    private static void waitFor(CompletableFuture<?> future) throws Exception { future.get(30, TimeUnit.SECONDS); }
    private static boolean running(ScheduledTask task) {
        return task.getExecutionState() == ScheduledTask.ExecutionState.RUNNING
                || task.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED_RUNNING;
    }
    private void reply(CommandSender sender, String message) {
        var plugman = Bukkit.getPluginManager().getPlugin("PlugManX");
        reply(sender instanceof Player player ? player.getUniqueId() : null, plugman == null ? plugin : plugman, message);
    }
    private void reply(UUID playerId, Plugin owner, String message) {
        if (playerId == null) { plugin.getLogger().info(message); return; }
        var player = Bukkit.getPlayer(playerId);
        if (player != null) player.getScheduler().execute(owner, () -> player.sendMessage(message), null, 1);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void unchecked(Checked action) {
        try { action.run(); }
        catch (Exception failure) { throw new IllegalStateException(failure.getMessage(), failure); }
    }
}

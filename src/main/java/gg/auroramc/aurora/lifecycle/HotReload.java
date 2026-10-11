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
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Prepares ordinary PlugMan commands while the plugins can still schedule player-owned cleanup. */
public final class HotReload implements Listener {
    private final Aurora plugin;
    private final Map<CompletableFuture<Void>, Plugin> commandChanges = new ConcurrentHashMap<>();
    private final Map<Plugin, CompletableFuture<Void>> commandResults = new ConcurrentHashMap<>();
    private final Map<Plugin, Object> guards = new IdentityHashMap<>();
    private volatile boolean busy;
    private volatile boolean paused;
    private volatile Plugin allowedUnload;
    private boolean disablingOnly;
    private volatile boolean closed;
    // Keep names only: a failed update must not pin retired plugin instances/loaders.
    private volatile String interruptedTarget;
    private volatile String interruptedQuestsFile;
    private volatile String stage = "未开始";

    public HotReload(Aurora plugin) {
        this.plugin = plugin;
        releaseInitializationTrace(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void releaseInitializationTrace(Plugin loaded) { UnloadReferences.releaseInitializationTrace(loaded); }

    public boolean isPaused() { return paused; }

    public boolean isReady() {
        checkCommandFailures();
        return commandChanges.isEmpty() && Aurora.getUserManager().getActiveLoads() == 0
                && !Aurora.getUserManager().hasUnreadyOnlineUsers();
    }

    public CompletableFuture<Void> changeCommands(Runnable change) {
        // The existing Main/Quests callers pass method references declared by their own
        // plugin classes. Wrapped/library callbacks can use the explicit-owner overload.
        return changeCommands(JavaPlugin.getProvidingPlugin(change.getClass()), change);
    }

    public CompletableFuture<Void> changeCommands(Plugin owner, Runnable change) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(change);
        if (closed || !owner.isEnabled()) return CompletableFuture.failedFuture(new CancellationException("Command registration owner is stopped"));
        var result = new CompletableFuture<Void>();
        commandChanges.put(result, owner);
        commandResults.put(owner, result);
        result.whenComplete((value, failure) -> commandChanges.remove(result));
        // A disable may race with publication above. Do not leave its callback or
        // plugin reference pending if close()/PluginDisableEvent already ran.
        if (closed || !owner.isEnabled()) {
            commandResults.remove(owner, result);
            result.completeExceptionally(new CancellationException("Command registration owner is stopped"));
            return result;
        }
        Runnable guarded = () -> {
            if (!closed && owner.isEnabled() && !result.isDone()) change.run();
        };
        Runnable register = () -> {
            try {
                if (CommandTreeGate.isStartupThread()) guarded.run();
                else CommandTreeGate.run(plugin, guarded);
                result.complete(null);
            }
            catch (Throwable failure) {
                result.completeExceptionally(failure);
                plugin.getLogger().log(Level.SEVERE, "Failed to update command registrations", failure);
            }
        };
        try {
            // Cold startup has no player command readers and owns command-map initialization.
            // Do not start a runtime timeout while the global scheduler is not ticking yet.
            if (CommandTreeGate.isStartupThread()) register.run();
            else Bukkit.getAsyncScheduler().runNow(plugin, task -> register.run());
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            plugin.getLogger().log(Level.SEVERE, "Failed to schedule command registrations", failure);
        }
        return result;
    }

    private void checkCommandFailures() {
        for (var result : commandResults.values()) if (result.isCompletedExceptionally()) {
            try { result.join(); }
            catch (CompletionException | CancellationException failure) {
                throw new IllegalStateException("Command registration failed", failure instanceof CompletionException ? failure.getCause() : failure);
            }
        }
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
        boolean recoveryCommand = owner == plugin && parts.length == 2 && parts[1].equalsIgnoreCase("recover");
        if (owner != null && Set.of("Aurora", "AuroraQuests").contains(owner.getName()) && paused && !recoveryCommand) {
            reply(sender, "Aurora 更新尚未完成，任务暂时不可操作；管理员请查看控制台并使用 /aurora recover 恢复。");
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
                reportFailure(failure);
                reply(senderId, owner, "Aurora 更新停止，阶段：" + stage + "。修复报错原因后执行 /aurora recover；不要反复卸载。");
            } finally { busy = false; }
        });
        return true;
    }

    public void recover(CommandSender sender) {
        if (!sender.hasPermission("aurora.core.admin.recover")) { reply(sender, "没有恢复权限。"); return; }
        if (bridgeActive()) { reply(sender, "AuroraHotSwap 正在接管，请先结束其操作。"); return; }
        synchronized (this) {
            if (busy) { reply(sender, "Aurora 正在处理更新，请等待结果。"); return; }
            if (!paused) { reply(sender, "Aurora 没有卡在更新保护状态，无需恢复。"); return; }
            if (closed || interruptedTarget == null) { reply(sender, "没有可恢复的更新记录，请保留控制台日志。"); return; }
            busy = true;
        }
        var senderId = sender instanceof Player player ? player.getUniqueId() : null;
        try { Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            var plugman = Bukkit.getPluginManager().getPlugin("PlugManX");
            try {
                if (plugman == null || !plugman.isEnabled()) throw new IllegalStateException("PlugManX is unavailable");
                if ((boolean) RuntimeAccess.get(plugin, "hotUnloadSaved")) throw new IllegalStateException("Aurora storage was already closed; maintenance restart is required");
                var targetName = interruptedTarget;
                stage = "恢复前等待数据操作结束";
                await(() -> Aurora.getUserManager().getActiveLoads() == 0 && Aurora.getUserManager().getActiveOperations() == 0
                        && !Aurora.getUserManager().isSaveTaskRunning(), "Player data operations are still running");
                // This also persists retained leaderboard values if their plugin is absent.
                stage = "恢复前保存保留数据";
                Aurora.getUserManager().saveForHotUnload();
                if (Bukkit.getPluginManager().getPlugin("AuroraQuests") == null && interruptedQuestsFile != null) {
                    stage = "重新加载任务插件";
                    CommandTreeGate.run(plugin, () -> unchecked(() -> {
                        var type = Class.forName("core.com.rylinaux.plugman.plugins.PluginManager", true, plugman.getClass().getClassLoader());
                        var manager = RuntimeAccess.call(RuntimeAccess.call(plugman, "getServiceRegistry"), "get", type);
                        var result = RuntimeAccess.call(manager, "load", interruptedQuestsFile);
                        if (!(boolean) RuntimeAccess.call(result, "success")) throw new IllegalStateException("Restore the Quests JAR: " + interruptedQuestsFile + ".jar");
                    }));
                }
                var target = Bukkit.getPluginManager().getPlugin(targetName);
                if (target == null) throw new IllegalStateException("Recovery target is absent: " + targetName);
                // Re-run native draining, strict save, reload and readiness verification.
                // This supports an enabled Quests instance whose profiles/tasks were already stopped.
                perform(plugman, target, "reload", task);
                reply(senderId, plugman, "AURORA_RECOVERY_SUCCESS：更新恢复完成，玩家可进服，任务及后台保存已恢复。");
            } catch (Throwable failure) { reportFailure(failure); }
            finally { busy = false; }
        }); } catch (RuntimeException failure) { busy = false; throw failure; }
    }

    private void reportFailure(Throwable failure) {
        plugin.getLogger().log(Level.SEVERE, "AURORA_UPDATE_FAILED stage=" + stage + "; protection=" + paused
                + ". 保留数据，未强行解除保护。修复原因后在控制台执行 aurora recover；阶段若显示存储已关闭则需维护重启。", failure);
    }

    private void perform(Plugin plugman, Plugin target, String operation, ScheduledTask coordinator) throws Exception {
        if (Runtime.version().feature() != 25 || !Set.of("26.2-DEV-9daa83b (MC: 26.2)", "26.2-DEV-a8f1d54 (MC: 26.2)").contains(Bukkit.getVersion())) throw new IllegalStateException("Unverified server version: " + Bukkit.getVersion());
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
        checkCommandFailures();
        var files = new HashMap<String, String>();
        for (var old : pair) files.put(old.getName(), ((File) RuntimeAccess.get(old, "file")).getName().replaceFirst("\\.jar$", ""));
        interruptedTarget = target.getName();
        interruptedQuestsFile = files.get("AuroraQuests");
        stage = "暂停任务并等待回调";
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
        stage = "保存玩家数据及排行榜";
        Aurora.getUserManager().saveForHotUnload();
        if (main) {
            Aurora.getUserManager().getStorage().dispose();
            var boards = Aurora.getExpansionManager().getExpansion(LeaderboardExpansion.class);
            if (!Aurora.getLibConfig().getStorageType().equals("mysql")) boards.dispose();
            plugin.markHotUnloadSaved();
        }
        stage = main ? "存储已关闭，卸载及清理插件引用" : "卸载及清理插件引用";
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
                stage = "加载插件";
                var loading = new ArrayList<>(pair); Collections.reverse(loading);
                for (var old : loading) unchecked(() -> {
                    var result = RuntimeAccess.call(manager, "load", files.get(old.getName()));
                    if (!(boolean) RuntimeAccess.call(result, "success")) throw new IllegalStateException("Failed to load " + old.getName());
                });
            }
        });
        stage = "检查玩家资料和指令";
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
        if (!main) {
            waitFor(global(plugman, () -> {
                Aurora.setDisabling(false);
                Aurora.getUserManager().resumeAfterHotUnload();
                paused = false;
            }));
        }
        interruptedTarget = null;
        interruptedQuestsFile = null;
        stage = "已完成";
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
        clearCommandChanges(event.getPlugin());
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
        closed = true;
        for (var owner : new ArrayList<>(commandResults.keySet())) clearCommandChanges(owner);
        for (var guarded : new ArrayList<>(guards.keySet())) removeGuard(guarded);
    }

    private void clearCommandChanges(Plugin owner) {
        commandResults.remove(owner);
        for (var entry : commandChanges.entrySet()) if (entry.getValue() == owner) {
            entry.getKey().completeExceptionally(new CancellationException("Command registration owner is stopped"));
        }
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

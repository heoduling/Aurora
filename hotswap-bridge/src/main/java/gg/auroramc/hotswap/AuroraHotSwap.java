package gg.auroramc.hotswap;

import bukkit.com.rylinaux.plugman.PlugManBukkit;
import bukkit.com.rylinaux.plugman.api.PlugManAPI;
import core.com.rylinaux.plugman.plugins.PluginManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** One coordinator owns the pair. It has no Aurora dependency or background polling task. */
public final class AuroraHotSwap extends JavaPlugin implements Listener {
    private volatile Job job;
    private volatile boolean failed;
    private volatile Plugin unloading;
    private volatile String status = "Ready";
    private final Set<Plugin> guarded = ConcurrentHashMap.newKeySet();
    private final List<WeakReference<ClassLoader>> retired = new CopyOnWriteArrayList<>();
    private final List<WeakReference<Plugin>> retiredPlugins = new CopyOnWriteArrayList<>();
    private final Map<Object, CompletableFuture<Void>> closedMenus = Collections.synchronizedMap(new WeakHashMap<>());

    @Override public void onEnable() {
        try { Files.createDirectories(getDataFolder().toPath().resolve("incoming")); }
        catch (IOException e) { throw new IllegalStateException(e); }
        Bukkit.getPluginManager().registerEvents(this, this);
        guardCurrent();
        guard(this);
    }
    @Override public void onDisable() {
        failed = true;
        for (var plugin : guarded) removeGuard(plugin);
        guarded.clear();
    }
    @SuppressWarnings("unchecked") private void removeGuard(Plugin plugin) {
        try {
            // 3.1.0-Beta.2's public getter returns a defensive copy, not the live registrations.
            var live = (Map<Plugin, ?>) Access.get(PlugManAPI.class, "gentleUnloads");
            var guard = live.get(plugin);
            if (guard != null && guard.getClass().getClassLoader() == getClass().getClassLoader()) live.remove(plugin);
            else if (guard != null) throw new IllegalStateException("Unload guard ownership changed: " + plugin.getName());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported PlugMan guard registry", failure); }
    }
    private void guard(Plugin plugin) {
        if (plugin == null || !guarded.add(plugin)) return;
        if (!PlugManAPI.pleaseAddMeToGentleUnload(plugin, () -> plugin == this ? job == null && !failed : unloading == plugin)) {
            guarded.remove(plugin);
            throw new IllegalStateException("An existing unload guard owns " + plugin.getName());
        }
    }
    private void guardCurrent() { guard(Bukkit.getPluginManager().getPlugin("Aurora")); guard(Bukkit.getPluginManager().getPlugin("AuroraQuests")); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("aurora.hotswap.admin")) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("gc")) {
            Bukkit.getAsyncScheduler().runNow(this, task -> { System.gc(); reply(sender, memoryStatus()); });
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("apply")) {
            reply(sender, status + "; " + memoryStatus() + ". Use /aurorahotswap apply after placing both JARs in AuroraHotSwap/incoming.");
            return true;
        }
        synchronized (this) {
            if (job != null || failed) { reply(sender, status); return true; }
            job = new Job();
        }
        reply(sender, "Checking the two replacement JARs. Existing menus will close; players can stay connected.");
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            var current = job;
            try {
                current.prepare();
                current.run();
                status = "Completed: online users restored";
                getLogger().info(status);
            } catch (Throwable e) {
                failed = current.committed;
                status = failed ? "Failed after preparation began; restart the server before using Aurora" : "Rejected without unloading: " + e.getMessage();
                getLogger().log(java.util.logging.Level.SEVERE, status, e);
            } finally {
                current.aborted = true;
                job = null; // No retired plugins, reflective Methods, executors or users remain in coordinator state.
            }
        });
        return true;
    }
    private String memoryStatus() {
        return "retired loaders retained=" + retired.stream().filter(r -> r.get() != null).count()
                + ", retired plugin instances retained=" + retiredPlugins.stream().filter(r -> r.get() != null).count();
    }
    private void reply(CommandSender sender, String text) {
        if (sender instanceof Player player) player.getScheduler().run(this, task -> player.sendMessage(text), null);
        else sender.sendMessage(text);
    }
    private boolean destructive(String command) {
        var parts = command.trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length < 2 || !Set.of("plugman", "plm", "plugmanx:plugman", "plugmanx:plm").contains(parts[0])) return false;
        if (!Set.of("load", "unload", "reload", "restart", "enable", "disable").contains(parts[1])) return false;
        if (parts.length == 3 && parts[2].equals("aurorahotswap") && job == null && !failed) return false;
        return parts.length < 3 || parts[2].equals("all") || parts[2].startsWith("aurora");
    }
    private boolean pairCommand(String text) {
        var root = text.trim().replaceFirst("^/", "").split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        var command = Bukkit.getCommandMap().getCommand(root);
        return command instanceof PluginIdentifiableCommand owned
                && Set.of("Aurora", "AuroraQuests").contains(owned.getPlugin().getName());
    }
    @EventHandler(priority = EventPriority.LOWEST) public void playerCommand(PlayerCommandPreprocessEvent event) {
        if (destructive(event.getMessage()) || ((job != null || failed) && pairCommand(event.getMessage()))) {
            event.setCancelled(true); reply(event.getPlayer(), "Use /aurorahotswap apply to replace the Aurora pair safely.");
        }
    }
    @EventHandler(priority = EventPriority.LOWEST) public void consoleCommand(ServerCommandEvent event) {
        if (destructive(event.getCommand()) || ((job != null || failed) && pairCommand(event.getCommand()))) {
            event.setCancelled(true); reply(event.getSender(), "Use aurorahotswap apply to replace the Aurora pair safely.");
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void login(AsyncPlayerPreLoginEvent event) {
        if (job != null || failed) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Aurora maintenance in progress; try again shortly.");
    }
    private boolean paused() { var current = job; return failed || current != null && current.committed; }
    private boolean auroraMenu(Object holder) {
        if (holder == null) return false;
        for (var type = holder.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("gg.auroramc.aurora.api.menu.AuroraMenu")) return true;
        }
        return false;
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if (paused() && auroraMenu(event.getView().getTopInventory().getHolder(false))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if (paused() && auroraMenu(event.getView().getTopInventory().getHolder(false))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void close(InventoryCloseEvent event) throws Exception {
        var current = job; var holder = event.getInventory().getHolder(false);
        if (current != null && current.committed && current.menuClass.isInstance(holder)) closeOnce(holder, event);
        else if (failed && auroraMenu(holder)) {
            var old = Bukkit.getPluginManager().getPlugin("Aurora");
            if (old != null && (boolean) Access.call(old.getClass(), "isDisabling")) closeOnce(holder, event);
        }
    }
    private void closeOnce(Object holder, InventoryCloseEvent event) throws Exception {
        var completion = new CompletableFuture<Void>();
        synchronized (closedMenus) {
            var existing = closedMenus.putIfAbsent(holder, completion);
            if (existing != null) {
                if (!existing.isDone()) throw new IllegalStateException("Reentrant close callback has not finished");
                existing.getNow(null); return;
            }
        }
        try { Access.call(holder, "handleEvent", event); completion.complete(null); }
        catch (Exception failure) { completion.completeExceptionally(failure); throw failure; }
    }

    private final class Job {
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        private volatile boolean aborted;
        private volatile boolean committed;
        private Plugin aurora, quests;
        private Path oldAurora, oldQuests, stagedAurora, stagedQuests, archive;
        private Object users, boards;
        private Class<?> menuClass;
        private List<Player> players;
        private Set<ThreadPoolExecutor> metrics;
        private List<ScheduledTask> asyncTasks;
        private Set<Thread> commandThreads;
        private Set<ThreadGroup> commandGroups;
        private final List<Class<?>> quartzBeans = new ArrayList<>();
        private final Set<HandlerList> ownedHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        private <T> T waitFor(CompletableFuture<T> future) throws Exception {
            return future.get(Math.min(15_000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))), TimeUnit.MILLISECONDS);
        }
        private CompletableFuture<Void> global(Throwing action) {
            var done = new CompletableFuture<Void>();
            Bukkit.getGlobalRegionScheduler().run(AuroraHotSwap.this, task -> {
                try { check(); action.run(); done.complete(null); }
                catch (Throwable e) { done.completeExceptionally(e); }
            }); return done;
        }
        private void check() { if (aborted || System.nanoTime() > deadline) throw new IllegalStateException("Preparation timed out"); }
        private PluginManager manager() { return PlugManBukkit.getInstance().getServiceRegistry().get(PluginManager.class); }
        private void prepare() throws Exception {
            if (Runtime.version().feature() != 25 || !Bukkit.getVersion().equals("26.2-DEV-9daa83b (MC: 26.2)")) {
                throw new IllegalStateException("Requires the tested Shiroha 26.2 core 9daa83b and Java 25");
            }
            commandThreads = LegacyCommands.threads(); // Validate the legacy virtual-thread layout before preparation.
            commandGroups = new HashSet<>();
            for (var thread : commandThreads) if (thread.getThreadGroup() != null) commandGroups.add(thread.getThreadGroup());
            if (!Bukkit.getAsyncScheduler().getClass().getName().equals("io.papermc.paper.threadedregions.scheduler.FoliaAsyncScheduler")) {
                throw new IllegalStateException("Untested async scheduler layout");
            }
            if (!PlugManBukkit.getInstance().getDescription().getVersion().equals("3.1.0-Beta.2")) throw new IllegalStateException("Requires the tested PlugManX 3.1.0-Beta.2");
            waitFor(global(() -> {
                aurora = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Aurora"), "Aurora missing");
                quests = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("AuroraQuests"), "AuroraQuests missing");
                if (!aurora.isEnabled() || !quests.isEnabled()) throw new IllegalStateException("Both old plugins must be enabled");
                for (var other : Bukkit.getPluginManager().getPlugins()) {
                    if (other == aurora || other == quests) continue;
                    var dependencies = new HashSet<>(other.getDescription().getDepend()); dependencies.addAll(other.getDescription().getSoftDepend());
                    if (!Collections.disjoint(dependencies, Set.of("Aurora", "AuroraQuests"))) throw new IllegalStateException("Unsupported Aurora dependent: " + other.getName());
                }
                players = new ArrayList<>(Bukkit.getOnlinePlayers());
            }));
            oldAurora = findActive(aurora); oldQuests = findActive(quests);
            for (var player : players) LegacyEntityTasks.validate(player);
            collectHandlers(oldAurora, aurora); collectHandlers(oldQuests, quests);
            for (var list : ownedHandlers) for (var listener : list.getRegisteredListeners()) {
                if (listener.getPlugin() != aurora && listener.getPlugin() != quests) throw new IllegalStateException("An external listener uses Aurora events: " + listener.getPlugin().getName());
            }
            validateLegacy(aurora, oldAurora, Set.of("b0556b4975cb8612efd341f07995e0ad25c0ec66eab1a63face461a59b429d5f", "687f096086c75616a5ad3a8bbdd3c42e5a699fe299117552e67c09a04affb49d"));
            validateLegacy(quests, oldQuests, Set.of("772a26c509419a7fabc566073299a7b12b326ba97ce767d8872a1438ead82326", "cd73773952407d0c223ceabc309d0dc1087df147e7623a44fe935f0fcfa935a6"));
            for (var name : List.of("org.quartz.simpl.SimpleThreadPool", "org.quartz.simpl.RAMJobStore")) {
                quartzBeans.add(Class.forName(name, false, quests.getClass().getClassLoader()));
            }
            users = Access.call(aurora.getClass(), "getUserManager");
            boards = Access.call(Access.call(aurora.getClass(), "getExpansionManager"), "getExpansion", Class.forName("gg.auroramc.aurora.expansions.leaderboard.LeaderboardExpansion", true, aurora.getClass().getClassLoader()));
            if ((boolean) Access.call(Access.get(users, "migrator"), "isMigrating")) throw new IllegalStateException("A storage migration is running");
            menuClass = Class.forName("gg.auroramc.aurora.api.menu.AuroraMenu", true, aurora.getClass().getClassLoader());
            for (var user : cachedUsers()) if (!(boolean) Access.call(user, "isLoaded") || !players.stream().anyMatch(p -> p.getUniqueId().equals(uuid(user)))) {
                throw new IllegalStateException("A player load/quit is still pending; retry when it finishes");
            }
            var incoming = getDataFolder().toPath().resolve("incoming").toRealPath();
            Path nextA = incoming.resolve("Aurora.jar").toRealPath(), nextQ = incoming.resolve("AuroraQuests.jar").toRealPath();
            if (!nextA.getParent().equals(incoming) || !nextQ.getParent().equals(incoming)) throw new IllegalStateException("Replacement path escaped incoming directory");
            validateNext(nextA, "Aurora"); validateNext(nextQ, "AuroraQuests");
            archive = getDataFolder().toPath().resolve("retired-jars").resolve(Long.toString(System.currentTimeMillis())); Files.createDirectories(archive);
            stagedAurora = archive.resolve("Aurora.next"); stagedQuests = archive.resolve("AuroraQuests.next");
            Files.copy(nextA, stagedAurora); Files.copy(nextQ, stagedQuests);
            metrics = LegacyMetrics.find(Set.of(aurora.getClass().getClassLoader(), quests.getClass().getClassLoader()));
            asyncTasks = new ArrayList<>();
            @SuppressWarnings("unchecked") var scheduled = (Set<ScheduledTask>) Access.get(Bukkit.getAsyncScheduler(), "tasks");
            for (var task : scheduled) if (task.getOwningPlugin() == aurora || task.getOwningPlugin() == quests) asyncTasks.add(task);
            // DriverManager and untracked JDBC executors must not retain a private legacy library loader.
            verifyJdbcOwnership();
            verifyMythicCaches();
            getLogger().info("Preflight passed: " + Bukkit.getVersion() + "; " + aurora.getDescription().getVersion() + "/" + quests.getDescription().getVersion() + "; bStats executors=" + metrics.size());
        }
        private void validateLegacy(Plugin plugin, Path path, Set<String> legacyHashes) throws Exception {
            try { plugin.getClass().getMethod("beginHotUnload"); }
            catch (NoSuchMethodException e) {
                if (!legacyHashes.contains(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))))) {
                    throw new IllegalStateException("Unsupported legacy binary: " + plugin.getName());
                }
            }
        }
        private Path findActive(Plugin plugin) throws Exception {
            Path root = getDataFolder().toPath().toAbsolutePath().getParent().toRealPath();
            List<Path> matches = new ArrayList<>();
            try (var paths = Files.list(root)) {
                for (var path : paths.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    var descriptor = descriptor(path);
                    if (plugin.getName().equals(descriptor.getString("name"))) matches.add(path.toRealPath());
                }
            }
            if (matches.size() != 1 || !matches.getFirst().getParent().equals(root)) throw new IllegalStateException("Expected one active JAR for " + plugin.getName());
            if (!plugin.getDescription().getVersion().equals(descriptor(matches.getFirst()).getString("version"))) throw new IllegalStateException("Active binary version differs from disk");
            return matches.getFirst();
        }
        private YamlConfiguration descriptor(Path path) throws Exception {
            try (var zip = new java.util.jar.JarFile(path.toFile())) {
                var entry = zip.getJarEntry("plugin.yml");
                if (entry == null) return new YamlConfiguration();
                return YamlConfiguration.loadConfiguration(new InputStreamReader(zip.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        private void validateNext(Path path, String name) throws Exception {
            var descriptor = descriptor(path);
            if (!name.equals(descriptor.getString("name")) || descriptor.getInt("aurora-hotswap-protocol") != 1) throw new IllegalStateException("Wrong replacement JAR or missing lifecycle protocol: " + name);
        }
        private void collectHandlers(Path jar, Plugin plugin) throws Exception {
            try (var zip = new java.util.jar.JarFile(jar.toFile())) {
                for (var entry : zip.stream().filter(e -> e.getName().startsWith("gg/auroramc/") && e.getName().contains("/api/event") && e.getName().endsWith("Event.class")).toList()) {
                    var type = Class.forName(entry.getName().replace('/', '.').replaceFirst("\\.class$", ""), false, plugin.getClass().getClassLoader());
                    try { ownedHandlers.add((HandlerList) type.getDeclaredMethod("getHandlerList").invoke(null)); }
                    catch (NoSuchMethodException abstractEvent) { }
                }
            }
        }
        @SuppressWarnings("unchecked") private void removeEmptyHandlers() throws Exception {
            // Bukkit's public getter clones this list. Remove only the old plugins' empty custom event lists.
            var lists = (List<HandlerList>) Access.get(HandlerList.class, "allLists");
            synchronized (lists) {
                for (var handler : ownedHandlers) {
                    if (handler.getRegisteredListeners().length != 0) throw new IllegalStateException("Old event listeners remain registered");
                    lists.remove(handler);
                }
            }
        }
        @SuppressWarnings("unchecked") private List<Object> cachedUsers() throws Exception {
            return new ArrayList<>(((Map<?, Object>) Access.call(Access.get(users, "cache"), "asMap")).values());
        }
        private UUID uuid(Object user) { try { return (UUID) Access.call(user, "getUniqueId"); } catch (Exception e) { throw new IllegalStateException(e); } }
        private void verifyJdbcOwnership() throws Exception {
            // These supplied binaries use the server's Connector/J. An unknown private driver is refused, never deregistered on behalf of another plugin.
            var field = java.sql.DriverManager.class.getDeclaredField("registeredDrivers");
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            var drivers = (List<?>) unsafe.getObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field));
            for (var registration : drivers) {
                var driver = LegacyMetrics.read(registration, "driver");
                var loader = driver.getClass().getClassLoader();
                if (loader == aurora.getClass().getClassLoader() || loader == quests.getClass().getClassLoader()
                        || loader == Access.get(aurora.getClass().getClassLoader(), "libraryLoader") || loader == Access.get(quests.getClass().getClassLoader(), "libraryLoader")) {
                    throw new IllegalStateException("A private JDBC driver needs an explicit ownership shutdown; migration refused");
                }
            }
        }
        private void verifyMythicCaches() throws ReflectiveOperationException {
            var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
            if (mythic == null || !mythic.isEnabled()) return;
            // Mythic's CustomDrop stores the AuroraItemDrop registered by MythicDropLoadEvent.
            // Its tables, mobs and skill modules keep these objects after Aurora unregisters listeners.
            // Inspect only Mythic-owned fields and their containers, without changing any drops.
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            var pending = new ArrayList<Object>(); pending.add(mythic);
            Set<ClassLoader> owners = Set.of(aurora.getClass().getClassLoader(), quests.getClass().getClassLoader());
            for (int i = 0; i < pending.size(); i++) {
                check();
                var value = pending.get(i);
                if (value == null || !visited.add(value)) continue;
                if (visited.size() > 200_000) throw new IllegalStateException("Mythic cache inspection exceeded its bound");
                var type = value.getClass();
                var loader = type.getClassLoader();
                if (loader != null && owners.contains(loader) || value instanceof Class<?> c && c.getClassLoader() != null && owners.contains(c.getClassLoader())) {
                    throw new IllegalStateException("MythicMobs retains an Aurora object; this integration requires a restart: " + type.getName());
                }
                if (value instanceof Map<?, ?> map) { pending.addAll(map.keySet()); pending.addAll(map.values()); }
                else if (value instanceof Collection<?> collection) pending.addAll(collection);
                else if (value instanceof Optional<?> optional) optional.ifPresent(pending::add);
                else if (value instanceof Object[] array) Collections.addAll(pending, array);
                else for (var current = type; current != null && current.getName().startsWith("io.lumine.mythic."); current = current.getSuperclass()) {
                    for (var field : current.getDeclaredFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                            field.setAccessible(true); pending.add(field.get(value));
                        }
                    }
                }
            }
        }
        private void run() throws Exception {
            retired.removeIf(reference -> reference.get() == null);
            retiredPlugins.removeIf(reference -> reference.get() == null);
            committed = true; status = "Preparing old plugins";
            waitFor(global(() -> {
                Access.set(aurora.getClass(), "disabling", true); Access.set(quests, "loaded", false);
                try { Access.call(aurora, "beginHotUnload"); Access.call(quests, "beginHotUnload"); } catch (NoSuchMethodException legacy) { }
                HandlerList.unregisterAll(quests); HandlerList.unregisterAll(aurora);
                for (var task : asyncTasks) task.cancel();
                Bukkit.getAsyncScheduler().cancelTasks(quests); Bukkit.getAsyncScheduler().cancelTasks(aurora);
                Bukkit.getGlobalRegionScheduler().cancelTasks(quests); Bukkit.getGlobalRegionScheduler().cancelTasks(aurora);
            }));
            LegacyMetrics.stop(metrics);
            var quartz = Class.forName("org.quartz.impl.StdSchedulerFactory", true, quests.getClass().getClassLoader());
            var scheduler = Access.call(quartz, "getDefaultScheduler");
            waitFor(CompletableFuture.runAsync(() -> { try { Access.call(scheduler, "shutdown", true); } catch (Exception e) { throw new CompletionException(e); } }));
            while (asyncTasks.stream().anyMatch(t -> t.getExecutionState() == ScheduledTask.ExecutionState.RUNNING || t.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED_RUNNING)) {
                check(); Thread.sleep(10);
            }
            try {
                while ((int) Access.call(users, "getActiveOperations") > 0) { check(); Thread.sleep(10); }
            } catch (NoSuchMethodException legacy) { }
            stopExternalHooks();
            status = "Closing old menus and quest listeners";
            for (var player : players) waitFor(onPlayer(player, () -> {
                var view = player.getOpenInventory(); var holder = view.getTopInventory().getHolder(false);
                if (menuClass.isInstance(holder)) {
                    var close = new InventoryCloseEvent(view);
                    player.closeInventory();
                    closeOnce(holder, close); // Includes closes caused by disconnecting while the old listeners are detached.
                }
                cleanMarkedItems(player);
                Access.call(Access.call(quests, "getProfileManager"), "destroyProfile", player.getUniqueId());
            }, 1));
            for (var player : players) waitFor(onPlayer(player, () -> {
                if (menuClass.isInstance(player.getOpenInventory().getTopInventory().getHolder(false))) throw new IllegalStateException("An old menu reopened during preparation");
                cleanMarkedItems(player);
                int cancelled = LegacyEntityTasks.cancel(player, Set.of(aurora, quests));
                if (cancelled > 0) getLogger().info("Cancelled pending entity tasks: " + cancelled + " for " + player.getUniqueId());
            }, 4));
            commandThreads.addAll(LegacyCommands.threads());
            int clearedContexts = LegacyCommands.clearContexts(List.of(aurora, quests), commandThreads);
            getLogger().info("Drained command contexts: " + clearedContexts);
            // Preserve loaded holders exactly as they are. Never migrate schemas, erase task records, or touch world data.
            status = "Saving progress";
            var loadedUsers = cachedUsers();
            var saveReason = Class.forName("gg.auroramc.aurora.api.user.storage.SaveReason", true, aurora.getClass().getClassLoader()).getField("QUIT").get(null);
            var storage = Access.get(users, "storage");
            int count = (int) Access.call(storage, "bulkSaveUsers", loadedUsers, saveReason);
            if (count != loadedUsers.size()) throw new IllegalStateException("Storage saved " + count + "/" + loadedUsers.size() + " players");
            Map<UUID, Collection<String>> dirty = new HashMap<>();
            for (var user : loadedUsers) dirty.put(uuid(user), ((Map<String, ?>) Access.call(user, "getDirtyLeaderboards")).keySet());
            waitFor((CompletableFuture<?>) Access.call(boards, "bulkUpdateUsers", dirty));
            Access.call(storage, "dispose");
            var boardStorage = Access.get(boards, "storage");
            if (boardStorage != storage) Access.call(boardStorage, "dispose");
            // The unchanged originals synchronously save in onDisable. After a verified save+close, avoid duplicate IO on a tick thread.
            Access.set(users, "storage", disposedProxy(storage, "gg.auroramc.aurora.api.user.storage.UserStorage"));
            Access.set(boards, "storage", disposedProxy(boardStorage, "gg.auroramc.aurora.expansions.leaderboard.storage.LeaderboardStorage"));
            retired.add(new WeakReference<>(aurora.getClass().getClassLoader())); retired.add(new WeakReference<>(quests.getClass().getClassLoader()));
            for (var plugin : List.of(aurora, quests)) {
                retiredPlugins.add(new WeakReference<>(plugin));
                var library = (ClassLoader) Access.get(plugin.getClass().getClassLoader(), "libraryLoader");
                if (library != null) retired.add(new WeakReference<>(library));
            }
            status = "Replacing both plugins";
            waitFor(global(() -> {
                unload(quests); unload(aurora); removeEmptyHandlers();
                LegacyCommands.clearHelp(Set.of(aurora.getClass().getClassLoader(), quests.getClass().getClassLoader()));
            }));
            waitFor(LegacyCommands.flushBeans(quartzBeans, commandGroups));
            Files.move(oldQuests, archive.resolve("AuroraQuests.old.jar")); Files.move(oldAurora, archive.resolve("Aurora.old.jar"));
            Path root = oldAurora.getParent();
            Files.move(stagedAurora, root.resolve("Aurora.jar")); Files.move(stagedQuests, root.resolve("AuroraQuests.jar"));
            waitFor(global(() -> { load("Aurora"); load("AuroraQuests"); guardCurrent(); }));
            status = "Restoring online players";
            for (var player : players) {
                boolean ready = false;
                for (int retry = 0; retry < 150; retry++) {
                    check(); var result = new CompletableFuture<Boolean>();
                    waitFor(onPlayer(player, () -> {
                        var a = Bukkit.getPluginManager().getPlugin("Aurora"); var q = Bukkit.getPluginManager().getPlugin("AuroraQuests");
                        var user = Access.call(Access.call(a.getClass(), "getUserManager"), "getUser", player.getUniqueId());
                        result.complete((boolean) Access.call(user, "isLoaded") && Access.call(Access.call(q, "getProfileManager"), "getProfile", player) != null);
                    }, 1));
                    if (result.getNow(false)) { ready = true; break; } Thread.sleep(100);
                }
                if (!ready) throw new IllegalStateException("Online player data was not restored: " + player.getUniqueId());
            }
        }
        private Object disposedProxy(Object storage, String iface) throws Exception {
            var type = Class.forName(iface, true, aurora.getClass().getClassLoader());
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "dispose", "bulkUpdateEntries", "updateEntry" -> null;
                    case "bulkSaveUsers" -> ((List<?>) args[0]).size();
                    case "toString" -> "Already saved and closed by AuroraHotSwap";
                    default -> throw new IllegalStateException("Storage is closed: " + method.getName());
                };
            });
        }
        private CompletableFuture<Void> onPlayer(Player player, Throwing action, long delay) {
            var done = new CompletableFuture<Void>();
            try {
                var scheduled = player.getScheduler().runDelayed(AuroraHotSwap.this, task -> {
                    try { check(); if (!player.isOnline()) throw new IllegalStateException("Player disconnected during migration"); action.run(); done.complete(null); }
                    catch (Throwable e) { done.completeExceptionally(e); }
                }, () -> done.completeExceptionally(new IllegalStateException("Player retired during migration")), delay);
                if (scheduled == null) done.completeExceptionally(new IllegalStateException("Player scheduler refused preparation"));
            } catch (Throwable e) { done.completeExceptionally(e); }
            return done;
        }
        private void cleanMarkedItems(Player player) {
            var key = NamespacedKey.fromString("aurora:aurora");
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                var item = player.getInventory().getItem(slot);
                if (marked(item, key)) player.getInventory().setItem(slot, null);
            }
            if (marked(player.getItemOnCursor(), key)) player.setItemOnCursor(null);
        }
        private boolean marked(ItemStack item, NamespacedKey key) { return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE); }
        private void stopExternalHooks() throws Exception {
            var lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (lp != null) {
                var loader = lp.getClass().getClassLoader();
                var provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader);
                var bus = Access.call(Access.call(provider, "get"), "getEventBus");
                var event = Class.forName("net.luckperms.api.event.user.UserDataRecalculateEvent", true, loader);
                var owners = Set.of(aurora.getClass().getClassLoader(), quests.getClass().getClassLoader());
                var drains = new ArrayList<CompletableFuture<Void>>();
                for (var sub : new ArrayList<>((Set<?>) Access.call(bus, "getSubscriptions", event))) {
                    var handler = Access.call(sub, "getHandler");
                    var handlerLoader = handler.getClass().getClassLoader();
                    if (handlerLoader == null || !owners.contains(handlerLoader)) continue;
                    Access.call(sub, "close");
                    // Legacy quest callbacks synchronize on their hook. Drain any invocation already
                    // admitted by LuckPerms before owner barriers/save, without blocking a tick thread.
                    for (var field : handler.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                        field.setAccessible(true); var captured = field.get(handler);
                        if (captured != null && captured.getClass().getName().equals("gg.auroramc.quests.hooks.luckperms.LuckPermsHook")) {
                            drains.add(CompletableFuture.runAsync(() -> { synchronized (captured) { } }));
                        }
                    }
                }
                for (var drain : drains) waitFor(drain);
            }
            var papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi != null) {
                var main = Class.forName("me.clip.placeholderapi.PlaceholderAPIPlugin", true, papi.getClass().getClassLoader());
                var manager = Access.call(Access.call(main, "getInstance"), "getLocalExpansionManager");
                var expansion = Access.call(manager, "getExpansion", "aurora");
                if (expansion != null && expansion.getClass().getClassLoader() == aurora.getClass().getClassLoader()) Access.call(expansion, "unregister");
            }
        }
        private void unload(Plugin plugin) throws Exception {
            unloading = plugin;
            try {
                var result = manager().unload(manager().getPluginByName(plugin.getName()));
                if (!result.success() || plugin.isEnabled() || Bukkit.getPluginManager().getPlugin(plugin.getName()) != null) throw new IllegalStateException("Unload failed: " + plugin.getName() + " " + result.messageId());
                removeGuard(plugin); guarded.remove(plugin);
            } finally { unloading = null; }
        }
        private void load(String name) {
            var result = manager().load(name);
            var plugin = Bukkit.getPluginManager().getPlugin(name);
            if (!result.success() || plugin == null || !plugin.isEnabled()) throw new IllegalStateException("Load failed: " + name + " " + result.messageId());
        }
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}

package gg.auroramc.aurora.lifecycle;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.IndexHelpTopic;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.ref.Reference;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** The supplied ACF binaries leave help factories and empty command stacks in core-owned objects. */
final class UnloadReferences {
    static void releaseInitializationTrace(Plugin plugin) {
        try {
            var state = RuntimeAccess.get(plugin.getClass().getClassLoader(), "pluginState");
            if (!(state instanceof IllegalStateException diagnostic) || !"Initial initialization".equals(diagnostic.getMessage())) {
                throw new IllegalStateException("Unsupported plugin initialization diagnostic");
            }
            ThreadLocalAccess.releaseBacktrace(diagnostic);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not release initialization backtrace", failure); }
    }

    static Set<Thread> threads() throws ReflectiveOperationException {
        var threads = new HashSet<>(Thread.getAllStackTraces().keySet());
        // getAllStackTraces excludes Java virtual threads. This exact Shiroha core owns them here.
        var regions = Class.forName("io.papermc.paper.threadedregions.TickRegions");
        var pool = RuntimeAccess.get(RuntimeAccess.call(regions, "getScheduler"), "scheduler");
        if (!pool.getClass().getName().equals("ca.spottedleaf.concurrentutil.scheduler.ContinuationStealingScheduledThreadPool")) {
            throw new IllegalStateException("Untested continuation scheduler layout");
        }
        for (var lane : (Set<?>) RuntimeAccess.get(pool, "lanes")) {
            addThread(threads, RuntimeAccess.get(lane, "virtualThread"));
            for (var member : (Set<?>) RuntimeAccess.get(lane, "members")) addThread(threads, RuntimeAccess.get(member, "stack"));
        }
        return threads;
    }
    private static void addThread(Set<Thread> threads, Object stack) throws ReflectiveOperationException {
        if (stack != null) threads.add((Thread) RuntimeAccess.call(stack, "thread"));
    }
    static int clearContexts(List<Plugin> plugins, Set<Thread> threads) throws ReflectiveOperationException {
        Set<Object> keys = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var plugin : plugins) {
            var prefix = plugin.getName().equals("Aurora") ? "gg.auroramc.aurora" : "gg.auroramc.quests";
            var manager = Class.forName(prefix + ".libs.acf.CommandManager", false, plugin.getClass().getClassLoader());
            keys.add(RuntimeAccess.get(manager, "commandOperationContext"));
        }
        var entries = new ArrayList<Reference<?>>();
        for (var thread : threads) {
            var map = ThreadLocalAccess.read(thread, "threadLocals");
            if (map == null) continue;
            for (var entry : (Object[]) ThreadLocalAccess.read(map, "table")) {
                if (!(entry instanceof Reference<?> reference) || !keys.contains(reference.get())) continue;
                var value = ThreadLocalAccess.read(entry, "value");
                if (!(value instanceof Stack<?> stack) || !stack.isEmpty()) {
                    throw new IllegalStateException("An old command context is still executing: " + thread.getName());
                }
                entries.add(reference);
            }
        }
        // After admission is stopped and owner barriers complete, invalidate only these empty,
        // identity-matched entries. Leave table links/size and every other plugin's locals alone;
        // ThreadLocalMap will expunge the stale weak key exactly as it does after GC.
        for (var entry : entries) {
            ThreadLocalAccess.clearValue(entry);
            entry.clear();
        }
        return entries.size();
    }
    @SuppressWarnings("unchecked") static void clearHelp(Set<ClassLoader> owners) throws ReflectiveOperationException {
        var help = Bukkit.getHelpMap();
        synchronized (help) {
            var factories = (Map<Class<?>, Object>) RuntimeAccess.get(help, "topicFactoryMap");
            factories.entrySet().removeIf(e -> owned(e.getKey().getClassLoader(), owners) || owned(e.getValue().getClass().getClassLoader(), owners));
            var topics = (Map<String, HelpTopic>) RuntimeAccess.get(help, "helpTopics");
            var roots = new ArrayList<>(topics.values());
            roots.add((HelpTopic) RuntimeAccess.get(help, "defaultTopic"));
            var visited = Collections.newSetFromMap(new IdentityHashMap<HelpTopic, Boolean>());
            for (var topic : roots) clearIndex(topic, owners, visited);
            var removed = Collections.newSetFromMap(new IdentityHashMap<HelpTopic, Boolean>());
            for (var topic : topics.values()) if (ownedTopic(topic, owners)) removed.add(topic);
            topics.values().removeIf(removed::contains);
        }
    }
    @SuppressWarnings("unchecked") static void clearIndex(HelpTopic topic, Set<ClassLoader> owners, Set<HelpTopic> visited) throws ReflectiveOperationException {
        if (topic == null || !visited.add(topic) || !(topic instanceof IndexHelpTopic)) return;
        var entries = (Collection<HelpTopic>) RuntimeAccess.get(topic, "allTopics");
        var retained = new ArrayList<HelpTopic>(entries.size());
        for (var child : entries) {
            if (!ownedTopic(child, owners)) { clearIndex(child, owners, visited); retained.add(child); }
        }
        // Read-only collections can reject removeIf even when no element matches.
        // Inspect children directly: an earlier interrupted pass may have removed their root entries.
        if (retained.size() != entries.size()) RuntimeAccess.set(topic, "allTopics", retained);
    }
    private static boolean ownedTopic(HelpTopic topic, Set<ClassLoader> owners) throws ReflectiveOperationException {
        if (owned(topic.getClass().getClassLoader(), owners)) return true;
        if (topic instanceof GenericCommandHelpTopic) {
            var command = RuntimeAccess.get(topic, "command");
            return owned(command.getClass().getClassLoader(), owners)
                    || command instanceof PluginIdentifiableCommand identified
                    && owned(identified.getPlugin().getClass().getClassLoader(), owners);
        }
        return false;
    }
    private static boolean owned(ClassLoader loader, Set<ClassLoader> owners) { return loader != null && owners.contains(loader); }
    static CompletableFuture<Void> flushBeans(List<Class<?>> beans, Set<ThreadGroup> groups) {
        var completions = new ArrayList<CompletableFuture<Void>>();
        // Introspector's public flush method clears only the caller's ThreadGroupContext.
        // Original cold startup and later region loading use different thread groups.
        for (var group : groups) {
            var done = new CompletableFuture<Void>(); completions.add(done);
            var thread = new Thread(group, () -> {
                try { for (var bean : beans) java.beans.Introspector.flushFromCaches(bean); done.complete(null); }
                catch (Throwable failure) { done.completeExceptionally(failure); }
            }, "Aurora-bean-cache");
            thread.setDaemon(true); thread.start();
        }
        return CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
    }
    private UnloadReferences() { }
}

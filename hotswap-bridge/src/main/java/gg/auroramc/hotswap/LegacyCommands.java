package gg.auroramc.hotswap;

import org.bukkit.Bukkit;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.IndexHelpTopic;
import org.bukkit.plugin.Plugin;

import java.lang.ref.Reference;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** The supplied ACF binaries leave help factories and empty command stacks in core-owned objects. */
final class LegacyCommands {
    static Set<Thread> threads() throws ReflectiveOperationException {
        var threads = new HashSet<>(Thread.getAllStackTraces().keySet());
        // getAllStackTraces excludes Java virtual threads. This exact Shiroha core owns them here.
        var regions = Class.forName("io.papermc.paper.threadedregions.TickRegions");
        var pool = Access.get(Access.call(regions, "getScheduler"), "scheduler");
        if (!pool.getClass().getName().equals("ca.spottedleaf.concurrentutil.scheduler.ContinuationStealingScheduledThreadPool")) {
            throw new IllegalStateException("Untested continuation scheduler layout");
        }
        for (var lane : (Set<?>) Access.get(pool, "lanes")) {
            addThread(threads, Access.get(lane, "virtualThread"));
            for (var member : (Set<?>) Access.get(lane, "members")) addThread(threads, Access.get(member, "stack"));
        }
        return threads;
    }
    private static void addThread(Set<Thread> threads, Object stack) throws ReflectiveOperationException {
        if (stack != null) threads.add((Thread) Access.call(stack, "thread"));
    }
    static int clearContexts(List<Plugin> plugins, Set<Thread> threads) throws ReflectiveOperationException {
        Set<Object> keys = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var plugin : plugins) {
            var prefix = plugin.getName().equals("Aurora") ? "gg.auroramc.aurora" : "gg.auroramc.quests";
            var manager = Class.forName(prefix + ".libs.acf.CommandManager", false, plugin.getClass().getClassLoader());
            keys.add(Access.get(manager, "commandOperationContext"));
        }
        var entries = new ArrayList<Reference<?>>();
        for (var thread : threads) {
            var map = LegacyMetrics.read(thread, "threadLocals");
            if (map == null) continue;
            for (var entry : (Object[]) LegacyMetrics.read(map, "table")) {
                if (!(entry instanceof Reference<?> reference) || !keys.contains(reference.get())) continue;
                var value = LegacyMetrics.read(entry, "value");
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
            LegacyMetrics.clearValue(entry);
            entry.clear();
        }
        return entries.size();
    }
    @SuppressWarnings("unchecked") static void clearHelp(Set<ClassLoader> owners) throws ReflectiveOperationException {
        var help = Bukkit.getHelpMap();
        synchronized (help) {
            var factories = (Map<Class<?>, Object>) Access.get(help, "topicFactoryMap");
            factories.entrySet().removeIf(e -> owned(e.getKey().getClassLoader(), owners) || owned(e.getValue().getClass().getClassLoader(), owners));
            var topics = (Map<String, HelpTopic>) Access.get(help, "helpTopics");
            var removed = Collections.newSetFromMap(new IdentityHashMap<HelpTopic, Boolean>());
            for (var topic : topics.values()) {
                if (owned(topic.getClass().getClassLoader(), owners)
                        || topic instanceof GenericCommandHelpTopic && owned(Access.get(topic, "command").getClass().getClassLoader(), owners)) removed.add(topic);
            }
            var indexes = new ArrayList<>(topics.values());
            indexes.add((HelpTopic) Access.get(help, "defaultTopic"));
            topics.values().removeIf(removed::contains);
            for (var topic : indexes) if (topic instanceof IndexHelpTopic) {
                ((Collection<HelpTopic>) Access.get(topic, "allTopics")).removeIf(removed::contains);
            }
        }
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
            }, "AuroraHotSwap-bean-cache");
            thread.setDaemon(true); thread.start();
        }
        return CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
    }
    private LegacyCommands() { }
}

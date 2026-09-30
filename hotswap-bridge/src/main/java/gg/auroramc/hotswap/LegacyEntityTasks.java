package gg.auroramc.hotswap;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Modifier;
import java.util.*;

/** The tested Folia core keeps cancelled delayed records until their original due tick. */
final class LegacyEntityTasks {
    private static Object scheduler(Player player) throws ReflectiveOperationException {
        var api = player.getScheduler();
        if (!api.getClass().getName().equals("io.papermc.paper.threadedregions.scheduler.FoliaEntityScheduler")) throw new IllegalStateException("Untested entity scheduler");
        return Access.get(Access.get(api, "entity"), "taskScheduler");
    }
    static void validate(Player player) throws ReflectiveOperationException {
        var scheduler = scheduler(player);
        Access.field(scheduler.getClass(), "stateLock");
        Access.field(scheduler.getClass(), "oneTimeDelayed");
        Access.field(scheduler.getClass(), "currentlyExecuting");
    }
    @SuppressWarnings("unchecked") static int cancel(Player player, Set<Plugin> owners) throws ReflectiveOperationException {
        if (!Bukkit.isOwnedByCurrentRegion(player)) throw new IllegalStateException("Wrong player owner for task cleanup");
        var scheduler = scheduler(player);
        int count = 0;
        synchronized (Access.get(scheduler, "stateLock")) {
            var delayed = (Map<?, List<?>>) Access.get(scheduler, "oneTimeDelayed");
            for (var lists = delayed.values().iterator(); lists.hasNext();) {
                var list = lists.next(); count += remove(list, owners);
                if (list.isEmpty()) lists.remove();
            }
            count += remove((Collection<?>) Access.get(scheduler, "currentlyExecuting"), owners);
        }
        return count;
    }
    private static int remove(Collection<?> records, Set<Plugin> owners) throws ReflectiveOperationException {
        int count = 0;
        for (var iterator = records.iterator(); iterator.hasNext();) {
            var record = iterator.next(); var run = Access.call(record, "run");
            if (run instanceof ScheduledTask task && owners.contains(task.getOwningPlugin())) {
                if (task.getExecutionState() == ScheduledTask.ExecutionState.RUNNING || task.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED_RUNNING) throw new IllegalStateException("An entity task is still running");
                task.cancel(); iterator.remove(); count++;
            } else if (run.getClass().getName().startsWith("io.papermc.paper.threadedregions.scheduler.FoliaEntityScheduler$$Lambda")) {
                for (var field : run.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !Plugin.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    if (owners.contains(field.get(run))) { iterator.remove(); count++; break; }
                }
            }
        }
        return count;
    }
    private LegacyEntityTasks() { }
}

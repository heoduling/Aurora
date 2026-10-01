package gg.auroramc.aurora.expansions.worldguard;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import gg.auroramc.aurora.api.dependency.Dep;
import gg.auroramc.aurora.api.dependency.DependencyManager;
import gg.auroramc.aurora.api.events.region.PlayerRegionEnterEvent;
import gg.auroramc.aurora.api.events.region.PlayerRegionLeaveEvent;
import gg.auroramc.aurora.api.expansions.AuroraExpansion;
import gg.auroramc.aurora.Aurora;
import gg.auroramc.aurora.lifecycle.RuntimeAccess;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.SessionManager;
import com.sk89q.worldguard.session.handler.Handler;
import com.google.common.cache.Cache;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WorldGuardExpansion implements AuroraExpansion, Listener {

    private final Map<UUID, Set<ProtectedRegion>> previousRegions = new ConcurrentHashMap<>();
    private final Map<UUID, Location> teleportMoves = new ConcurrentHashMap<>();
    private WorldGuardEntryAndLeaveHandler.Factory factory;
    private volatile boolean stopping;

    protected void onRegionUpdate(UUID playerUUID, Set<ProtectedRegion> newRegions) {
        if (stopping || Aurora.isDisabling()) return;
        var player = Bukkit.getPlayer(playerUUID);
        if (player == null) return;

        var oldRegions = previousRegions.get(playerUUID);
        var regions = new ArrayList<>(newRegions);
        if (regions.isEmpty()) {

            if (oldRegions != null && !oldRegions.isEmpty()) {
                previousRegions.remove(playerUUID);
                Bukkit.getPluginManager().callEvent(new PlayerRegionLeaveEvent(player, oldRegions.stream().toList()));
            }
            return;
        }

        var enterRegions = newRegions.stream().filter(r -> oldRegions == null || !oldRegions.contains(r)).toList();
        var leaveRegions = oldRegions == null ? null : oldRegions.stream().filter(r -> !newRegions.contains(r)).toList();

        previousRegions.put(playerUUID, newRegions);

        if (leaveRegions != null && !leaveRegions.isEmpty()) {
            Bukkit.getPluginManager().callEvent(new PlayerRegionLeaveEvent(player, leaveRegions));
        }
        if (!enterRegions.isEmpty()) {
            Bukkit.getPluginManager().callEvent(new PlayerRegionEnterEvent(player, enterRegions));
        }
    }

    protected void addTeleportMove(UUID playerUUID, Location location) {
        teleportMoves.put(playerUUID, location);
    }

    protected void removeTeleportMove(UUID playerUUID) {
        teleportMoves.remove(playerUUID);
    }

    protected boolean checkFreeMoveAfterTeleport(UUID playerUUID, Location location) {
        if (teleportMoves.containsKey(playerUUID)) {
            var oldLocation = teleportMoves.get(playerUUID);
            removeTeleportMove(playerUUID);
            if (oldLocation == null) return false;
            if (!oldLocation.isWorldLoaded()) return false;
            if (oldLocation.getWorld() != location.getWorld()) return false;
            return oldLocation.distance(location) <= 5;
        }
        return false;
    }

    @Override
    public void hook() {
        factory = WorldGuardEntryAndLeaveHandler.FACTORY(this);
        replaceFactory(true);
        pruneOfflineSessions();
        for (var player : Bukkit.getOnlinePlayers()) player.getScheduler().run(Aurora.getInstance(), task -> attachPlayer(player), null);
    }

    /** Publish a complete registry list; existing readers can finish traversing their old snapshot. */
    @SuppressWarnings("unchecked") private void replaceFactory(boolean register) {
        var manager = WorldGuard.getInstance().getPlatform().getSessionManager();
        try {
            synchronized (manager) {
                var old = (List<Handler.Factory<?>>) RuntimeAccess.get(manager, "handlers");
                var next = new ArrayList<>(old);
                if (register) next.removeIf(value -> value.getClass().getName().equals(factory.getClass().getName()));
                else next.removeIf(value -> value == factory);
                if (register) next.add(factory);
                RuntimeAccess.set(manager, "handlers", next);
                if (register) RuntimeAccess.set(manager, "hasCustom", true);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported WorldGuard session registry", failure); }
    }

    @SuppressWarnings("unchecked") private static Map<Class<?>, Handler> handlers(Session session) {
        try { return (Map<Class<?>, Handler>) RuntimeAccess.get(session, "handlers"); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported WorldGuard session", failure); }
    }

    private void attachPlayer(Player player) {
        if (stopping || !player.isOnline()) return;
        var local = WorldGuardPlugin.inst().wrapPlayer(player);
        var manager = WorldGuard.getInstance().getPlatform().getSessionManager();
        var session = manager.getIfPresent(local);
        if (session == null) return; // A newly created session will use the current factory.
        var map = handlers(session);
        map.keySet().removeIf(type -> type.getName().equals(WorldGuardEntryAndLeaveHandler.class.getName()));
        var handler = factory.create(session);
        session.register(handler);
        var location = BukkitAdapter.adapt(player.getLocation());
        handler.initialize(local, location, WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(location));
    }

    public void detachPlayer(Player player) {
        var session = WorldGuard.getInstance().getPlatform().getSessionManager().getIfPresent(WorldGuardPlugin.inst().wrapPlayer(player));
        if (session != null) handlers(session).keySet().removeIf(type -> type.getClassLoader() == getClass().getClassLoader());
        previousRegions.remove(player.getUniqueId());
        teleportMoves.remove(player.getUniqueId());
    }

    @SuppressWarnings("unchecked") private void pruneOfflineSessions() {
        try {
            var manager = WorldGuard.getInstance().getPlatform().getSessionManager();
            var sessions = (Cache<Object, Session>) RuntimeAccess.get(manager, "sessions");
            for (var entry : sessions.asMap().entrySet()) {
                var id = (UUID) RuntimeAccess.get(entry.getKey(), "uuid");
                if (Bukkit.getPlayer(id) == null && handlers(entry.getValue()).keySet().stream()
                        .anyMatch(type -> type.getName().equals(WorldGuardEntryAndLeaveHandler.class.getName()))) {
                    sessions.asMap().remove(entry.getKey(), entry.getValue());
                }
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported WorldGuard session cache", failure); }
    }

    public void stopNewSessions() {
        stopping = true;
        if (factory != null) { replaceFactory(false); pruneOfflineSessions(); factory = null; }
    }

    public void dispose() {
        stopNewSessions();
        previousRegions.clear();
        teleportMoves.clear();
    }

    @Override
    public boolean canHook() {
        return DependencyManager.hasDep(Dep.WORLDGUARD);
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent e) {
        previousRegions.remove(e.getPlayer().getUniqueId());
        teleportMoves.remove(e.getPlayer().getUniqueId());
    }
}

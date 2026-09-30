package gg.auroramc.aurora.expansions.region;

import gg.auroramc.aurora.Aurora;
import gg.auroramc.aurora.api.expansions.AuroraExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class RegionExpansion implements AuroraExpansion {
    private NamespacedKey createKey(Block block) {
        return createKey(block.getLocation());
    }

    private NamespacedKey createKey(Location location) {
        return new NamespacedKey("aurora", "placed_v2_" + (location.getBlockX() & 15)
                + "_" + location.getBlockY() + "_" + (location.getBlockZ() & 15));
    }

    private boolean hasLegacyMarker(PersistentDataContainer data, Location location) {
        var blockLocation = new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return data.has(new NamespacedKey("aurora", Integer.toHexString(blockLocation.hashCode())))
                || data.has(new NamespacedKey("aurora", Integer.toHexString(location.hashCode())));
    }

    public boolean isPlacedBlock(Block block) {
        return isPlacedBlock(block.getLocation());
    }

    public boolean isPlacedBlock(Location location) {
        var data = location.getChunk().getPersistentDataContainer();
        var placed = data.get(createKey(location), PersistentDataType.BYTE);
        return placed != null ? placed == 1 : hasLegacyMarker(data, location);
    }

    public void addPlacedBlock(Block block) {
        block.getChunk().getPersistentDataContainer().set(createKey(block), PersistentDataType.BYTE, (byte) 1);
    }

    public void addPlacedBlock(Location location) {
        location.getChunk().getPersistentDataContainer().set(createKey(location), PersistentDataType.BYTE, (byte) 1);
    }

    public void removePlacedBlock(Block block) {
        removePlacedBlock(block.getLocation());
    }

    public void removePlacedBlock(Location location) {
        var data = location.getChunk().getPersistentDataContainer();
        if (hasLegacyMarker(data, location)) {
            // A legacy hash can also belong to another position. Keep it, and override only this block.
            data.set(createKey(location), PersistentDataType.BYTE, (byte) 0);
        } else {
            data.remove(createKey(location));
        }
    }

    @Override
    public void hook() {
        var plugin = Aurora.getInstance();
        Bukkit.getPluginManager().registerEvents(new RegionBlockListener(plugin, this), plugin);
    }

    @Override
    public boolean canHook() {
        return true;
    }
}

package gg.auroramc.aurora.expansions.region;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

/** Compatibility storage: retain the exact old hash membership, including raw Location hashes. */
final class CompactBlockMarkers {
    // PDC array reads copy the entire array on the target core. Bound each copy to at most 1 KiB.
    static final int MAX_VALUES = 128;
    static final int MIN_VALUES = 4;
    static final NamespacedKey[] LEGACY = keys("placed_legacy_v3_");
    static final NamespacedKey[] CLEARED = keys("placed_clear_v3_");

    private CompactBlockMarkers() {}

    private static NamespacedKey[] keys(String prefix) {
        var keys = new NamespacedKey[256];
        for (int i = 0; i < keys.length; i++) keys[i] = new NamespacedKey("aurora", prefix + Integer.toHexString(i));
        return keys;
    }

    static int bucket(int hash) {
        int mixed = hash ^ (hash >>> 16);
        mixed *= 0x85ebca6b;
        return (mixed ^ (mixed >>> 13)) & 255;
    }

    static long coordinate(Location location) {
        return ((long) location.getBlockY() << 8) | ((location.getBlockX() & 15) << 4) | (location.getBlockZ() & 15);
    }

    static boolean hasLegacy(PersistentDataContainer data, int hash) {
        var key = LEGACY[bucket(hash)];
        if (!data.has(key, PersistentDataType.INTEGER_ARRAY)) return false;
        var hashes = data.get(key, PersistentDataType.INTEGER_ARRAY);
        return Arrays.binarySearch(hashes, hash) >= 0;
    }

    static boolean isCleared(PersistentDataContainer data, Location location) {
        long coordinate = coordinate(location);
        var key = CLEARED[(int) coordinate & 255];
        if (!data.has(key, PersistentDataType.LONG_ARRAY)) return false;
        return Arrays.binarySearch(data.get(key, PersistentDataType.LONG_ARRAY), coordinate) >= 0;
    }

    static Integer legacyHash(NamespacedKey key) {
        if (!key.getNamespace().equals("aurora")) return null;
        String text = key.getKey();
        if (text.isEmpty() || text.length() > 8 || (text.length() > 1 && text.charAt(0) == '0')) return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return null;
        }
        return Integer.parseUnsignedInt(text, 16);
    }

    static Long clearCoordinate(NamespacedKey key) {
        if (!key.getNamespace().equals("aurora") || !key.getKey().startsWith("placed_v2_")) return null;
        var parts = key.getKey().split("_", -1);
        if (parts.length != 5) return null;
        try {
            int x = Integer.parseInt(parts[2]), y = Integer.parseInt(parts[3]), z = Integer.parseInt(parts[4]);
            if (x < 0 || x > 15 || z < 0 || z > 15
                    || !key.getKey().equals("placed_v2_" + x + "_" + y + "_" + z)) return null;
            return ((long) y << 8) | (x << 4) | z;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static int archiveIndex(NamespacedKey key, NamespacedKey[] keys) {
        if (!key.getNamespace().equals("aurora")) return -1;
        String prefix = keys == LEGACY ? "placed_legacy_v3_" : "placed_clear_v3_";
        if (!key.getKey().startsWith(prefix)) return -1;
        try {
            int index = Integer.parseInt(key.getKey().substring(prefix.length()), 16);
            return index >= 0 && index < keys.length && key.equals(keys[index]) ? index : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static boolean sorted(int[] values) {
        if (values.length > MAX_VALUES) return false;
        for (int i = 1; i < values.length; i++) if (values[i - 1] >= values[i]) return false;
        return true;
    }

    static boolean sorted(long[] values) {
        if (values.length > MAX_VALUES) return false;
        for (int i = 1; i < values.length; i++) if (values[i - 1] >= values[i]) return false;
        return true;
    }
}

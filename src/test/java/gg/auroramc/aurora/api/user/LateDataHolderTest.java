package gg.auroramc.aurora.api.user;

import gg.auroramc.aurora.api.util.NamespacedId;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class LateDataHolderTest {
    public static class Holder extends UserDataHolder {
        int progress;
        public NamespacedId getId() { return NamespacedId.fromDefault("late-test"); }
        public void serializeInto(ConfigurationSection data) { data.set("progress", progress); }
        public void initFrom(ConfigurationSection data) { if (data != null) progress = data.getInt("progress"); }
    }
    @Test void restoresStoredDataWhenDependentPluginRegistersAfterOnlineUserLoaded() {
        var yaml = new YamlConfiguration(); yaml.set("aurora/late-test.progress", 7);
        var user = new AuroraUser(UUID.randomUUID(), true); user.initData(yaml, Set.of());
        user.initMissingData(Holder.class);
        assertEquals(7, ((Holder) user.getDataHolders().iterator().next()).progress);
        assertTrue(user.isLoaded());
    }
    @Test void duplicateLoadEventKeepsUnsavedProgressAndHolderIdentity() {
        var user = new AuroraUser(UUID.randomUUID(), true); user.initData(new YamlConfiguration(), Set.of(Holder.class));
        var holder = (Holder) user.getDataHolders().iterator().next(); holder.progress = 9; holder.setDirty(true);
        user.initMissingData(Holder.class);
        assertSame(holder, user.getDataHolders().iterator().next()); assertEquals(9, holder.progress); assertTrue(holder.isDirty());
    }
    @Test void concurrentRegistrationDoesNotReplaceHolder() {
        var user = new AuroraUser(UUID.randomUUID(), true); user.initData(new YamlConfiguration(), Set.of());
        CompletableFuture.allOf(CompletableFuture.runAsync(() -> user.initMissingData(Holder.class)), CompletableFuture.runAsync(() -> user.initMissingData(Holder.class))).join();
        assertEquals(1, user.getDataHolders().size());
    }
}

package config;

import gg.auroramc.aurora.config.Config;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CleanerConfigTest {
    @TempDir Path directory;

    @Test
    void malformedConfigIsNotOverwrittenByDefaultMerge() throws Exception {
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        var originalServer = serverField.get(null);
        var console = (ConsoleCommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ConsoleCommandSender.class}, (p, m, a) -> {
            if (m.getName().equals("sendMessage")) return null;
            if (m.getName().equals("getName")) return "ConfigTestConsole";
            throw new AssertionError(m);
        });
        serverField.set(null, Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Server.class}, (p, m, a) -> switch (m.getName()) {
            case "getConsoleSender" -> console;
            case "getLogger" -> Logger.getLogger("CleanerConfigTest");
            default -> throw new AssertionError(m);
        }));
        try {
            var file = directory.resolve("malformed.yml");
            String malformed = "config-version: 15\nblock-tracker: [\n";
            Files.writeString(file, malformed);
            new Config(file.toFile());
            assertEquals(malformed, Files.readString(file));
        } finally {
            serverField.set(null, originalServer);
        }
    }

    @Test
    void oldConfigUpgradePreservesValuesAndComments() throws Exception {
        var file = directory.resolve("config.yml").toFile();
        var yaml = new YamlConfiguration();
        yaml.set("config-version", 14);
        yaml.set("block-tracker.enabled", false);
        yaml.set("block-tracker.cleaner-enabled", false);
        yaml.setComments("block-tracker.enabled", List.of("owner comment"));
        yaml.set("storage-type", "mysql");
        yaml.set("mysql.password", "test-only-password");
        yaml.set("unrelated", 42);
        yaml.save(file);
        var config = new Config(file);
        config.load();
        assertFalse(config.getBlockTracker().getEnabled());
        assertFalse(config.getBlockTracker().getCleanerEnabled());
        var upgraded = YamlConfiguration.loadConfiguration(file);
        assertEquals(15, upgraded.getInt("config-version"));
        assertEquals("mysql", upgraded.getString("storage-type"));
        assertEquals("test-only-password", upgraded.getString("mysql.password"));
        assertEquals(42, upgraded.getInt("unrelated"));
        assertEquals(List.of("owner comment"), upgraded.getComments("block-tracker.enabled"));
        assertFalse(upgraded.getComments("block-tracker.cleaner-enabled").isEmpty());
    }

    @Test
    void oldConfigWithoutCleanerKeyGetsEnabledDefault() throws Exception {
        var file = directory.resolve("config.yml").toFile();
        var yaml = new YamlConfiguration();
        yaml.set("config-version", 14);
        yaml.set("block-tracker.enabled", true);
        yaml.set("unrelated", "preserve");
        yaml.save(file);
        var config = new Config(file);
        config.load();
        assertTrue(config.getBlockTracker().getCleanerEnabled());
        var upgraded = YamlConfiguration.loadConfiguration(file);
        assertEquals(15, upgraded.getInt("config-version"));
        assertTrue(upgraded.getBoolean("block-tracker.cleaner-enabled"));
        assertEquals("preserve", upgraded.getString("unrelated"));
    }

    @Test
    void missingKeyIsAddedEvenWhenVersionAlreadyCurrent() throws Exception {
        var file = directory.resolve("config.yml").toFile();
        var yaml = new YamlConfiguration();
        yaml.set("config-version", 15);
        yaml.set("block-tracker.enabled", true);
        yaml.set("locale", "zh-CN");
        yaml.save(file);
        var config = new Config(file);
        config.load();
        assertTrue(config.getBlockTracker().getCleanerEnabled());
        var upgraded = YamlConfiguration.loadConfiguration(file);
        assertTrue(upgraded.getBoolean("block-tracker.cleaner-enabled"));
        assertEquals("zh-CN", upgraded.getString("locale"));
        assertEquals(2, upgraded.getComments("block-tracker.cleaner-enabled").size());
    }
}

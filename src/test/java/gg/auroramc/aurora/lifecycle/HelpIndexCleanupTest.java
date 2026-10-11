package gg.auroramc.aurora.lifecycle;

import org.bukkit.command.CommandSender;
import org.bukkit.help.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HelpIndexCleanupTest {
    public static class RetiredTopic extends HelpTopic {
        public boolean canSee(CommandSender sender) { return true; }
    }
    private HelpTopic retired() throws Exception {
        var name = RetiredTopic.class.getName();
        byte[] code;
        try (var in = getClass().getResourceAsStream("/" + name.replace('.', '/') + ".class")) { code = in.readAllBytes(); }
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> topic() { return defineClass(name, code, 0, code.length); }
        };
        return (HelpTopic) loader.topic().getConstructor().newInstance();
    }
    @Test void immutableEmptyAndUnrelatedIndexesRemainUntouched() throws Exception {
        var entries = List.<HelpTopic>of(new RetiredTopic());
        var index = new IndexHelpTopic("fixture", "", null, entries);
        UnloadReferences.clearIndex(index, Set.of(new ClassLoader() {}), new HashSet<>());
        assertSame(entries, RuntimeAccess.get(index, "allTopics"));
        UnloadReferences.clearIndex(new IndexHelpTopic("empty", "", null, List.of()), Set.of(getClass().getClassLoader()), new HashSet<>());
    }
    @Test void immutableNestedOrphansAreRemovedWithoutRootMapMembership() throws Exception {
        var unrelated = new IndexHelpTopic("unrelated", "", null, List.of());
        var old = retired();
        var nested = new IndexHelpTopic("nested", "", null, List.of(old, unrelated));
        var root = new IndexHelpTopic("root", "", null, List.of(nested));
        UnloadReferences.clearIndex(root, Set.of(old.getClass().getClassLoader()), new HashSet<>());
        assertEquals(List.of(unrelated), RuntimeAccess.get(nested, "allTopics"));
        assertEquals(List.of(nested), RuntimeAccess.get(root, "allTopics"));
    }
    @Test void cyclicIndexDoesNotRecurseForever() throws Exception {
        var entries = new ArrayList<HelpTopic>();
        var index = new IndexHelpTopic("cycle", "", null, entries);
        var old = retired();
        entries.add(index); entries.add(old);
        UnloadReferences.clearIndex(index, Set.of(old.getClass().getClassLoader()), new HashSet<>());
        assertEquals(List.of(index), RuntimeAccess.get(index, "allTopics"));
    }
}

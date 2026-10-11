package gg.auroramc.aurora.expansions.leaderboard;

import gg.auroramc.aurora.api.user.AuroraUser;
import gg.auroramc.aurora.expansions.leaderboard.model.LbEntry;
import gg.auroramc.aurora.expansions.leaderboard.storage.BoardValue;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DetachedBoardTest {
    @Test void detachedBoardSavesRetainedComputedValueWithoutDroppingDirtyEntry() {
        var boards = new LeaderboardExpansion();
        var user = new AuroraUser(UUID.randomUUID());
        user.getLeaderboardEntries().put("quests_daily", new LbEntry(user.getUniqueId(), "fixture", "quests_daily", 37, 0));
        var values = new HashSet<BoardValue>();
        boards.populateBoardValues(user, null, values, "quests_daily");
        assertEquals(Set.of(new BoardValue("quests_daily", 37)), values);
        assertEquals(37D, user.getDirtyLeaderboards().get("quests_daily"));
    }
    @Test void restoredBoardUsesCurrentDataRatherThanStaleCachedValue() {
        var boards = new LeaderboardExpansion();
        var user = new AuroraUser(UUID.randomUUID());
        user.getLeaderboardEntries().put("quests_daily", new LbEntry(user.getUniqueId(), "fixture", "quests_daily", 37, 0));
        boards.registerBoard("quests_daily", ignored -> 41D, 10);
        var values = new HashSet<BoardValue>();
        boards.populateBoardValues(user, null, values, "quests_daily");
        assertEquals(Set.of(new BoardValue("quests_daily", 41)), values);
    }
    @Test void unknownBoardWithoutActualValueFailsRatherThanInventingZero() {
        var boards = new LeaderboardExpansion();
        var error = assertThrows(IllegalStateException.class, () -> boards.populateBoardValues(new AuroraUser(UUID.randomUUID()), null, new HashSet<>(), "missing"));
        assertTrue(error.getMessage().contains("missing"));
    }
}

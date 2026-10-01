package gg.auroramc.hotswap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyUserIoTest {
    @Test void recognizesRealStorageAndPendingQuitButNotCacheReads() {
        assertTrue(LegacyUserIo.active(frame("gg.auroramc.aurora.api.user.storage.sql.MySqlStorage", "bulkSaveUsers")));
        assertTrue(LegacyUserIo.active(frame("gg.auroramc.aurora.api.user.UserManager", "saveUserData")));
        assertTrue(LegacyUserIo.active(frame("gg.auroramc.aurora.api.user.UserManager", "lambda$invalidate$13")));
        assertTrue(LegacyUserIo.active(frame("gg.auroramc.aurora.expansions.leaderboard.LeaderboardExpansion", "lambda$updateUser$5")));
        assertFalse(LegacyUserIo.active(frame("gg.auroramc.aurora.api.user.UserManager", "getUser")));
        assertFalse(LegacyUserIo.active(frame("other.plugin.Storage", "saveUser")));
    }
    private StackTraceElement frame(String type, String method) {
        return new StackTraceElement(type, method, "Fixture.java", 1);
    }
}

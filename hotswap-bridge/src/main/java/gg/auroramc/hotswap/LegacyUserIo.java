package gg.auroramc.hotswap;

/** The pinned originals use common-pool futures without an operation counter. */
final class LegacyUserIo {
    static boolean active(StackTraceElement frame) {
        String type = frame.getClassName(), method = frame.getMethodName();
        if (type.startsWith("gg.auroramc.aurora.api.user.storage.")
                || type.startsWith("gg.auroramc.aurora.expansions.leaderboard.storage.")) return true;
        if (type.equals("gg.auroramc.aurora.api.user.UserManager")) {
            return method.equals("saveUserData") || method.equals("stopTasksAndSaveAllData")
                    || method.startsWith("lambda$loadUser") || method.startsWith("lambda$invalidate$")
                    || method.startsWith("lambda$purgeUserData$") || method.startsWith("lambda$attemptMigration$");
        }
        return type.equals("gg.auroramc.aurora.expansions.leaderboard.LeaderboardExpansion")
                && (method.startsWith("lambda$") || method.equals("updateLeaderBoards"));
    }
    private LegacyUserIo() { }
}

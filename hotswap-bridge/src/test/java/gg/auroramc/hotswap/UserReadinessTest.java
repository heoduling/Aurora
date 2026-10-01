package gg.auroramc.hotswap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserReadinessTest {
    @Test void onlineLoadedPlayerIsReady() {
        assertEquals(UserReadiness.READY, UserReadiness.classify(true, true, true, true));
    }

    @Test void onlinePlaceholderMustWaitForItsDatabaseLoad() {
        assertEquals(UserReadiness.WAITING, UserReadiness.classify(true, false, false, false));
    }

    @Test void onlyCleanUnloadedOfflinePlaceholderCanBeIgnored() {
        assertEquals(UserReadiness.EMPTY_OFFLINE_PLACEHOLDER, UserReadiness.classify(false, false, false, false));
    }

    @Test void unloadedConfigurationCannotBeIgnored() {
        assertEquals(UserReadiness.WAITING, UserReadiness.classify(false, false, true, false));
    }

    @Test void dirtyOfflinePlaceholderCannotBeIgnored() {
        assertEquals(UserReadiness.WAITING, UserReadiness.classify(false, false, false, true));
    }

    @Test void loadedOfflineDataIsSavedEvenWhenClean() {
        assertEquals(UserReadiness.READY, UserReadiness.classify(false, true, true, false));
    }

    @Test void dirtyLoadedOfflineDataIsSaved() {
        assertEquals(UserReadiness.READY, UserReadiness.classify(false, true, true, true));
    }
}

package gg.auroramc.hotswap;

/** A legacy offline placeholder must never be saved as real player data. */
enum UserReadiness {
    READY, EMPTY_OFFLINE_PLACEHOLDER, WAITING;

    static UserReadiness classify(boolean online, boolean loaded, boolean configured, boolean dirty) {
        if (loaded) return READY;
        if (online) return WAITING;
        if (!loaded && !configured && !dirty) return EMPTY_OFFLINE_PLACEHOLDER;
        return WAITING;
    }
}

# Aurora pair hot swap

This separate coordinator saves the enabled Aurora/AuroraQuests pair, closes its old menus on each player's entity scheduler, retires its resources, then loads both replacement JARs and restores online profiles. It does no idle polling and adds no database schema or world migration.

The verified environment is Shiroha `26.2-DEV-9daa83b (MC: 26.2)`, Java 25 and PlugManX `3.1.0-Beta.2`. Legacy acceptance is restricted to the exact supplied Aurora b214 / AuroraQuests b167 binaries and the preceding maintained 2.6.0-2 / 2.5.0-1 binaries. New replacements must declare `aurora-hotswap-protocol: 1`. Other cores, unsupported dependent plugins, foreign Aurora event listeners, private JDBC drivers and MythicMobs caches holding Aurora objects are rejected before unloading.

Build with the repository's Gradle wrapper and Java 25:

```powershell
.\gradlew.bat -p hotswap-bridge -PplugmanJar=PATH_TO_PLUGMAN_JAR check jar
```

Install as `plugins/AuroraHotSwap.jar`. On an already running server, use `/plugman load AuroraHotSwap`. Place the new pair in `plugins/AuroraHotSwap/incoming/Aurora.jar` and `AuroraQuests.jar`, then run `/aurorahotswap apply`. `/aurorahotswap status` reports progress. Successful completion is logged as `Completed: online users restored`.

Preflight refreshes the online roster while waiting up to 30 seconds for unloaded online data and actual player IO. Loaded offline entries are real data and are included in the save, even if quit cleanup left them cached. Only clean, unloaded offline entries with no configuration are ignored and excluded from saving; other unloaded offline entries block replacement. Tracked operations and the pinned legacy IO call stacks distinguish pending IO from retained cache entries. Loaded records are retained across legacy cache removals, queued storage calls are fenced before the final save, and already entered calls must finish. A timeout reports up to eight blocking states or IO call sites. Checking runs only during `apply`; there is no idle polling. A final roster check precedes changes to the old plugins. This recovery was verified with the user's single-server Aurora database; shared-database recovery has not been verified.

Queued entity records, bStats executors, LuckPerms subscriptions, PlaceholderAPI registrations, Quartz workers, ACF help and thread-local registrations, and Quartz bean caches are retired by ownership. Core internals are used only for this verified core layout. Old JARs are kept below `AuroraHotSwap/retired-jars`, outside the server's active plugin directory. After preparation starts, a save/unload/load failure blocks Aurora operations until a restart. A rejected preflight leaves the enabled pair running. `/aurorahotswap gc` is an explicit administrator diagnostic, never an automatic background task.

The coordinator also retires its own command help when disabled. On enable it removes help owned by a disabled predecessor with the same plugin name, so upgrading from older coordinators does not leave their PluginCommand and plugin instance in the help map.

Native verification uses original binaries, a private MySQL 8.0 instance and three real TCP player sessions. It covers repeated swaps, original and maintained legacy upgrades, live quest progress and single reward delivery, real free-slot menu refunds, unrelated inventories/tasks, save failure, cold restart and class-loader collection. Tests, heap dumps and server/database configuration are not packaged in the production JARs.

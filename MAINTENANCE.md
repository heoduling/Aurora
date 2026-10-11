# Current maintenance: 2.6.0-11

Fixes immutable and nested/orphaned help-index cleanup, saves already computed leaderboard entries after a dependent plugin detaches, and adds `/aurora recover` (`aurora.core.admin.recover`, default OP). Recovery retains the update protection until strict saving and the native reload/readiness checks finish. If a replacement Quests JAR is absent, restore its original filename shown in the error before retrying. A disposed main storage cannot be resumed by this command and requires a maintenance restart.

Validated with Java 25, Shiroha 26.2-DEV-a8f1d54, PlugManX 3.1.0-Beta.2, AuroraQuests 2.5.0-5 and private local MySQL. Evidence is kept outside the distributable JAR under `audit/aurora-native-recovery-20261011` in the maintenance workspace. No database schema, quest configuration or cleaner behavior changes. Source builds still require the existing local compile-only API dependencies in `libs`; these third-party binaries are not included in the source-only ZIP.

For the production upgrade from 2.6.0-10, prefer a maintenance shutdown and replacement: unloading the old binary still runs its old faulty code. The regression tests cover the new binary's unload/reload and built-in recovery, not an unconditional live upgrade from every old state.

## Historical block provenance maintenance: 2.6.0-1

Based on upstream `cc683e6d5752f831ba778c8066567b585c04b64c` (2.6.0).

This patch fixes the reproduced collision between block-location hashes, piston marker transfer, missing explosion/burn/removal-fade cleanup, and sand/red-sand/gravel tracking on Folia. Piston moves snapshot all sources before changing destinations. Falling blocks carry their provenance in entity PDC and restore it through the native landing event on the destination region. Block-break cleanup still happens next tick so drop handlers can read the original provenance.

New chunk markers use exact local X/Z and signed block Y. Existing hexadecimal hash keys remain readable. Because an old hash can represent several positions, removal writes a coordinate-specific zero override when a legacy key exists, retaining the old key for other positions. This preserves existing records; it cannot reconstruct an ambiguous historical marker or reclaim all historical hash keys. Other namespaces are untouched.

Plugin identity, public API, commands, configuration values, MySQL schema and user-data serializers are unchanged. With the supplied production build `Aurora-2.6.0-b214-SNAPSHOT.jar`, all 508 existing classes outside the two repaired classes are byte-identical. Bundled YAML/SQL content is identical after normalizing line endings, except for the plugin version.

Build with Java 25 and the included wrapper:

```sh
./gradlew --no-daemon clean check shadowJar
```

Use an ASCII build/output path on Windows if Java argument-file encoding prevents test classes from loading. The test suite contains 17 upstream tests and 13 block-provenance regression cases. Verification also exercised native piston extension/retraction, slime attachments, falling sand, and chunk/entity PDC across a restart on an isolated mizuki/Folia 26.2 server.

Stop the server, replace the two plugin JARs, and keep the existing configuration and data directories. This patch requires no MySQL or whole-world migration; backing up or restoring these stores is not an installation prerequisite. The original JAR cannot read new coordinate markers after a downgrade. This patch does not include a historical bulk cleaner: ambiguous legacy hashes remain stored and receive coordinate-specific overrides.

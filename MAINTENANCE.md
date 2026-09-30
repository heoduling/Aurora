# Block provenance maintenance: 2.6.0-1

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

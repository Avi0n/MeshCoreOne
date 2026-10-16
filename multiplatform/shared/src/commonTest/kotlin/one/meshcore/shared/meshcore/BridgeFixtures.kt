package one.meshcore.shared.meshcore

/**
 * Reads every `<name>.json` golden vector under `fixtures/bridge/<subdir>` (subdir is `events` or
 * `results`), keyed by file name without extension. The directory is supplied by the Gradle
 * `mc1.bridgeFixturesDir` system property so the desktop and Android unit targets read the same
 * checked-in files that the Swift producer generates.
 */
expect fun readBridgeFixtures(subdir: String): Map<String, String>

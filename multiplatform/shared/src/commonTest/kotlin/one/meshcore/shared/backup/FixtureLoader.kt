package one.meshcore.shared.backup

/**
 * Reads a frozen backup fixture by file name from the repo's canonical `fixtures/backup/` directory.
 * The path is supplied by the Gradle `mc1.fixturesDir` system property so both the desktop and the
 * Android unit test targets resolve the same checked-in files rather than a copy.
 */
expect fun loadBackupFixture(name: String): String

/** Reads a binary fixture (e.g. zlib `.mc1backup`) from the same directory. */
expect fun loadBackupFixtureBytes(name: String): ByteArray

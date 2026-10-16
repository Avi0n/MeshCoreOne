package one.meshcore.shared.fixtures

/**
 * Reads a checked-in file under the repo `fixtures/` directory. The path is supplied by the Gradle
 * `mc1.repoFixturesDir` system property so desktop and Android unit tests resolve the same files.
 */
expect fun loadRepoFixture(relativePath: String): String

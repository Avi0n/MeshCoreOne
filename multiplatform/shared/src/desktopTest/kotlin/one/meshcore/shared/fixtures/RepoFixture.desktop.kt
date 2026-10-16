package one.meshcore.shared.fixtures

import java.io.File

actual fun loadRepoFixture(relativePath: String): String {
    val dir = requireNotNull(System.getProperty("mc1.repoFixturesDir")) {
        "mc1.repoFixturesDir system property is not set; the Gradle test task must supply it"
    }
    return File(dir, relativePath).readText()
}

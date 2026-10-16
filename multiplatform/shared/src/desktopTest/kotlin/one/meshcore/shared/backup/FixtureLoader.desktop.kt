package one.meshcore.shared.backup

import java.io.File

actual fun loadBackupFixture(name: String): String {
    val dir = requireNotNull(System.getProperty("mc1.fixturesDir")) {
        "mc1.fixturesDir system property is not set; the Gradle test task must supply it"
    }
    return File(dir, name).readText()
}

actual fun loadBackupFixtureBytes(name: String): ByteArray {
    val dir = requireNotNull(System.getProperty("mc1.fixturesDir")) {
        "mc1.fixturesDir system property is not set; the Gradle test task must supply it"
    }
    return File(dir, name).readBytes()
}

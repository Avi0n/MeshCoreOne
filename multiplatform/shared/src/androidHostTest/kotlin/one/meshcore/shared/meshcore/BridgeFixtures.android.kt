package one.meshcore.shared.meshcore

import java.io.File

actual fun readBridgeFixtures(subdir: String): Map<String, String> {
    val dir = requireNotNull(System.getProperty("mc1.bridgeFixturesDir")) {
        "mc1.bridgeFixturesDir system property is not set; the Gradle test task must supply it"
    }
    val files = File(dir, subdir).listFiles { file -> file.extension == "json" }
        ?: error("No bridge fixtures found under $dir/$subdir")
    return files.associate { it.nameWithoutExtension to it.readText() }
}

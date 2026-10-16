package one.meshcore.shared.backup

import java.io.File
import kotlin.test.Test

/**
 * Writes fixtures/backup/kotlin-reexport-full.json when REGENERATE_KOTLIN_BACKUP=1.
 * Not a gate test — disabled unless the env flag is set.
 */
class GenerateKotlinBackupExport {
    @Test
    fun regenerateKotlinReexportFixture() {
        if (System.getenv("REGENERATE_KOTLIN_BACKUP") != "1") return
        val encoded = encodeBackupEnvelope(decodeBackupEnvelope(loadBackupFixture("full-envelope.json")))
        // Resolve fixtures/backup from common Gradle working directories under multiplatform/.
        val candidates = listOf(
            File("fixtures/backup/kotlin-reexport-full.json"),
            File("../fixtures/backup/kotlin-reexport-full.json"),
            File("../../fixtures/backup/kotlin-reexport-full.json"),
            File("../../../fixtures/backup/kotlin-reexport-full.json"),
        )
        val target = candidates.firstOrNull { it.parentFile?.exists() == true }
            ?: error("Could not locate fixtures/backup from ${File(".").absolutePath}")
        target.writeText(encoded)
        println("Wrote ${target.absolutePath} (${encoded.length} bytes)")
    }
}

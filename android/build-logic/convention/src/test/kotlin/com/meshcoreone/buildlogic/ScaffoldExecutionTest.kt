// AndroidOnly: WP-002 Exercise project-owned resolution through real parallel Gradle task execution.
package com.meshcoreone.buildlogic

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class ScaffoldExecutionTest {
    @TempDir
    lateinit var directory: File

    private fun fixture() {
        File(directory, "settings.gradle").writeText(
            "rootProject.name = 'fixture'\n" + allowedEdges.keys.sorted().joinToString("") { "include '$it'\n" },
        )
        File(directory, "build.gradle").writeText(
            """
            plugins { id 'mesh.scaffold.root' }
            subprojects {
                apply plugin: 'java-library'
                repositories { maven { url = rootProject.uri('repo') } }
            }
            """.trimIndent(),
        )
        allowedEdges.keys.forEach { path ->
            moduleFile(path).apply { parentFile.mkdirs(); writeText("") }
        }
        moduleFile(":app").writeText(
            """
            configurations {
                debugRuntimeClasspath { canBeResolved = true; canBeConsumed = false }
            }
            dependencies { debugRuntimeClasspath 'example:runtime:1.0' }
            """.trimIndent(),
        )
        publish("example", "parent", "pom", """
            <licenses>
              <license><name>Apache-2.0</name><url>https://example.test/apache</url></license>
              <license><name>MIT</name><url>https://example.test/mit</url></license>
            </licenses>
        """.trimIndent())
        publish("example", "runtime", "jar", """
            <parent><groupId>example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
        """.trimIndent())
    }

    private fun moduleFile(path: String) =
        File(directory, path.removePrefix(":").replace(':', File.separatorChar) + "${File.separator}build.gradle")

    private fun publish(group: String, artifact: String, packaging: String, body: String) {
        val folder = File(directory, "repo/${group.replace('.', '/')}/$artifact/1.0")
        folder.mkdirs()
        File(folder, "$artifact-1.0.pom").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId>" +
                "<artifactId>$artifact</artifactId><version>1.0</version><packaging>$packaging</packaging>$body</project>",
        )
        if (packaging != "pom") {
            ZipOutputStream(File(folder, "$artifact-1.0.$packaging").outputStream()).use { }
        }
    }

    private fun runner(vararg tasks: String): GradleRunner =
        GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(*tasks, "--parallel", "--max-workers=4", "--offline", "--stacktrace")

    @Test
    fun `parallel project tasks preserve complete graph and inherited runtime license evidence`() {
        fixture()
        moduleFile(":core:contracts").appendText("\ndependencies { implementation project(':core:model') }\n")
        val result = runner("validateModuleGraph", "runtimeDependencyInventory", "resolveScaffoldDependencies").build()
        assertTrue(result.output.contains("Module graph verified: ${allowedEdges.size} modules"))
        assertTrue(result.output.contains(":core:contracts:writeScaffoldModuleSnapshot"))
        assertTrue(result.output.contains(":app:writeScaffoldRuntimeDependencyInventory"))
        assertTrue(result.output.contains(":core:contracts:resolveScaffoldProjectDependencies"))
        val graph = File(directory, "build/reports/scaffold/module-graph.tsv").readText()
        assertTrue(graph.contains(":core:contracts\t:core:model\timplementation"))
        val parent = File(directory, "repo/example/parent/1.0/parent-1.0.pom")
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(parent.readBytes()))
        val inventory = File(directory, "build/reports/scaffold/runtime-dependencies.tsv").readLines()
        assertEquals(3, inventory.size)
        assertTrue(inventory[1].contains("example:runtime:1.0\tApache-2.0\thttps://example.test/apache\texample:parent:1.0\t$digest\thuman-review-pending"))
        assertTrue(inventory[2].contains("example:runtime:1.0\tMIT\thttps://example.test/mit\texample:parent:1.0\t$digest\thuman-review-pending"))

        moduleFile(":feature:chats").appendText("\ndependencies { implementation project(':feature:nodes') }\n")
        val violation = runner("validateModuleGraph").buildAndFail()
        assertTrue(violation.output.contains("Feature-to-feature edge :feature:chats -> :feature:nodes"))

        moduleFile(":feature:chats").writeText("")
        File(directory, "settings.gradle").appendText("\ninclude ':unapproved'\n")
        moduleFile(":unapproved").apply { parentFile.mkdirs(); writeText("") }
        val unknown = runner("validateModuleGraph").buildAndFail()
        assertTrue(unknown.output.contains("Unknown module :unapproved"))
    }

    @Test
    fun `transitive Android artifacts in a JVM module remain forbidden`() {
        fixture()
        publish("com.google.android", "fixture", "aar", "")
        publish("example", "bridge", "jar", """
            <dependencies><dependency><groupId>com.google.android</groupId><artifactId>fixture</artifactId>
            <version>1.0</version></dependency></dependencies>
        """.trimIndent())
        moduleFile(":core:contracts").appendText("\ndependencies { implementation 'example:bridge:1.0' }\n")
        val result = runner("validateModuleGraph").buildAndFail()
        assertTrue(result.output.contains("Android artifact com.google.android:fixture:1.0"))
    }

    @Test
    fun `missing runtime license and unresolved dependency still fail`() {
        fixture()
        publish("example", "parent", "pom", "")
        val missingLicense = runner("runtimeDependencyInventory").buildAndFail()
        assertTrue(missingLicense.output.contains("Undeclared runtime license for example:runtime:1.0"))

        moduleFile(":app").appendText("\ndependencies { debugRuntimeClasspath 'example:missing:1.0' }\n")
        val missingRuntime = runner("runtimeDependencyInventory").buildAndFail()
        assertTrue(missingRuntime.output.contains("Unresolved dependency graph debugRuntimeClasspath"))

        moduleFile(":app").writeText("configurations { debugRuntimeClasspath { canBeResolved = true; canBeConsumed = false } }")
        val emptyRuntime = runner("runtimeDependencyInventory").buildAndFail()
        assertTrue(emptyRuntime.output.contains("Zero discovered linked runtime dependencies"))

        moduleFile(":core:contracts").appendText("\ndependencies { implementation 'example:missing:1.0' }\n")
        val unresolved = runner("resolveScaffoldDependencies").buildAndFail()
        assertTrue(unresolved.output.contains("Unresolved dependency graph"))
    }
}

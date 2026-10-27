// AndroidOnly: WP-002 Positive/negative assertions use real Gradle project/configuration fixtures.
package com.meshcoreone.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class ModuleGraphTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `module snapshots retain evidence and reject mismatched consumers`() {
        val snapshot = ModuleSnapshot(
            ":core:model",
            listOf(ProductionEdge(":core:model", ":core:protocol", "implementation")),
            setOf("Android artifact fixture"),
            setOf("example:dynamic:1.+"),
        )
        val file = File(directory, "snapshot.json")
        writeModuleSnapshot(file, snapshot)
        assertEquals(snapshot, readModuleSnapshot(file))
        file.writeText("""{"path":":core:model","edges":[[":app",":core:protocol","api"]],"androidEvidence":[],"dynamicDependencies":[]}""")
        assertFailsWith<IllegalArgumentException> { readModuleSnapshot(file) }
    }

    private fun fixture(vararg paths: String): Map<String, Project> {
        val root = ProjectBuilder.builder().withName("fixture").withProjectDir(directory).build()
        val result = linkedMapOf(":" to root)
        paths.forEach { path ->
            var parent = root
            var current = ""
            path.removePrefix(":").split(":").forEach { name ->
                current += ":$name"
                parent = result.getOrPut(current) {
                    ProjectBuilder.builder().withName(name).withParent(parent).build()
                }
            }
            parent.pluginManager.apply("java-library")
        }
        return result.filterKeys { it in paths }
    }

    private fun Project.edge(producer: String, configuration: String = "implementation") {
        configurations.maybeCreate(configuration)
        dependencies.add(configuration, dependencies.project(mapOf("path" to producer)))
    }

    private fun violations(projects: Map<String, Project>) =
        graphViolations(projects.values.map { inspectModule(it, resolveJvmArtifacts = false) }, completeInventory = false)

    @Test
    fun `factory consumers depend on contracts without concrete mutual edges`() {
        val modules = fixture(":core:runtime", ":core:services", ":core:contracts")
        modules.getValue(":core:runtime").edge(":core:contracts")
        modules.getValue(":core:services").edge(":core:contracts")
        assertEquals(emptyList(), violations(modules))
    }

    @Test
    fun `theme consumer can observe the process-owned preference store`() {
        val modules = fixture(":core:designsystem", ":core:datastore", ":core:model", ":core:l10n", ":core:contracts")
        modules.getValue(":core:designsystem").edge(":core:model")
        modules.getValue(":core:designsystem").edge(":core:l10n")
        modules.getValue(":core:designsystem").edge(":core:datastore")
        modules.getValue(":core:datastore").edge(":core:model")
        modules.getValue(":core:datastore").edge(":core:contracts")
        assertEquals(emptyList(), violations(modules))
    }

    @Test
    fun `preference store cannot depend back on theme consumer or form a cycle`() {
        val modules = fixture(":core:designsystem", ":core:datastore")
        modules.getValue(":core:designsystem").edge(":core:datastore")
        modules.getValue(":core:datastore").edge(":core:designsystem")
        val result = violations(modules)
        assertTrue(result.any { it.startsWith("Unlisted production edge") && it.contains(":core:datastore -> :core:designsystem") })
        assertTrue(result.any { it.startsWith("Dependency cycle") })
    }

    @Test
    fun `theme adapter does not authorize direct concrete preference edges in features`() {
        val modules = fixture(":feature:settings", ":core:datastore")
        modules.getValue(":feature:settings").edge(":core:datastore")
        assertTrue(violations(modules).any { it.startsWith("Unlisted production edge") })
    }

    @Test
    fun `shared tips can atomically use the process-owned preference store`() {
        val modules = fixture(":core:ui", ":core:datastore", ":core:contracts", ":core:model")
        modules.getValue(":core:ui").edge(":core:datastore")
        modules.getValue(":core:ui").edge(":core:contracts")
        modules.getValue(":core:datastore").edge(":core:contracts")
        modules.getValue(":core:datastore").edge(":core:model")
        assertEquals(emptyList(), violations(modules))
    }

    @Test
    fun `preference store cannot depend back on shared UI or form a cycle`() {
        val modules = fixture(":core:ui", ":core:datastore")
        modules.getValue(":core:ui").edge(":core:datastore")
        modules.getValue(":core:datastore").edge(":core:ui")
        val result = violations(modules)
        assertTrue(result.any { it.startsWith("Unlisted production edge") && it.contains(":core:datastore -> :core:ui") })
        assertTrue(result.any { it.startsWith("Dependency cycle") })
    }

    @Test
    fun `shared tips do not authorize UI concrete radio or data dependencies`() {
        for (producer in listOf(":core:ble", ":core:data", ":core:runtime", ":core:services")) {
            val modules = fixture(":core:ui", producer)
            modules.getValue(":core:ui").edge(producer)
            assertTrue(violations(modules).any { it.startsWith("Unlisted production edge") }, producer)
        }
    }

    @Test
    fun `actual feature dependency is rejected`() {
        val modules = fixture(":feature:chats", ":feature:nodes")
        modules.getValue(":feature:chats").edge(":feature:nodes")
        assertTrue(violations(modules).any { it.startsWith("Feature-to-feature") })
    }

    @Test
    fun `feature cannot directly consume a platform adapter`() {
        val modules = fixture(":feature:chats", ":platform:translation")
        modules.getValue(":feature:chats").edge(":platform:translation")
        assertTrue(violations(modules).any { it.startsWith("Feature-to-platform") })
    }

    @Test
    fun `runtime cannot depend on concrete services`() {
        val modules = fixture(":core:runtime", ":core:services")
        modules.getValue(":core:runtime").edge(":core:services")
        assertTrue(violations(modules).any { it.startsWith("Unlisted production edge") })
    }

    @Test
    fun `production cannot consume any test development or verification module`() {
        developmentPaths.forEach { development ->
            val modules = fixture(":app", development)
            modules.getValue(":app").edge(development)
            assertTrue(violations(modules).any { it.startsWith("Production depends") }, development)
        }
    }

    @Test
    fun `actual circular configurations are rejected`() {
        val modules = fixture(":core:model", ":core:protocol")
        modules.getValue(":core:model").edge(":core:protocol")
        modules.getValue(":core:protocol").edge(":core:model")
        assertTrue(violations(modules).any { it.startsWith("Dependency cycle") })
    }

    @Test
    fun `inherited generator configuration cannot hide a forbidden edge`() {
        val modules = fixture(":feature:chats", ":feature:nodes")
        val chats = modules.getValue(":feature:chats")
        chats.edge(":feature:nodes", "generatedInputs")
        chats.configurations.getByName("implementation").extendsFrom(chats.configurations.getByName("generatedInputs"))
        assertTrue(violations(modules).any { it.contains(":feature:nodes (implementation)") })
    }

    @Test
    fun `KSP production inputs cannot depend on development modules`() {
        val modules = fixture(":core:database", ":core:testing")
        modules.getValue(":core:database").edge(":core:testing", "kspDebug")
        assertTrue(violations(modules).any { it.startsWith("Production depends") && it.contains("kspDebug") })
    }

    @Test
    fun `test configurations may consume test helpers`() {
        val modules = fixture(":core:contracts", ":core:testing")
        modules.getValue(":core:contracts").edge(":core:testing", "testImplementation")
        assertEquals(emptyList(), violations(modules))
    }

    @Test
    fun `Android external dependency in a pure JVM module is rejected`() {
        val modules = fixture(":core:protocol")
        modules.getValue(":core:protocol").dependencies.add("implementation", "androidx.room:room-runtime:2.8.5")
        assertTrue(violations(modules).any { it.contains("Android dependency androidx.room:room-runtime") })
    }

    @Test
    fun `Android references in actual JVM production sources are rejected`() {
        val modules = fixture(":core:contracts")
        val source = File(modules.getValue(":core:contracts").projectDir, "src/main/java/AndroidLeak.java")
        source.parentFile.mkdirs()
        source.writeText("import android.content.Context;\nclass AndroidLeak { Context context; }\n")
        assertTrue(violations(modules).any { it.contains("Android source reference") })
    }

    @Test
    fun `dynamic production versions are rejected`() {
        val modules = fixture(":core:model")
        modules.getValue(":core:model").dependencies.add("implementation", "example:unstable:1.+")
        assertTrue(violations(modules).any { it.contains("unpinned dependency") })
    }

    @Test
    fun `unknown and missing modules fail complete graph validation`() {
        val modules = fixture(":unapproved")
        val snapshot = modules.values.map { inspectModule(it, resolveJvmArtifacts = false) }
        assertTrue(graphViolations(snapshot, completeInventory = true).any { it.startsWith("Module inventory mismatch") })
        assertTrue(graphViolations(snapshot, completeInventory = false).any { it.startsWith("Unknown module") })
    }
}

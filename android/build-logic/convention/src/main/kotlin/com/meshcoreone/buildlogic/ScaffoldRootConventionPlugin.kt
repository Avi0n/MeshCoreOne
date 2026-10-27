// AndroidOnly: WP-002 Executable graph, lock resolution, schema and nonzero-suite checks.
package com.meshcoreone.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class ScaffoldRootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        check(this == rootProject) { "Scaffold root convention belongs at the root only" }
        tasks.register("validateModuleGraph") {
            group = "verification"
            description = "Inspect actual production configurations, artifacts and JVM sources against modules.md."
            notCompatibleWithConfigurationCache("Inspects the fully configured Gradle project dependency model")
        }
        tasks.register("resolveScaffoldDependencies") {
            group = "build setup"
            description = "Resolve configured component graphs for reviewed locks; consumed artifact views supply checksums."
            notCompatibleWithConfigurationCache("Resolves the configured project dependency model")
        }
        tasks.register("runtimeDependencyInventory") {
            group = "verification"
            description = "Record exact linked-runtime POM license inputs; missing declarations block admission."
            notCompatibleWithConfigurationCache("Resolves current linked artifacts and declared license POMs")
        }
        tasks.register("verifyScaffoldTests") {
            group = "verification"
            description = "Require real nonzero, unskipped assertions in all four scaffold suites."
            notCompatibleWithConfigurationCache("Reads current reports from the four configured scaffold suites")
            dependsOn(
                ":core:contracts:test",
                ":app:testDebugUnitTest",
                ":scaffold:room-verification:testDebugUnitTest",
                gradle.includedBuild("build-logic").task(":convention:test"),
            )
            doLast {
                val reports = linkedMapOf(
                    "build-logic" to "build-logic/convention/build/test-results/test",
                    "contracts" to "core/contracts/build/test-results/test",
                    "app" to "app/build/test-results/testDebugUnitTest",
                    "room-verification" to "scaffold/room-verification/build/test-results/testDebugUnitTest",
                )
                val result = layout.buildDirectory.file("reports/scaffold/test-discovery.tsv").get().asFile
                result.parentFile.mkdirs()
                result.writeText(buildString {
                    appendLine("suite\tdiscovered\tpassed\tfailed\terrors\tskipped")
                    reports.forEach { (suite, path) ->
                        val evidence = readTestEvidence(layout.projectDirectory.dir(path).asFile)
                        appendLine("$suite\t${evidence.discovered}\t${evidence.passed}\t${evidence.failed}\t${evidence.errors}\t${evidence.skipped}")
                        logger.lifecycle("$suite: $evidence")
                    }
                })
            }
        }
        tasks.register("verifyRoomSchema") {
            group = "verification"
            description = "Require a real Room/KSP-exported verification-fixture schema, not a product schema."
            notCompatibleWithConfigurationCache("Inspects the current fixture schema export")
            dependsOn(":scaffold:room-verification:assembleDebug")
            doLast {
                val schema = layout.projectDirectory.file(
                    "scaffold/room-verification/schemas/com.meshcoreone.android.scaffold.roomverification.ScaffoldDatabase/1.json",
                ).asFile
                verifyScaffoldSchema(schema)
                logger.lifecycle("Room/KSP fixture schema exported; no production schema/migration/backup acceptance implied")
            }
        }
        tasks.register("lintScaffold") {
            group = "verification"
            description = "Lint every Android scaffold module."
        }
        gradle.projectsEvaluated {
            val modules = subprojects.filter { it.path in allowedEdges || it.buildFile.isFile }
            val snapshots = modules.map { module ->
                val destination = module.layout.buildDirectory.file("reports/scaffold/module-snapshot.json")
                val producer = module.tasks.register("writeScaffoldModuleSnapshot") {
                    notCompatibleWithConfigurationCache("Inspects this project's configured dependency model")
                    outputs.file(destination)
                    outputs.upToDateWhen { false }
                    doLast {
                        writeModuleSnapshot(destination.get().asFile, inspectModule(module, resolveJvmArtifacts = true))
                    }
                }
                destination to producer
            }
            tasks.named("validateModuleGraph") {
                dependsOn(snapshots.map { it.second })
                inputs.files(snapshots.map { it.first })
                outputs.file(layout.buildDirectory.file("reports/scaffold/module-graph.tsv"))
                outputs.upToDateWhen { false }
                doLast {
                    verifyLocalCatalogBookkeeping(layout.projectDirectory.file("settings-gradle.lockfile").asFile)
                    val actual = snapshots.map { readModuleSnapshot(it.first.get().asFile) }
                    val violations = graphViolations(actual, completeInventory = true)
                    if (violations.isNotEmpty()) throw GradleException(violations.joinToString("\n"))
                    writeGraphReport(layout.buildDirectory.file("reports/scaffold/module-graph.tsv").get().asFile, actual)
                    logger.lifecycle("Module graph verified: ${actual.size} modules; no forbidden production edges or Android JVM leakage")
                }
            }
            val resolutionTasks = allprojects.sortedBy { it.path }.map { module ->
                module.tasks.register("resolveScaffoldProjectDependencies") {
                    notCompatibleWithConfigurationCache("Resolves this project's configured dependency model")
                    doLast {
                        module.configurations.filter { it.isCanBeResolved }.sortedBy { it.name }.forEach {
                            resolveDependencyGraph(it)
                        }
                    }
                }
            }
            tasks.named("resolveScaffoldDependencies") {
                dependsOn(resolutionTasks)
            }
            val application = project(":app")
            val inventory = application.layout.buildDirectory.file("reports/scaffold/runtime-dependencies.tsv")
            val inventoryTask = application.tasks.register("writeScaffoldRuntimeDependencyInventory") {
                notCompatibleWithConfigurationCache("Resolves this project's linked artifacts and license POMs")
                outputs.file(inventory)
                outputs.upToDateWhen { false }
                doLast {
                    generateRuntimeDependencyInventory(application, inventory.get().asFile)
                }
            }
            tasks.named("runtimeDependencyInventory") {
                dependsOn(inventoryTask)
                inputs.file(inventory)
                outputs.file(layout.buildDirectory.file("reports/scaffold/runtime-dependencies.tsv"))
                outputs.upToDateWhen { false }
                doLast {
                    val destination = layout.buildDirectory.file("reports/scaffold/runtime-dependencies.tsv").get().asFile
                    destination.parentFile.mkdirs()
                    inventory.get().asFile.copyTo(destination, overwrite = true)
                }
            }
            tasks.named("lintScaffold") {
                dependsOn(subprojects.filter { it.plugins.hasPlugin("com.android.library") || it.plugins.hasPlugin("com.android.application") }
                    .map { "${it.path}:lintDebug" })
            }
        }
        tasks.register("checkScaffold") {
            group = "verification"
            description = "Local scaffold assertions only; not a WP completion or protected-gate receipt."
            dependsOn("validateModuleGraph", "verifyScaffoldTests", "verifyRoomSchema", "runtimeDependencyInventory", "lintScaffold")
        }
    }
}

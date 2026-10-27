// AndroidOnly: WP-002 Inspect real Gradle production dependencies and enforce the draft module contract.
package com.meshcoreone.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.plugins.JavaPluginExtension

internal val featurePaths = setOf(
    ":feature:onboarding", ":feature:chats", ":feature:nodes", ":feature:remotenodes",
    ":feature:map", ":feature:tools", ":feature:settings",
)
internal val developmentPaths = setOf(
    ":core:testing", ":tools:meshcli", ":benchmark", ":scaffold:room-verification",
)
internal val pureJvmPaths = setOf(
    ":core:protocol", ":core:model", ":core:contracts", ":core:runtime",
    ":core:services", ":tools:meshcli",
)

internal val allowedEdges: Map<String, Set<String>> = buildMap {
    put(":core:protocol", emptySet())
    put(":core:model", setOf(":core:protocol"))
    put(":core:contracts", setOf(":core:model", ":core:protocol"))
    put(":core:l10n", emptySet())
    put(":core:designsystem", setOf(":core:model", ":core:l10n", ":core:datastore"))
    put(":core:database", setOf(":core:model"))
    put(":core:datastore", setOf(":core:model", ":core:contracts"))
    put(":core:data", setOf(":core:protocol", ":core:model", ":core:contracts", ":core:database", ":core:datastore"))
    put(":core:ble", setOf(":core:protocol", ":core:model", ":core:contracts"))
    put(":core:connectivity", setOf(":core:protocol", ":core:model", ":core:contracts", ":core:ble"))
    put(":core:runtime", setOf(":core:protocol", ":core:model", ":core:contracts"))
    put(":core:services", setOf(":core:protocol", ":core:model", ":core:contracts"))
    put(":core:ui", setOf(":core:model", ":core:contracts", ":core:designsystem", ":core:l10n", ":core:datastore"))
    put(":core:maps", setOf(":core:model", ":core:contracts", ":core:designsystem", ":core:ui", ":core:l10n"))
    val featureCore = setOf(":core:model", ":core:contracts", ":core:designsystem", ":core:ui", ":core:maps", ":core:l10n")
    featurePaths.forEach { put(it, featureCore) }
    put(":platform:notifications", setOf(":core:model", ":core:contracts", ":core:l10n"))
    put(":platform:widgets", setOf(":core:model", ":core:contracts", ":core:designsystem", ":core:l10n"))
    put(":platform:shortcuts", setOf(":core:model", ":core:contracts", ":core:l10n"))
    put(":platform:translation", setOf(":core:model", ":core:contracts"))
    val productionLibraries = keys.toSet()
    put(":app", productionLibraries)
    put(":core:testing", productionLibraries)
    put(":tools:meshcli", setOf(":core:protocol", ":core:model", ":core:contracts"))
    put(":benchmark", emptySet())
    put(":scaffold:room-verification", emptySet())
}

internal data class ProductionEdge(val consumer: String, val producer: String, val configuration: String)
internal data class ModuleSnapshot(
    val path: String,
    val edges: List<ProductionEdge>,
    val androidEvidence: Set<String>,
    val dynamicDependencies: Set<String>,
)

internal fun writeModuleSnapshot(destination: File, module: ModuleSnapshot) {
    destination.parentFile.mkdirs()
    destination.writeText(JsonOutput.toJson(mapOf(
        "path" to module.path,
        "edges" to module.edges.map { listOf(it.consumer, it.producer, it.configuration) },
        "androidEvidence" to module.androidEvidence.sorted(),
        "dynamicDependencies" to module.dynamicDependencies.sorted(),
    )))
}

internal fun readModuleSnapshot(source: File): ModuleSnapshot {
    val data = JsonSlurper().parse(source) as? Map<*, *>
        ?: error("Malformed module snapshot: ${source.path}")
    val path = data["path"] as? String ?: error("Missing module snapshot path: ${source.path}")
    fun strings(name: String): Set<String> {
        val values = data[name] as? List<*> ?: error("Missing $name in ${source.path}")
        return values.map { it as? String ?: error("Malformed $name in ${source.path}") }.toSet()
    }
    val edges = (data["edges"] as? List<*> ?: error("Missing edges in ${source.path}")).map { value ->
        val parts = value as? List<*> ?: error("Malformed edge in ${source.path}")
        require(parts.size == 3 && parts.all { it is String }) { "Malformed edge in ${source.path}" }
        ProductionEdge(parts[0] as String, parts[1] as String, parts[2] as String)
    }
    require(edges.all { it.consumer == path }) { "Mismatched module snapshot consumer: ${source.path}" }
    return ModuleSnapshot(path, edges, strings("androidEvidence"), strings("dynamicDependencies"))
}

internal fun isProductionConfiguration(name: String): Boolean {
    val lower = name.lowercase()
    if (lower.contains("test")) return false
    return lower in setOf("api", "implementation", "compileonly", "runtimeonly", "compileclasspath", "runtimeclasspath") ||
        lower.startsWith("ksp") ||
        lower.endsWith("compileclasspath") || lower.endsWith("runtimeclasspath") ||
        lower.endsWith("implementation") || lower.endsWith("compileonly") ||
        lower.endsWith("runtimeonly") || lower.endsWith("api")
}

private val androidGroup = Regex("""^(android(\..*)?|androidx(\..*)?|com\.android(\..*)?|com\.google\.android(\..*)?)$""")
private val androidReference = Regex("""(?<![\w.])(android|androidx|com\.android|com\.google\.android)\.""")
private val dynamicVersion = Regex("""[+\[\]()]|^latest\.""")

internal fun inspectModule(project: Project, resolveJvmArtifacts: Boolean): ModuleSnapshot {
    val edges = mutableListOf<ProductionEdge>()
    val android = sortedSetOf<String>()
    val dynamic = sortedSetOf<String>()
    val pure = project.path in pureJvmPaths
    if (pure) {
        listOf("com.android.application", "com.android.library", "com.android.test", "org.jetbrains.kotlin.android").forEach {
            if (project.plugins.hasPlugin(it)) android += "Android plugin $it"
        }
        val sourceFiles = project.extensions.findByType(JavaPluginExtension::class.java)
            ?.sourceSets?.findByName("main")?.allSource?.files.orEmpty()
        sourceFiles.filter { it.extension in setOf("kt", "java") }.forEach { file ->
            if (androidReference.containsMatchIn(file.readText())) {
                android += "Android source reference ${file.relativeTo(project.projectDir).invariantSeparatorsPath}"
            }
        }
    }
    project.configurations.filter { isProductionConfiguration(it.name) }.forEach { configuration ->
        configuration.allDependencies.forEach { dependency ->
            if (dependency is ProjectDependency) {
                edges += ProductionEdge(project.path, dependency.path, configuration.name)
            } else {
                if (pure && dependency.group?.let(androidGroup::matches) == true) {
                    android += "Android dependency ${dependency.group}:${dependency.name} in ${configuration.name}"
                }
                dependency.version?.let { version ->
                    if (dynamicVersion.containsMatchIn(version)) {
                        dynamic += "${dependency.group}:${dependency.name}:$version in ${configuration.name}"
                    }
                }
            }
        }
        if (pure && resolveJvmArtifacts && configuration.isCanBeResolved) {
            configuration.incoming.artifacts.artifacts.forEach { artifact ->
                val id = artifact.id.componentIdentifier
                if (artifact.file.extension == "aar" ||
                    (id is ModuleComponentIdentifier && androidGroup.matches(id.group))
                ) {
                    android += "Android artifact ${id.displayName} in ${configuration.name}"
                }
            }
        }
    }
    return ModuleSnapshot(project.path, edges.distinct(), android, dynamic)
}

internal fun graphViolations(modules: List<ModuleSnapshot>, completeInventory: Boolean): List<String> {
    val violations = mutableListOf<String>()
    val paths = modules.map { it.path }.toSet()
    if (completeInventory && paths != allowedEdges.keys) {
        violations += "Module inventory mismatch: missing=${allowedEdges.keys - paths}, unknown=${paths - allowedEdges.keys}"
    }
    modules.forEach { module ->
        if (module.path !in allowedEdges) violations += "Unknown module ${module.path}"
        if (module.path in pureJvmPaths) {
            module.androidEvidence.forEach { violations += "${module.path}: $it" }
        }
        module.dynamicDependencies.forEach { violations += "${module.path}: unpinned dependency $it" }
        module.edges.forEach { edge ->
            val location = "${edge.consumer} -> ${edge.producer} (${edge.configuration})"
            if (edge.producer !in allowedEdges[edge.consumer].orEmpty()) violations += "Unlisted production edge $location"
            if (edge.consumer !in developmentPaths && edge.producer in developmentPaths) {
                violations += "Production depends on development/test module $location"
            }
            if (edge.consumer in featurePaths && edge.producer.startsWith(":feature:")) {
                violations += "Feature-to-feature edge $location"
            }
            if (edge.consumer in featurePaths && edge.producer.startsWith(":platform:")) {
                violations += "Feature-to-platform edge $location"
            }
        }
    }
    val adjacency = modules.associate { module -> module.path to module.edges.map { it.producer }.toSet() }
    val visited = mutableSetOf<String>()
    val stack = mutableListOf<String>()
    fun visit(path: String) {
        val start = stack.indexOf(path)
        if (start >= 0) {
            violations += "Dependency cycle ${(stack.drop(start) + path).joinToString(" -> ")}"
            return
        }
        if (!visited.add(path)) return
        stack += path
        adjacency[path].orEmpty().filter { it in adjacency }.sorted().forEach(::visit)
        stack.removeAt(stack.lastIndex)
    }
    adjacency.keys.sorted().forEach(::visit)
    return violations.distinct()
}

internal fun writeGraphReport(destination: File, modules: List<ModuleSnapshot>) {
    destination.parentFile.mkdirs()
    destination.writeText(buildString {
        appendLine("consumer\tproducer\tconfiguration")
        modules.sortedBy { it.path }.forEach { module ->
            module.edges.sortedWith(compareBy({ it.producer }, { it.configuration })).forEach { edge ->
                appendLine("${edge.consumer}\t${edge.producer}\t${edge.configuration}")
            }
        }
    })
}

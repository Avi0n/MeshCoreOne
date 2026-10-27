// AndroidOnly: WP-002 Exact linked-runtime POM/license inputs; not a legal approval or release SBOM.
package com.meshcoreone.buildlogic

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.UnresolvedArtifactResult
import org.gradle.api.artifacts.result.UnresolvedComponentResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

internal data class DeclaredLicense(val name: String, val url: String)

private fun Element.child(name: String): Element? {
    val matches = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
        .filter { it.tagName == name }
    require(matches.size <= 1) { "Duplicate Maven POM element: $name" }
    return matches.singleOrNull()
}

internal fun declaredLicenses(pom: File): List<DeclaredLicense> {
    val root = secureXmlFactory().newDocumentBuilder().parse(pom).documentElement
    require(root.tagName == "project") { "Invalid Maven POM root: ${pom.name}" }
    val licenses = root.child("licenses") ?: return emptyList()
    return (0 until licenses.childNodes.length).map { licenses.childNodes.item(it) }
        .filterIsInstance<Element>().filter { it.tagName == "license" }.map { license ->
            val name = license.child("name")?.textContent?.trim()
            val url = license.child("url")?.textContent?.trim()
            require(!name.isNullOrBlank() && !url.isNullOrBlank()) { "Incomplete declared license: ${pom.name}" }
            DeclaredLicense(name, url)
        }.distinct()
}

internal fun generateRuntimeDependencyInventory(project: Project, destination: File) {
    require(project.path == ":app") { "Runtime inventory must execute in the application project" }
    val runtime = project.configurations.getByName("debugRuntimeClasspath")
    resolveDependencyGraph(runtime)
    val ids = runtime.incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? ModuleComponentIdentifier }.sortedBy { it.displayName }
    require(ids.isNotEmpty()) { "Zero discovered linked runtime dependencies" }
    val rows = mutableListOf<String>()
    ids.forEach { id ->
        var coordinate = Triple(id.group, id.module, id.version)
        val visited = mutableSetOf<Triple<String, String, String>>()
        var licenseSource: File? = null
        var licenses = emptyList<DeclaredLicense>()
        while (licenses.isEmpty()) {
            require(visited.add(coordinate) && visited.size <= 8) { "Cyclic/unbounded license parent for ${id.displayName}" }
            val result = project.dependencies.createArtifactResolutionQuery()
                .forModule(coordinate.first, coordinate.second, coordinate.third)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
            val unresolved = result.components.filterIsInstance<UnresolvedComponentResult>().firstOrNull()
            if (unresolved != null) {
                throw GradleException("Cannot read license POM for ${id.displayName}", unresolved.failure)
            }
            require(result.resolvedComponents.size == 1) { "Missing/ambiguous license component for ${id.displayName}" }
            val artifacts = result.resolvedComponents.single().getArtifacts(MavenPomArtifact::class.java)
            require(artifacts.size == 1) { "Missing/ambiguous license POM for ${id.displayName}" }
            val artifact = artifacts.single()
            if (artifact is UnresolvedArtifactResult) {
                throw GradleException("Cannot read license POM for ${id.displayName}", artifact.failure)
            }
            if (artifact !is ResolvedArtifactResult) throw GradleException("Missing license POM for ${id.displayName}")
            licenseSource = artifact.file
            licenses = declaredLicenses(artifact.file)
            if (licenses.isEmpty()) {
                val parent = secureXmlFactory().newDocumentBuilder().parse(artifact.file).documentElement.child("parent")
                    ?: throw GradleException("Undeclared runtime license for ${id.displayName}; admission is blocked")
                fun parentPart(name: String): String {
                    val value = parent.child(name)?.textContent?.trim()
                    require(!value.isNullOrBlank() && !value.contains('$')) { "Unresolved parent $name for ${id.displayName}" }
                    return value
                }
                coordinate = Triple(parentPart("groupId"), parentPart("artifactId"), parentPart("version"))
            }
        }
        val source = requireNotNull(licenseSource)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
        fun String.tsv() = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
        licenses.forEach { license ->
            rows += listOf(
                "${id.group}:${id.module}:${id.version}",
                license.name,
                license.url,
                "${coordinate.first}:${coordinate.second}:${coordinate.third}",
                digest,
                "human-review-pending",
            ).joinToString("\t") { it.tsv() }
        }
    }
    destination.parentFile.mkdirs()
    destination.writeText(
        "artifact\tdeclared_license\tlicense_url\tlicense_pom\tlicense_pom_sha256\tlegal_gate\n" +
            rows.joinToString("\n", postfix = "\n"),
    )
    project.logger.lifecycle("Recorded ${ids.size} linked runtime artifact inputs; legal approval/release SBOM not implied")
}

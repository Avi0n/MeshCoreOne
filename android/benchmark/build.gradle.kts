// AndroidOnly: WP-504 External-package instrumentation; no benchmark dependency enters the product.
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element
import java.security.MessageDigest

plugins { id("mesh.android.library") }

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.meshcoreone.android.benchmark.runner"
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    sourceSets.getByName("test").kotlin.srcDir("dataset/kotlin")
    sourceSets.getByName("androidTest").kotlin.srcDir("dataset/kotlin")
}

dependencies {
    androidTestImplementation(libs.androidx.benchmark.macro.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

tasks.register("verifyBenchmarkDataset") {
    group = "verification"
    description = "Execute deterministic workload assertions and reject absent, skipped or zero-test reports."
    dependsOn("testDebugUnitTest")
    doLast {
        val reports = layout.buildDirectory.dir("test-results/testDebugUnitTest").get().asFile
        val suites = reports.listFiles { file -> file.name.startsWith("TEST-") && file.extension == "xml" }.orEmpty()
        check(suites.isNotEmpty()) { "Missing WP-504 dataset test reports" }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        var cases = 0
        val identities = mutableSetOf<Pair<String, String>>()
        suites.forEach { file ->
            val suite = factory.newDocumentBuilder().parse(file).documentElement
            val nodes = suite.getElementsByTagName("testcase")
            check(suite.getAttribute("tests").toInt() == nodes.length && nodes.length > 0) {
                "Malformed or zero-test WP-504 suite: $file"
            }
            listOf("failures", "errors", "skipped").forEach { outcome ->
                check(suite.getAttribute(outcome).toInt() == 0) { "Unsuccessful WP-504 declared counts: $file" }
            }
            (0 until nodes.length).forEach { index ->
                val case = nodes.item(index) as Element
                val identity = case.getAttribute("classname") to case.getAttribute("name")
                check(identity.first.isNotEmpty() && identity.second.isNotEmpty() && identities.add(identity)) {
                    "Missing/duplicate WP-504 assertion identity: $file"
                }
            }
            listOf("failure", "error", "skipped").forEach { outcome ->
                check(suite.getElementsByTagName(outcome).length == 0) { "Unsuccessful WP-504 suite: $file" }
            }
            cases += nodes.length
        }
        check(cases >= 8) { "Missing required WP-504 dataset assertions: $cases" }
        logger.lifecycle("WP-504 dataset assertions: $cases passed; no device measurements claimed")
    }
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":benchmark:verifyBenchmarkDataset") }

tasks.register("verifyBenchmarkHarness") {
    group = "verification"
    description = "Compile the instrumentation producer and R8 target, execute dataset assertions, and build ordinary release."
    dependsOn("verifyBenchmarkDataset", "benchmarkDependencyInventory", "assembleDebugAndroidTest",
        ":app:assembleBenchmark", ":app:assembleProfile", ":app:assembleRelease")
}

tasks.register("benchmarkDependencyInventory") {
    group = "verification"
    description = "Resolve actual instrumentation artifacts and every inherited license POM; not legal approval."
    notCompatibleWithConfigurationCache("Reads the actual instrumentation resolution graph")
    doLast {
        val configuration = configurations.getByName("debugAndroidTestRuntimeClasspath")
        configuration.incoming.artifacts.artifacts
        val ids = configuration.incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }.sortedBy { it.displayName }
        check(ids.isNotEmpty()) { "Empty benchmark runtime graph" }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        fun Element.child(name: String): Element? =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
                .filter { it.tagName == name }.also { check(it.size <= 1) }.singleOrNull()
        val rows = ids.flatMap { id ->
            var coordinate = listOf(id.group, id.module, id.version)
            val visited = mutableSetOf<List<String>>()
            var result: List<String>? = null
            while (result == null) {
                check(visited.add(coordinate) && visited.size <= 8) { "Cyclic/unbounded license parent: ${id.displayName}" }
                val query = dependencies.createArtifactResolutionQuery()
                    .forModule(coordinate[0], coordinate[1], coordinate[2])
                    .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
                check(query.resolvedComponents.size == 1) { "Unresolved license POM: ${coordinate.joinToString(":")}" }
                val pom = query.resolvedComponents.single().getArtifacts(MavenPomArtifact::class.java)
                    .singleOrNull() as? ResolvedArtifactResult
                    ?: error("Missing/ambiguous license POM: ${coordinate.joinToString(":")}")
                val root = factory.newDocumentBuilder().parse(pom.file).documentElement
                check(root.tagName == "project")
                val licenses = root.child("licenses")?.let { parent ->
                    (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
                        .filterIsInstance<Element>().filter { it.tagName == "license" }
                }.orEmpty()
                if (licenses.isEmpty()) {
                    val parent = root.child("parent") ?: error("Undeclared benchmark license: ${id.displayName}")
                    coordinate = listOf("groupId", "artifactId", "version").map {
                        requireNotNull(parent.child(it)?.textContent?.trim()).also { value ->
                            check(value.isNotEmpty() && !value.contains('$')) { "Unresolved license parent" }
                        }
                    }
                } else {
                    val digest = MessageDigest.getInstance("SHA-256").digest(pom.file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    result = licenses.map {
                        val name = requireNotNull(it.child("name")?.textContent?.trim())
                        val url = requireNotNull(it.child("url")?.textContent?.trim())
                        check(name.isNotEmpty() && url.isNotEmpty())
                        listOf(id.displayName, name, url, coordinate.joinToString(":"), digest, "human-review-pending")
                            .joinToString("\t") { value -> value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
                    }
                }
            }
            result
        }
        layout.buildDirectory.file("reports/wp504/benchmark-dependencies.tsv").get().asFile.apply {
            parentFile.mkdirs()
            writeText("artifact\tdeclared_license\tlicense_url\tlicense_pom\tlicense_pom_sha256\tlegal_gate\n" +
                rows.joinToString("\n") + "\n")
        }
        logger.lifecycle("WP-504 actual test-runtime license inputs: ${ids.size} components; human review pending")
    }
}

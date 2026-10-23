// AndroidOnly: WP-002 Honest launcher/module assembly without a product AppContainer.
// AndroidOnly: WP-218 Narrow app/content native adapters for core:services content ports.
// AndroidOnly: WP-218 Exclusive producer support for WP-302's reviewed native navigation graph.
// Navigation coordinates are the WP-302 owner's reviewed proposal; incumbent catalog pins remain unchanged.
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.security.MessageDigest
import java.nio.file.Files
import java.util.Properties

plugins {
    id("mesh.android.application")
    id("mesh.android.robolectric")
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:data"))
    implementation(project(":core:ble"))
    implementation(project(":core:connectivity"))
    implementation(project(":core:runtime"))
    implementation(project(":core:services"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:maps"))
    implementation(project(":core:l10n"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:chats"))
    implementation(project(":feature:nodes"))
    implementation(project(":feature:remotenodes"))
    implementation(project(":feature:map"))
    implementation(project(":feature:tools"))
    implementation(project(":feature:settings"))
    implementation(project(":platform:notifications"))
    implementation(project(":platform:widgets"))
    implementation(project(":platform:shortcuts"))
    implementation(project(":platform:translation"))
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation(libs.androidx.room.runtime)
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("androidx.navigation3:navigation3-runtime:1.1.0")
    implementation("androidx.navigation3:navigation3-ui:1.1.0")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.4.0")
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.3.0")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

val applicationProfileInput = layout.projectDirectory.file("src/main/baseline-prof.txt").asFile
val applicationProfileAvailable = applicationProfileInput.exists()
val startupStrictMode = providers.gradleProperty("wp504StartupStrictMode").map { it.toBooleanStrict() }.getOrElse(false)
if (applicationProfileAvailable) {
    check(applicationProfileInput.isFile && !Files.isSymbolicLink(applicationProfileInput.toPath())) {
        "WP-504 application profile input must be a regular file"
    }
    val applicationRule = Regex("^[HSP]*Lcom/meshcoreone/android/app/.*;.*$")
    check(applicationProfileInput.useLines { lines -> lines.any(applicationRule::matches) }) {
        "WP-504 application profile input must contain real application rules, not only dependency profiles"
    }
}

android {
    buildFeatures { resValues = true }
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("bool", "wp504_startup_strict_mode", "false")
    }
    buildTypes.getByName("debug") {
        resValue("bool", "wp504_startup_strict_mode", startupStrictMode.toString())
    }
    packaging {
        // AndroidOnly: WP-312 MapLibre's 64-bit binaries are 16 KiB-page aligned. Keep the app's existing
        // 32-bit DataStore support, but omit MapLibre's 4 KiB-only 32-bit runtime and report maps unsupported there.
        jniLibs.excludes += setOf(
            "lib/armeabi-v7a/libmaplibre.so",
            "lib/x86/libmaplibre.so",
        )
    }
    sourceSets.getByName("test").kotlin.srcDir(
        "src/androidTest/kotlin/com/meshcoreone/android/app/navigation/cases",
    )
    // AndroidOnly: WP-503 Execute owned accessibility suites with the existing native App test runner.
    sourceSets.getByName("test").kotlin.srcDir(rootProject.file("accessibility-tests/src/test/kotlin"))
    sourceSets.getByName("androidTest").kotlin.srcDir(rootProject.file("accessibility-tests/src/androidTest/kotlin"))
    listOf("test", "androidTest").forEach { name ->
        sourceSets.getByName(name).kotlin.srcDir(rootProject.file("accessibility-tests/src/shared/kotlin"))
    }
    // AndroidOnly: WP-501 Test-only cross-component acceptance; never an APK source set.
    sourceSets.getByName("test").kotlin.srcDir("../parity-tests/src/test/kotlin")
    // AndroidOnly: WP-502 Test-only external sources; no product dependency or parity-runner change.
    sourceSets.getByName("test").kotlin.srcDirs(
        "../ui-tests/src/shared/kotlin",
        "../ui-tests/src/test/kotlin",
        "../screenshot-tests/src/shared/kotlin",
        "../screenshot-tests/src/test/kotlin",
    )
    sourceSets.getByName("androidTest").kotlin.srcDirs(
        "../ui-tests/src/shared/kotlin",
        "../ui-tests/src/androidTest/kotlin",
        "../screenshot-tests/src/shared/kotlin",
        "../screenshot-tests/src/androidTest/kotlin",
    )

    // AndroidOnly: WP-504 Release optimization and isolated, non-shipping workload target.
    buildTypes.getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    buildTypes.create("benchmark") {
        initWith(buildTypes.getByName("release"))
        applicationIdSuffix = ".benchmark"
        isDebuggable = false
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
        manifestPlaceholders["wp504ProfileSource"] = "false"
        manifestPlaceholders["wp504ApplicationProfileInput"] = applicationProfileAvailable.toString()
    }
    buildTypes.create("profile") {
        initWith(buildTypes.getByName("benchmark"))
        isMinifyEnabled = false
        isShrinkResources = false
        manifestPlaceholders["wp504ProfileSource"] = "true"
    }
    listOf("benchmark", "profile").forEach { name ->
        sourceSets.getByName(name) {
            kotlin.srcDirs("../benchmark/target/kotlin", "../benchmark/dataset/kotlin")
            manifest.srcFile("../benchmark/target/AndroidManifest.xml")
        }
    }
}

class ContentPlatformSdkArguments(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}
// AndroidOnly: WP-504 The fixture compiles against existing pins, without widening production API edges.
dependencies {
    listOf("benchmarkImplementation", "profileImplementation").forEach { configuration ->
        add(configuration, libs.androidx.room.runtime)
        add(configuration, libs.maplibre.android)
    }
}
val appPlatformSdk33 = configurations.create("appPlatformSdk33") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(appPlatformSdk33.name, "org.robolectric:android-all-instrumented:13-robolectric-9030017-i7")
val prepareAppPlatformSdks = tasks.register<Sync>("prepareAppPlatformSdks") {
    dependsOn(":core:designsystem:prepareThemePlatformSdks")
    from(rootProject.project(":core:designsystem").layout.buildDirectory.dir("theme-platform-sdks"))
    from(appPlatformSdk33)
    into(layout.buildDirectory.dir("platform-sdks"))
}
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("core/testing/fixtures/reference-codec"))
    systemProperty("referenceCodecFixtures",
        rootProject.layout.projectDirectory.dir("core/testing/fixtures/reference-codec").asFile.absolutePath)
    dependsOn(prepareAppPlatformSdks)
    systemProperty("wp502ScreenshotDirectory", rootProject.file("screenshot-tests/build/screens").absolutePath)
    forkEvery = 1
    jvmArgumentProviders.add(ContentPlatformSdkArguments(
        layout.buildDirectory.dir("platform-sdks"),
    ))
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

tasks.register("resolveContentHttpDependencies") {
    group = "verification"
    description = "Resolve only the eleven admitted App content HTTP classpaths and retain their actual graphs."
    val names = listOf(
        "debugCompileClasspath", "debugRuntimeClasspath",
        "debugUnitTestCompileClasspath", "debugUnitTestRuntimeClasspath",
        "debugAndroidTestCompileClasspath", "releaseCompileClasspath", "releaseRuntimeClasspath",
        "debugLintChecksClasspath", "debugUnitTestLintChecksClasspath",
        "debugAndroidTestLintChecksClasspath", "releaseLintChecksClasspath",
    )
    doLast {
        val directory = layout.buildDirectory.dir("reports/wp218/http-dependencies").get().asFile
        directory.mkdirs()
        names.forEach { name ->
            val configuration = configurations.findByName(name)
                ?: throw GradleException("Missing admitted HTTP configuration: $name")
            check(configuration.isCanBeResolved) { "Unresolvable admitted HTTP configuration: $name" }
            val graph = configuration.incoming.resolutionResult
            val rows = graph.allComponents.map {
                "${it.id.displayName}\t${it.moduleVersion?.version.orEmpty()}"
            }.sorted()
            check(rows.isNotEmpty()) { "Empty admitted HTTP dependency graph: $name" }
            directory.resolve("$name.tsv").writeText("component\tversion\n" + rows.joinToString("\n") + "\n")
            configuration.incoming.artifactView {
                componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            }.files.files
        }
    }
}

tasks.register("resolveWp302NavigationDependencies") {
    group = "verification"
    description = "Resolve only sixteen reviewed App navigation assembly/lint/unit/instrumentation/compiler graphs."
    val names = listOf(
        "debugCompileClasspath", "debugRuntimeClasspath",
        "debugUnitTestCompileClasspath", "debugUnitTestRuntimeClasspath",
        "debugAndroidTestCompileClasspath", "debugAndroidTestRuntimeClasspath",
        "releaseCompileClasspath", "releaseRuntimeClasspath",
        "debugLintChecksClasspath", "debugUnitTestLintChecksClasspath",
        "debugAndroidTestLintChecksClasspath", "releaseLintChecksClasspath",
        "kotlinCompilerPluginClasspathDebug", "kotlinCompilerPluginClasspathDebugUnitTest",
        "kotlinCompilerPluginClasspathDebugAndroidTest", "kotlinCompilerPluginClasspathRelease",
    )
    doLast {
        val directory = layout.buildDirectory.dir("reports/wp302/navigation-dependencies").get().asFile
        directory.mkdirs()
        names.forEach { name ->
            val configuration = configurations.findByName(name)
                ?: throw GradleException("Missing admitted navigation configuration: $name")
            check(configuration.isCanBeResolved) { "Unresolvable admitted navigation configuration: $name" }
            val rows = configuration.incoming.resolutionResult.allComponents.map {
                "${it.id.displayName}\t${it.moduleVersion?.version.orEmpty()}"
            }.sorted()
            check(rows.isNotEmpty()) { "Empty admitted navigation dependency graph: $name" }
            directory.resolve("$name.tsv").writeText("component\tversion\n" + rows.joinToString("\n") + "\n")
            configuration.incoming.artifactView {
                componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            }.files.files
        }
    }
}

val repository = rootProject.projectDir.parentFile
val verifyEmojiCatalog by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify the complete source-selected emoji catalog and its exact packaged notices."
    workingDir(repository)
    commandLine("python", "tools/android-port/emoji_convert.py", "--check")
    inputs.file(repository.resolve("tools/android-port/emoji_convert.py"))
    inputs.file(layout.projectDirectory.file("src/main/assets/emoji/emojibase.json"))
    inputs.files(listOf(
        "Emojibase-Bindings-Apache-2.0.txt", "Emojibase-Data-MIT.txt", "Unicode-License.txt",
    ).map { layout.projectDirectory.file("src/main/assets/licenses/$it") }).withPropertyName("emojiNotices")
}
tasks.named("preBuild") { dependsOn(verifyEmojiCatalog) }
val navigationInputBinding = layout.buildDirectory.file("reports/wp302/input-binding.properties")
val navigationArtifactDirectory = layout.buildDirectory.dir("reports/wp302/screens")
val prepareNavigationTestInputs by tasks.registering {
    group = "verification"
    description = "Provide the current source/input identity consumed by navigation tests."
    inputs.dir(layout.projectDirectory.dir("src/test/kotlin/com/meshcoreone/android/app/navigation"))
    outputs.file(navigationInputBinding)
    outputs.dir(navigationArtifactDirectory)
    doLast {
        val sourceRoot = layout.projectDirectory.dir("src/test/kotlin/com/meshcoreone/android/app/navigation").asFile
        val digest = MessageDigest.getInstance("SHA-256")
        sourceRoot.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(sourceRoot).path }.forEach {
            digest.update(it.relativeTo(sourceRoot).path.toByteArray())
            digest.update(it.readBytes())
        }
        fun gitRevision(revision: String) = ProcessBuilder("git", "rev-parse", revision)
            .directory(repository).start().inputStream.bufferedReader().readText().trim()
        val head = gitRevision("HEAD")
        val tree = gitRevision("HEAD^{tree}")
        navigationInputBinding.get().asFile.apply {
            parentFile.mkdirs()
            Properties().apply {
                setProperty("nonce", "navigation-test-inputs-v1")
                setProperty("head", head)
                setProperty("tree", tree)
                setProperty("inputs_sha256", digest.digest().joinToString("") { "%02x".format(it) })
            }.store(writer(), "Actual navigation test inputs")
        }
        navigationArtifactDirectory.get().asFile.mkdirs()
    }
}
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn(prepareNavigationTestInputs)
        systemProperty("navigationInputBinding", navigationInputBinding.get().asFile.absolutePath)
        systemProperty("navigationArtifactDirectory", navigationArtifactDirectory.get().asFile.absolutePath)
    }
}

// WP-218 diagnostic-only addition: hosted CI invokes the root verify stage with Gradle's
// `--quiet` flag, which otherwise suppresses the failing test's identity and stack trace even on
// a failed Test task (confirmed by direct inspection of tools/android-port/controller/ci.py and
// the uploaded CI artifact, which contains no JUnit XML/report on a failed run). This mirrors the
// exact same local pattern already used by core/designsystem/build.gradle.kts for the same
// reason: no behavior change, no new dependency/config; `logger.error` always prints regardless
// of the configured Gradle log level, so this is the one reliable way to see which test failed.
// AndroidOnly: WP-501 Quiet-visible path-recency observation; no task or test outcome changes.
tasks.configureEach {
    if (name == "compileDebugKotlin" || name == "compileDebugUnitTestKotlin") {
        doFirst { logger.quiet("WP501_PATH_RECENCY|TASK|$path|START") }
        doLast { logger.quiet("WP501_PATH_RECENCY|TASK|$path|END") }
    }
}
tasks.withType<Test>().configureEach {
    testLogging.quiet {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    val failureLogger = logger
    val pathRecencyClass = "com.meshcoreone.android.parity.AdvertisementPathRecencyOriginalPredicateTest"
    val rollbackClass = "com.meshcoreone.android.parity.AdvertisementRollbackOriginalPredicateTest"
    val rollbackTestNames = setOf(
        "originalSuccessfulCommitRemovesTheRealResurrectedRowWithoutDiscoverOrNotification",
        "originalReAdvertClearsTheDeleteAndKeepsTheReaddedRoomContactAndNewHistory",
        "originalFailedCommitRollsBackItsRealEarlyWriteAndCannotRequeueTheDeletedKey",
        "originalCancelledTeardownStillRollsBackTheRealCommitLandingAfterStop",
    ).flatMap { method -> listOf(method, "$method()", "$method[31]", "$method[31]()") }.toSet()
    val pathRecencyPhases = setOf(
        "room-open", "room-close", "room-seed", "room-contact-check", "room-final-check",
        "session-start", "session-stop", "service-monitor-start", "service-initial-drain",
        "body", "predicate", "service-monitor-stop", "owner-cancel-join",
        "service-events-finish", "service-listener-join", "protocol-listener-join",
    )
    val pathRecencyMarkers = pathRecencyPhases.flatMap { phase ->
        listOf("WP501_PATH_RECENCY_PHASE|$phase|START", "WP501_PATH_RECENCY_PHASE|$phase|END")
    }.toSet()
    val pathRecencyOutput = mutableMapOf<String, StringBuilder>()
    val pathRecencyMarkerLimit = pathRecencyMarkers.maxOf { it.length }
    doFirst { logger.quiet("WP501_PATH_RECENCY|TASK|$path|START") }
    doLast { logger.quiet("WP501_PATH_RECENCY|TASK|$path|END") }
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
        override fun beforeTest(testDescriptor: TestDescriptor) {
            if (testDescriptor.className == pathRecencyClass) {
                failureLogger.quiet("WP501_PATH_RECENCY|TEST|${testDescriptor.className}|${testDescriptor.name}|START")
            }
            if (testDescriptor.className == rollbackClass && testDescriptor.name in rollbackTestNames) {
                failureLogger.quiet("WP501_ADVERT_ROLLBACK|TEST|${testDescriptor.className}|${testDescriptor.name}|START")
            }
        }
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            if (testDescriptor.className == pathRecencyClass) {
                synchronized(pathRecencyOutput) { pathRecencyOutput.remove(testDescriptor.name) }
                failureLogger.quiet(
                    "WP501_PATH_RECENCY|TEST|${testDescriptor.className}|${testDescriptor.name}|END|${result.resultType}",
                )
            }
            if (testDescriptor.className == rollbackClass && testDescriptor.name in rollbackTestNames) {
                failureLogger.quiet(
                    "WP501_ADVERT_ROLLBACK|TEST|${testDescriptor.className}|${testDescriptor.name}|END|${result.resultType}",
                )
            }
            if (result.resultType == TestResult.ResultType.FAILURE) {
                failureLogger.error("WP218_NATIVE_FAILURE|${testDescriptor.className}|${testDescriptor.name}")
                result.exceptions.forEach { failure ->
                    failureLogger.error("WP218 native failure stack", failure)
                }
            }
        }
    })
    addTestOutputListener { descriptor, event ->
        if (descriptor.className == pathRecencyClass && event.destination == TestOutputEvent.Destination.StdOut) {
            synchronized(pathRecencyOutput) {
                val pending = pathRecencyOutput.getOrPut(descriptor.name) { StringBuilder() }
                event.message.forEach { character ->
                    if (character == '\n' || character == '\r') {
                        val marker = pending.toString()
                        pending.setLength(0)
                        if (marker in pathRecencyMarkers) {
                            failureLogger.quiet(
                                "WP501_PATH_RECENCY|PHASE|${descriptor.className}|${descriptor.name}|" +
                                    marker.removePrefix("WP501_PATH_RECENCY_PHASE|"),
                            )
                        }
                    } else if (pending.length <= pathRecencyMarkerLimit) {
                        pending.append(character)
                    }
                }
            }
        }
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Versions are pinned once at the root (apply false); modules apply by bare id. The Kotlin
// plugins (multiplatform/jvm) and the AGP plugins (application/library/kmp-library) each share a
// single backing artifact across ids, so requesting a version here would conflict with the
// artifact already on the classpath.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    // Room's KSP generates an `actual` object for the @ConstructedBy database constructor; the
    // expect/actual-class feature it relies on is stable enough in practice but still flagged Beta.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "one.meshcore.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // CMP-9547: Compose Multiplatform strings/drawables are dropped from the APK unless
        // Android resource processing is on, even when the files live under composeResources/.
        androidResources { enable = true }
        withHostTest {
            isIncludeAndroidResources = true
        }
        // Single-variant plugin does not publish consumer keep rules unless asked.
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }
    }
    // desktop JVM compiles the shared shell and the unit tests. Android SDK types (the radio
    // session, BLE, MapLibre, Play Billing) stay in androidMain behind the commonMain interface.
    // Do not add an expect/actual for a new Android-only SDK. Shared JVM code goes in
    // jvmCommonMain. desktopMain only fills an expect the hierarchy already requires.
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Apply first so the default template is not skipped; then add the extra JVM-shared set.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.koala.plot)
            // System back via Navigation Event (NavigationBackHandler). Replaces deprecated ui-backhandler.
            // Android: Activity 1.13 hosts NavigationEventDispatcherOwner. Desktop Escape: Compose UI host.
            implementation(libs.androidx.navigationevent.compose)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.serialization.json)
            // Room KMP: the runtime is multiplatform; the bundled SQLite driver compiles the engine
            // from source so the Android target and the desktop JVM share one SQLite. The room-compiler is
            // added per target as a KSP dependency below (there is no common KSP configuration).
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            // Pure-Kotlin QR generation backing the contact/channel QrEncoder seams; one commonMain
            // impl serves android and desktop. The QR payloads are frozen by the wire-format goldens.
            implementation(libs.qrcode.kotlin)
            // HTTP client for link previews and the map elevation/geocoder services; the engine is
            // supplied per JVM target from jvmCommonMain.
            implementation(libs.ktor.client.core)
            // Inline chat images (AsyncImage); the network fetcher rides the shared Ktor client.
            // Compose 1.12 supplies Skiko 0.150.1. Coil 3.3's older transitive Skiko is excluded so
            // desktop has one native/API version; newer Coil releases require Kotlin 2.4 metadata.
            implementation("io.coil-kt.coil3:coil-compose:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-network-ktor3:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            // KMP HTML parsing for link-preview scraping (OpenGraph/title extraction).
            implementation(libs.ksoup)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val jvmCommonTest by creating {
            dependsOn(commonTest.get())
        }
        named("desktopTest") { dependsOn(jvmCommonTest) }
        named("androidHostTest") { dependsOn(jvmCommonTest) }
        // An intermediate source set shared by both JVM targets (Android + desktop). The WiFi/TCP
        // transport's one real-socket adapter (java.net.Socket) lives here so a single implementation
        // serves Android and the desktop (Windows) build, rather than an expect/actual pair with a
        // duplicated actual per target.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // DataStore Preferences backs PreferencesStore for both JVM targets (Android + desktop);
                // it is file-backed and multiplatform, so one impl serves both. Brings okio transitively.
                implementation(libs.androidx.datastore.preferences.core)
                // One Ktor engine for both JVM targets, like the socket transport this set hosts.
                implementation(libs.ktor.client.okhttp)
            }
        }
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
        }
        desktopMain.dependencies {
            implementation(libs.compose.desktop)
            implementation(libs.kotlinx.coroutines.swing)
            // `desktop` is JVM API only. Image.makeFromEncoded needs libskiko from the current-OS
            // artifact; without it desktopTest (and any desktop decode) fails closed to null.
            implementation(compose.desktop.currentOs)
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }
        androidMain.dependencies {
            // The Swift MeshCore bridge: the generated `one.meshcore.bridge.MeshCoreBridge` command
            // bindings plus the `libMeshCoreBridgeKit.so` the JNI shim's `nativeCreate` lives in.
            implementation(project(":swift-core"))
            // Kable: the BLE transport for the Kotlin `MeshCoreSession`. androidMain only.
            implementation(libs.kable.core)
            // MapLibre Android host for the Map tab (W6). Desktop uses the list interim instead.
            implementation(libs.maplibre.android.sdk)
            // Animated GIF decoder for inline chat images (autoplay/loop); Android-only.
            implementation(libs.coil.gif)
            // Play Billing for Support / theme-bundle / tips (W13). Desktop keeps NoOpBillingClient.
            implementation(libs.billing.client)
            implementation(libs.mlkit.language.id)
            implementation(libs.mlkit.translate)
        }
    }
}

// Room's annotation processor must run for every Kotlin target that owns the @Database. There is no
// common KSP configuration, so each target's room-compiler is registered explicitly.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}

// Room writes the production schema here because MeshDatabase exports it. Both KSP targets share
// one directory; the JSON is the schema-11 contract.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The backup decode tests load the frozen cross-platform fixtures. Point every JVM test task
// (desktop + androidHostTest) at the repo's canonical fixtures dir so the path is deterministic and
// independent of the process working directory. rootProject is multiplatform/; the fixtures live one
// level up at repo/fixtures/backup.
tasks.withType<Test>().configureEach {
    systemProperty(
        "mc1.fixturesDir",
        rootProject.layout.projectDirectory.dir("../fixtures/backup").asFile.absolutePath,
    )
    systemProperty(
        "mc1.repoFixturesDir",
        rootProject.layout.projectDirectory.dir("../fixtures").asFile.absolutePath,
    )
    // The bridge boundary golden vectors (the Kotlin side of the JNI encoding freeze).
    systemProperty(
        "mc1.bridgeFixturesDir",
        rootProject.layout.projectDirectory.dir("../fixtures/bridge").asFile.absolutePath,
    )
    // Compose multiplatform string resources for locale key-parity tests (W14).
    systemProperty(
        "mc1.composeResourcesDir",
        layout.projectDirectory.dir("src/commonMain/composeResources").asFile.absolutePath,
    )
}

androidComponents {
    onVariants { variant ->
        // Variant-level republish in addition to optimization.consumerKeepRules; without this
        // the JNI keep rules in consumer-rules.pro are dropped from the AAR.
        variant.consumerProguardFiles.add(layout.projectDirectory.file("consumer-rules.pro"))
    }
}

compose.resources {
    // The generated Res class must be public so :androidApp and :desktopApp can read
    // localized strings from the shared module.
    publicResClass = true
    packageOfResClass = "one.meshcore.shared.resources"
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    source.setFrom(
        "src/commonMain/kotlin",
        "src/jvmCommonMain/kotlin",
        "src/androidMain/kotlin",
        "src/desktopMain/kotlin",
        "src/commonTest/kotlin",
        "src/jvmCommonTest/kotlin",
        "src/androidHostTest/kotlin",
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude("**/ui/icons/symbols/MaterialSymbols.kt")
}

// Regenerates Compose string resources from the iOS .strings source of truth.
// Generated files are checked in (like SwiftGen output on the iOS side); CI can run this
// task and diff to catch drift. Add locales as later phases translate them.
tasks.register<Exec>("convertStrings") {
    group = "localization"
    description =
        "Convert iOS .strings and .stringsdict (plus the Android supplement) into Compose string resources for all locales."
    val script = layout.projectDirectory.file("../scripts/strings-to-compose.py")
    val iosRoot = layout.projectDirectory.dir("../../MC1/Resources/Localization")
    val supplementRoot = layout.projectDirectory.dir("../localization-supplement")
    val outRoot = layout.projectDirectory.dir("src/commonMain/composeResources")
    val androidAppRes = layout.projectDirectory.dir("../androidApp/src/main/res")
    inputs.file(script)
    inputs.dir(iosRoot)
    inputs.dir(supplementRoot)
    outputs.dir(outRoot)
    commandLine(
        "python3",
        script.asFile.absolutePath,
        "--ios-root",
        iosRoot.asFile.absolutePath,
        "--out-root",
        outRoot.asFile.absolutePath,
        "--supplement-root",
        supplementRoot.asFile.absolutePath,
        "--android-app-res",
        androidAppRes.asFile.absolutePath,
        "--locales",
        "en,de,es,fr,nl,pl,ru,uk,zh-Hans,it,pt,ko",
    )
}

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
// Ship one ABI per sideload artifact. Native libs (Swift bridge, ML Kit, MapLibre) are stored
// uncompressed, so the APK size is ~the byte sum of every bundled .so; the four ABIs an unfiltered
// merge produces are pure overhead for the recipient's one phone. Default is arm64-v8a (every modern
// phone); MESH_CORE_ONE_ABIS (comma-separated) overrides it for a universal or per-ABI build.
val shippedAbis = (
    System.getenv("MESH_CORE_ONE_ABIS")?.takeIf { it.isNotBlank() }
        ?: "arm64-v8a"
    ).split(",").map { it.trim() }.filter { it.isNotEmpty() }

// Versions pinned once at the root (apply false); apply by bare id here. See :shared for why.
plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

// Self-signed release key read from gitignored local properties; CI supplies the same four
// values through ANDROID_KEYSTORE_-prefixed environment variables. With no key present the release
// build stays unsigned, so local debug builds and CI lint/test jobs never depend on the secret.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun ksProp(name: String): String? =
    System.getenv("ANDROID_KEYSTORE_" + name.uppercase()) ?: keystoreProps.getProperty(name)

android {
    namespace = "one.meshcore.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        val storeFile = ksProp("storeFile")
        if (storeFile != null) {
            create("release") {
                // Relative paths resolve against the module dir; anchor to root when absent.
                val file = rootProject.file(storeFile)
                this.storeFile = if (file.exists()) file else project.file(storeFile)
                storePassword = ksProp("storePassword")
                keyAlias = ksProp("keyAlias")
                keyPassword = ksProp("keyPassword")
            }
        }
    }

    defaultConfig {
        // Kotlin package naming is ours (one.meshcore.*); the shipping applicationId stays io.pocketmesh.app.
        // Keep the id in a val so Play-listing decisions later can flip it without hunting through the DSL.
        applicationId = "io.pocketmesh.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.5.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // One ABI per artifact (see shippedAbis). Filters the merged native libs from the Swift
        // bridge, ML Kit, and MapLibre down to the recipient's actual CPU.
        ndk { abiFilters += shippedAbis }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Only attach signing when the self-signed key is present; otherwise the release build
            // stays unsigned (lint/test jobs and contributors without the secret still build).
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // minSdk 28 would otherwise store uncompressed, aligned dex (faster install). A sideload
    // APK is the download, so deflate the dex entries. Native libs stay stored for 16 KB mapping.
    packaging {
        dex {
            useLegacyPackaging = true
        }
    }

    lint {
        // API 37 is the preview compile surface required by Compose 1.12; API 36 is the current Play target.
        disable += "OldTargetApi"
        // Release APKs intentionally contain one sideload ABI; MESH_CORE_ONE_ABIS can request a universal artifact.
        disable += "ChromeOsAbiSupport"
    }
}

abstract class ExportSideloadApk : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputApk: RegularFileProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @TaskAction
    fun export() {
        val artifacts = checkNotNull(builtArtifactsLoader.get().load(apkDirectory.get())) {
            "Cannot load release APK metadata"
        }
        check(artifacts.elements.size == 1) { "Expected a single release APK" }
        val destination = outputApk.get().asFile
        destination.parentFile.mkdirs()
        File(artifacts.elements.single().outputFile).copyTo(destination, overwrite = true)
    }
}

// Keep AGP's APK and metadata intact; the sideload copy has its own ABI-labelled output.
val abiReleaseTag = shippedAbis.joinToString("-")
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val exportTask = tasks.register<ExportSideloadApk>("exportReleaseApk") {
            outputApk.set(
                layout.buildDirectory.file("outputs/sideload/androidApp-${variant.name}-$abiReleaseTag.apk"),
            )
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
        }
        variant.artifacts.use(exportTask).wiredWith(ExportSideloadApk::apkDirectory).toListenTo(SingleArtifact.APK)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    // AppContainer touches MeshDatabase members directly, which needs the RoomDatabase supertype
    // on this module's compile classpath.
    implementation(libs.androidx.room.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // CameraX + ML Kit back the contact/channel QR scanners (the ContactQrScanner seam); desktop
    // falls back to paste, so the camera stack stays Android-only.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.junit)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "one.meshcore.chartbench"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Isolated from the shipping sideload package. Macrobenchmark installs only this id.
        applicationId = "one.meshcore.chartbench"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "benchmark"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            isProfileable = true
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The chart composables stay internal. This benchmark target is the only extra friend.
val sharedFriendClasses = providers.provider {
    configurations.getByName("benchmarkCompileClasspath").incoming.artifactView {
        componentFilter { id -> id is ProjectComponentIdentifier && id.projectPath == ":shared" }
        attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
    }.files
}
tasks.withType<KotlinCompile>().configureEach {
    friendPaths.from(sharedFriendClasses)
}

tasks.withType<KotlinCompile>().matching { it.name == "compileDebugUnitTestKotlin" }.configureEach {
    source(
        rootProject.file("chartBenchmarkMacro/src/main/kotlin/one/meshcore/chartbench/macro/ChartFrameAcceptance.kt"),
    )
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":shared"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime.tracing)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

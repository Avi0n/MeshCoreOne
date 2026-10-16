import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("androidx.benchmark")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "one.meshcore.shared.benchmarks"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }
    // The benchmark plugin configures release signing, compilation and result collection.
    testBuildType = "release"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Internal bridge installers remain internal; access follows Gradle's resolved shared classes.
val sharedFriendClasses = providers.provider {
    configurations.getByName("releaseAndroidTestCompileClasspath").incoming.artifactView {
        componentFilter { id -> id is ProjectComponentIdentifier && id.projectPath == ":shared" }
        attributes.attribute(
            org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
            "android-classes-jar",
        )
    }.files
}
tasks.withType<KotlinCompile>().matching { it.name == "compileReleaseAndroidTestKotlin" }.configureEach {
    friendPaths.from(sharedFriendClasses)
}

dependencies {
    androidTestImplementation(project(":shared"))
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    source.setFrom("src/androidTest/kotlin")
}

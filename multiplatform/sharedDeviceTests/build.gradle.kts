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
    namespace = "one.meshcore.shared.devicetests"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "one.meshcore.shared.test"
        minSdk = libs.versions.minSdk.get().toInt()
        // Match the KMP test target independently of the shipping app target.
        targetSdk = libs.versions.compileSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("androidTest").kotlin.srcDir(project(":shared").file("src/androidDeviceTest/kotlin"))
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Resolve the producer's classes through Gradle so internal test access follows the compiled module.
val sharedFriendClasses = providers.provider {
    configurations.getByName("debugAndroidTestCompileClasspath").incoming.artifactView {
        componentFilter { id -> id is ProjectComponentIdentifier && id.projectPath == ":shared" }
        attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
    }.files
}
tasks.withType<KotlinCompile>().matching { it.name == "compileDebugAndroidTestKotlin" }.configureEach {
    friendPaths.from(sharedFriendClasses)
}

dependencies {
    implementation(project(":shared"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.runtime)
    androidTestImplementation(libs.compose.foundation)
    androidTestImplementation(libs.compose.material3)
    androidTestImplementation(libs.compose.components.resources)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.sqlite.bundled)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.kotlinx.serialization.json)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    source.setFrom(project(":shared").file("src/androidDeviceTest/kotlin"))
}

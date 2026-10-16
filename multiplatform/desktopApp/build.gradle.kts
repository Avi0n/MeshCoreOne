import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desktop is a stub host (no radio). Android-only source sets are the default path for
// map/BLE/Play. Do not block an Android change on a JVM actual. Windows is later.

// Versions pinned once at the root (apply false); apply by bare id here. See :shared for why.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Match Java to the Kotlin JVM target so compileJava and compileKotlin agree (the daemon
// runs on JDK 21, whose default Java target would otherwise be 21).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.compose.desktop)
    implementation(libs.compose.components.resources)
}

compose.desktop {
    application {
        mainClass = "one.meshcore.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MeshCoreOne"
            packageVersion = "1.0.0"
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    source.setFrom("src/main/kotlin")
}

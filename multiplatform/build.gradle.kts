// Root build script. Plugins are declared here (apply false) so subprojects can
// reference them from the version catalog; lint gates are configured per Kotlin module.
plugins {
    alias(libs.plugins.androidxBenchmark) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ksp) apply false
}

subprojects {
    // The Compose resources plugin injects generated Res accessors into the Kotlin source sets.
    // Linter source filters only see declared source dirs, not those injected ones, so exclude
    // generated code at the task level (its source tree spans build/generated) instead.
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask>().configureEach {
            exclude { it.file.absolutePath.contains("/build/generated/") }
            exclude { it.file.name == "MaterialSymbols.kt" }
        }
        tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask>().configureEach {
            exclude { it.file.absolutePath.contains("/build/generated/") }
            exclude { it.file.name == "MaterialSymbols.kt" }
        }
    }
    plugins.withId("io.gitlab.arturbosch.detekt") {
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            exclude { it.file.absolutePath.contains("/build/generated/") }
        }
    }
}

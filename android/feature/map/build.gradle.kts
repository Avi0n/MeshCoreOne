// AndroidOnly: WP-312 Map feature UI backed by shared MapLibre rendering contracts.
plugins { id("mesh.android.feature") }
dependencies {
    implementation(project(":core:maps"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}
rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:map:testDebugUnitTest") }

// AndroidOnly: WP-002 Remote-node entry shell, not an extra main tab or admin implementation.
// AndroidOnly: WP-313 Remote-node admin/telemetry logic; its JVM suite joins the root unit-test collection.
plugins { id("mesh.android.feature") }

dependencies {
    implementation(project(":core:maps"))
    implementation(libs.androidx.activity.compose)
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:remotenodes:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }

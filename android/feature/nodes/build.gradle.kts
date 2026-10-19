// AndroidOnly: WP-002 Nodes entry shell; auxiliary routes go through app registration.
// AndroidOnly: WP-311 Nodes logic layer (state holders, path editing, share/scan logic) and its JVM unit suite.
plugins { id("mesh.android.feature") }

dependencies {
    implementation(project(":core:maps"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:nodes:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }

// AndroidOnly: WP-002 Shortcut boundary without external/device-changing actions.
plugins {
    id("mesh.android.library")
    id("mesh.android.robolectric")
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    implementation(project(":core:l10n"))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":platform:shortcuts:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }

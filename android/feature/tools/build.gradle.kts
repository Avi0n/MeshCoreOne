// AndroidOnly: WP-002 Tools entry shell; no real or simulated radio operation.
// AndroidOnly: WP-314/WP-315 Tools JVM suites and the approved WP-312 map surface dependency.
plugins { id("mesh.android.feature") }

dependencies {
    implementation(project(":core:maps"))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:tools:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }

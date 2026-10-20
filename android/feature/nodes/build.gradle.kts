// AndroidOnly: WP-002 Nodes entry shell; auxiliary routes go through app registration.
// AndroidOnly: WP-311 Nodes logic layer (state holders, path editing, share/scan logic) and its JVM unit suite.
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("mesh.android.feature")
    id("mesh.android.robolectric")
}

dependencies {
    implementation(project(":core:maps"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}

val api37TestSdk = configurations.create("testRobolectricSdk37") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(api37TestSdk.name, "org.robolectric:android-all-instrumented:17-robolectric-15733970-i7")

val robolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdks")
val prepareRobolectricSdks by tasks.registering(Sync::class) {
    from(configurations.named("testRobolectricSdk"))
    from(api37TestSdk)
    into(robolectricSdkDirectory)
}

class NodesRobolectricSdkArguments(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdks)
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    jvmArgumentProviders.add(NodesRobolectricSdkArguments(robolectricSdkDirectory))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:nodes:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }

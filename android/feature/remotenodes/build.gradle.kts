// AndroidOnly: WP-002 Remote-node entry shell, not an extra main tab or admin implementation.
// AndroidOnly: WP-313 Remote-node admin/telemetry logic; its JVM suite joins the root unit-test collection.
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("mesh.android.feature")
    id("mesh.android.robolectric")
}

dependencies {
    implementation(project(":core:maps"))
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

val remoteNodesSdk37 = configurations.create("remoteNodesSdk37") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(remoteNodesSdk37.name, "org.robolectric:android-all-instrumented:17-robolectric-15733970-i7")
val prepareRemoteNodesPlatformSdks by tasks.registering(Sync::class) {
    from(configurations.named("testRobolectricSdk"))
    from(remoteNodesSdk37)
    into(layout.buildDirectory.dir("remote-nodes-platform-sdks"))
}
class RemoteNodesPlatformSdkArguments(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRemoteNodesPlatformSdks)
    jvmArgumentProviders.add(RemoteNodesPlatformSdkArguments(layout.buildDirectory.dir("remote-nodes-platform-sdks")))
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:remotenodes:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }

import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :swift-core cross-compiles the MeshCoreBridge Swift package for Android, runs swift-java's
// jextract (JNI mode) over its surface, and packages the resulting .so's + Java bindings into a
// consumable .aar. The heavy lifting is SwiftPM invoked through swiftly; Gradle orchestrates the
// per-ABI builds, staging, a 16 KB page-alignment gate, and the Android library packaging.
//
// One-time prerequisite: org.swift.swiftkit:swiftkit-core must be in mavenLocal (swift-java is
// pre-1.0 and not on Maven Central). See README.md for the JDK-25 publish command.
//
// Versions pinned once at the root (apply false); apply by bare id here. See :shared for why.
plugins {
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

// ---------------------------------------------------------------------------------------------
// Pinned Swift toolchain coordinates. These are not Gradle dependencies (SwiftPM resolves the
// Swift graph), so they live here rather than in the version catalog. Each pin is deliberate;
// the reasons mirror thoughts/multiplatform/STATUS.md.
// ---------------------------------------------------------------------------------------------
// Swift toolchain selected via swiftly. Must match the installed Android SDK bundle below.
val swiftVersion = "6.4.0"
// Android Swift SDK artifactbundle. Installed via `swift sdk install` during toolchain setup.
val swiftAndroidSdkBundle = "swift-$swiftVersion-RELEASE_android"
val androidNdkVersion = "30.0.16248370"
// swift-java is pinned by exact revision in MeshCoreBridge/Package.swift (tag 0.4.2,
// 3555583ad6ed9cc14461087b4b088dcdcf510cf0); the Kotlin-side runtime is libs.swiftkit.core (0.4.2).
// minSdk drives the Android target API level baked into the Swift triple (…-android<level>).
val androidApiLevel = libs.versions.minSdk.get().toInt()

// The MeshCoreBridge SwiftPM package (repo root, a sibling of multiplatform/).
val bridgeDir = rootProject.projectDir.parentFile.resolve("MeshCoreBridge")
val meshCoreDir = rootProject.projectDir.parentFile.resolve("MeshCore")

// Home-relative toolchain locations. swiftly and the Swift SDKs are per-user installs.
val homeDir: String = System.getProperty("user.home")
val swiftlyBin = "$homeDir/.swiftly/bin/swiftly"
val swiftSdkBundleDir = "$homeDir/Library/org.swift.swiftpm/swift-sdks/$swiftAndroidSdkBundle.artifactbundle"

// Swift 6.4 evaluates Package.swift against the active host SDK. The override remains available
// for diagnosing future host-toolchain regressions without pinning every build to an older SDK.
val hostSwiftPmSdk: String? = System.getenv("MESHCORE_SWIFTPM_HOST_SDK")

// The Android NDK supplies llvm-readelf/llvm-strip and libc++_shared.so. Its libc++ must match the
// pinned toolchain; an unrelated ANDROID_NDK_HOME can produce an APK that fails during native load.
val sdkDir = providers.gradleProperty("sdk.dir").orNull
    ?: File(rootProject.projectDir, "local.properties").takeIf { it.exists() }?.let { lp ->
        lp.readLines().firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
    }
    ?: "$homeDir/Library/Android/sdk"
val pinnedNdkDir = File("$sdkDir/ndk/$androidNdkVersion")
val environmentNdkDir = System.getenv("ANDROID_NDK_HOME")?.let(::File)
val environmentNdkVersion = environmentNdkDir
    ?.resolve("source.properties")
    ?.takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.startsWith("Pkg.Revision") }
    ?.substringAfter("=")
    ?.trim()
val ndkDir: String = when {
    pinnedNdkDir.isDirectory -> pinnedNdkDir.absolutePath
    environmentNdkVersion == androidNdkVersion -> requireNotNull(environmentNdkDir).absolutePath
    else -> error(
        "NDK $androidNdkVersion not found under $sdkDir/ndk or ANDROID_NDK_HOME; " +
            "found ${environmentNdkVersion ?: "no usable NDK"}.",
    )
}
val ndkPrebuiltDir: File = File("$ndkDir/toolchains/llvm/prebuilt").listFiles()
    ?.singleOrNull { it.isDirectory }
    ?: error("Expected one NDK llvm prebuilt directory under $ndkDir.")
val llvmBinDir: String = ndkPrebuiltDir.resolve("bin").absolutePath

// One statically-linked .so per ABI. Alignment (16 KB) is verified before packaging. We ship
// arm64-v8a (devices) and x86_64 (emulators) only; armeabi-v7a is intentionally dropped.
data class Abi(
    val androidAbi: String,
    val swiftTriple: String, // --swift-sdk selector, e.g. aarch64-unknown-linux-android28
    val ndkTriple: String, // libc++_shared.so dir, e.g. aarch64-linux-android
    val swiftArch: String, // swift_static-<arch> resource dir, e.g. aarch64
    val taskSuffix: String, // capitalized fragment for task names, e.g. Arm64
)

// Both ABIs ship by default. SWIFT_CORE_ABIS (comma-separated) narrows the set for faster local
// iteration or a disk-constrained one-ABI-at-a-time build; unset builds everything.
val abis = listOf(
    Abi("arm64-v8a", "aarch64-unknown-linux-android$androidApiLevel", "aarch64-linux-android", "aarch64", "Arm64"),
    Abi("x86_64", "x86_64-unknown-linux-android$androidApiLevel", "x86_64-linux-android", "x86_64", "X64"),
).filter { System.getenv("SWIFT_CORE_ABIS")?.split(",")?.contains(it.androidAbi) ?: true }

// The single .so SwiftPM emits per ABI (product MeshCoreBridgeKit -> libMeshCoreBridgeKit.so),
// with MeshCore, swift-java, and the Swift runtime statically linked in.
val bridgeSoName = "libMeshCoreBridgeKit.so"

// Where staged .so's and generated Java land inside the module build dir.
val stagedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val jextractJavaDir = bridgeDir.resolve(
    ".build/plugins/outputs/meshcorebridge/MeshCoreBridgeKit/destination/JExtractSwiftPlugin/src/generated/java",
)

// ---------------------------------------------------------------------------------------------
// Per-ABI Swift cross-build. Each runs one `swift build`; the manifest reads the static-stdlib
// resource dir from MESHCORE_ANDROID_SWIFT_STATIC_RESOURCE_DIR and links the Swift runtime in.
// Package.swift is touched first so SwiftPM re-evaluates the manifest and re-reads the env var
// (the resource dir differs per ABI); the builds must therefore not run in parallel.
// ---------------------------------------------------------------------------------------------
val swiftBuildTasks = abis.map { abi ->
    tasks.register<Exec>("swiftBuild${abi.taskSuffix}") {
        group = "swift"
        description = "Cross-compiles MeshCoreBridge for ${abi.androidAbi} (release, static-stdlib)."

        val staticResourceDir =
            "$swiftSdkBundleDir/swift-android/swift-resources/usr/lib/swift_static-${abi.swiftArch}/"
        val builtSo = bridgeDir.resolve(".build/out/Products/Release-android-${abi.swiftArch}/$bridgeSoName")

        inputs.dir(bridgeDir.resolve("Sources"))
        inputs.file(bridgeDir.resolve("Package.swift"))
        inputs.dir(meshCoreDir.resolve("Sources"))
        inputs.file(meshCoreDir.resolve("Package.swift"))
        outputs.file(builtSo)
        outputs.dir(jextractJavaDir)

        workingDir(bridgeDir)
        executable(swiftlyBin)
        args(
            "run", "+$swiftVersion", "swift", "build",
            "--swift-sdk", swiftAndroidSdkBundle, "--triple", abi.swiftTriple, "-c", "release",
        )

        environment("SWIFT_VERSION", swiftVersion)
        environment("MESHCORE_ANDROID_SWIFT_STATIC_RESOURCE_DIR", staticResourceDir)
        // The jextract build-tool plugin runs a host JVM step; point it at the Gradle daemon's JDK.
        environment("JAVA_HOME", System.getProperty("java.home"))
        hostSwiftPmSdk?.let { environment("SDKROOT", it) }

        doFirst {
            if (!file(swiftlyBin).exists()) error("swiftly not found at $swiftlyBin.")
            if (!file(staticResourceDir).exists()) error("Static Swift resources not found: $staticResourceDir.")
            // Force a manifest re-evaluation so the per-ABI resource dir env var is re-read.
            bridgeDir.resolve("Package.swift").setLastModified(System.currentTimeMillis())
        }
    }
}
// Serialize the ABI builds: they share one Package.swift whose manifest re-read is order-sensitive.
swiftBuildTasks.zipWithNext().forEach { (first, second) -> second.configure { mustRunAfter(first) } }

// ---------------------------------------------------------------------------------------------
// Stage each ABI's stripped .so plus libc++_shared.so into the module's jniLibs.
// ---------------------------------------------------------------------------------------------
val stageTasks = abis.mapIndexed { index, abi ->
    tasks.register<Copy>("stageJniLibs${abi.taskSuffix}") {
        group = "swift"
        description = "Strips and stages the ${abi.androidAbi} .so and libc++_shared.so."
        dependsOn(swiftBuildTasks[index])

        val builtSo = bridgeDir.resolve(".build/out/Products/Release-android-${abi.swiftArch}/$bridgeSoName")
        val libcxx = ndkPrebuiltDir.resolve("sysroot/usr/lib/${abi.ndkTriple}/libc++_shared.so")

        from(builtSo)
        from(libcxx)
        into(stagedJniLibsDir.map { it.dir(abi.androidAbi) })

        doFirst {
            // Copy.from() silently skips a missing file; guard so we never ship the bridge .so
            // without its C++ runtime.
            require(libcxx.exists()) { "libc++_shared.so not found for ${abi.androidAbi}: $libcxx" }
        }
        doLast {
            // Strip the bridge .so in place. The unstripped file still carries debug sections;
            // after strip it should be ~34 MB with the filtered ICU blob (it was ~58 MB with
            // the full packaged icudt76_dat). libc++_shared.so ships as the NDK provides it.
            val stagedBridge = stagedJniLibsDir.get().dir(abi.androidAbi).file(bridgeSoName).asFile
            providers.exec {
                commandLine("$llvmBinDir/llvm-strip", stagedBridge.absolutePath)
            }.result.get().assertNormalExitValue()

            val maxStrippedBridgeBytes = 42L * 1024L * 1024L
            val strippedBytes = stagedBridge.length()
            require(strippedBytes <= maxStrippedBridgeBytes) {
                "Stripped $bridgeSoName for ${abi.androidAbi} is $strippedBytes bytes; " +
                    "expected <= $maxStrippedBridgeBytes. The full packaged ICU blob was " +
                    "probably linked instead of MeshCoreAndroidICUData."
            }
            logger.lifecycle(
                "Stripped $bridgeSoName ${abi.androidAbi}: $strippedBytes bytes",
            )

            // The jextract-generated MeshCoreBridge bootstrap unconditionally loads a separate
            // libSwiftJava.so by name, but swift-java is statically linked into libMeshCoreBridgeKit.so
            // (the single-.so rule), so no such library ships. Emit an empty, 16 KB-aligned stub so
            // System.loadLibrary("SwiftJava") succeeds without pulling in a second Swift runtime.
            val stub = stagedJniLibsDir.get().dir(abi.androidAbi).file("libSwiftJava.so").asFile
            providers.exec {
                commandLine(
                    "$llvmBinDir/clang",
                    "--target=${abi.ndkTriple}$androidApiLevel",
                    "-shared", "-nostdlib",
                    "-Wl,-z,max-page-size=16384",
                    "-Wl,-soname,libSwiftJava.so",
                    "-o", stub.absolutePath,
                    "-x", "c", "/dev/null",
                )
            }.result.get().assertNormalExitValue()
        }
    }
}

// Swift Build exposes the most recent target through shared .build links. Stage each ABI before
// starting the next one so generated bindings and target-specific products cannot be overwritten.
stageTasks.zip(swiftBuildTasks.drop(1)).forEach { (stage, nextBuild) ->
    nextBuild.configure { mustRunAfter(stage) }
}

val stageAllJniLibs = tasks.register("stageJniLibs") {
    group = "swift"
    description = "Builds and stages every shipped ABI's .so."
    dependsOn(stageTasks)
}

// Verify that every Android-libc++ symbol required by the bridge exists in the staged runtime.
// This catches a mismatched NDK before the APK reaches System.loadLibrary on a device.
val verifyCxxRuntimeCompatibility = tasks.register("verifyCxxRuntimeCompatibility") {
    group = "verification"
    description = "Fails the build when the staged C++ runtime cannot load the Swift bridge."
    dependsOn(stageAllJniLibs)

    doLast {
        val nm = "$llvmBinDir/llvm-nm"
        abis.forEach { abi ->
            val abiDir = stagedJniLibsDir.get().dir(abi.androidAbi)
            val bridge = abiDir.file(bridgeSoName).asFile
            val libcxx = abiDir.file("libc++_shared.so").asFile
            val undefinedOutput = providers.exec {
                commandLine(nm, "-D", "--undefined-only", bridge.absolutePath)
            }.standardOutput.asText.get()
            val definedOutput = providers.exec {
                commandLine(nm, "-D", "--defined-only", libcxx.absolutePath)
            }.standardOutput.asText.get()
            val undefinedCxxSymbols = undefinedOutput.lineSequence()
                .mapNotNull { it.trim().split(Regex("\\s+")).lastOrNull() }
                .map { it.substringBefore("@") }
                .filter { it.startsWith("_ZNSt6__ndk") }
                .toSet()
            val definedCxxSymbols = definedOutput.lineSequence()
                .mapNotNull { it.trim().split(Regex("\\s+")).lastOrNull() }
                .map { it.substringBefore("@") }
                .toSet()
            val missingSymbols = (undefinedCxxSymbols - definedCxxSymbols).sorted()
            require(missingSymbols.isEmpty()) {
                "C++ runtime compatibility check FAILED for ${abi.androidAbi}: " +
                    "libc++_shared.so does not export ${missingSymbols.joinToString()}."
            }
            logger.lifecycle("C++ runtime compatibility OK: ${abi.androidAbi}")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 16 KB page-alignment gate. Android 15+ requires every LOAD segment of every shipped .so to be
// aligned to at least 0x4000. A sub-0x4000 segment fails the build here, before packaging.
// ---------------------------------------------------------------------------------------------
val minLoadAlignment = 0x4000L

val verifySoAlignment = tasks.register("verifySoAlignment") {
    group = "verification"
    description = "Fails the build if any shipped .so has a LOAD segment aligned below 16 KB."
    dependsOn(verifyCxxRuntimeCompatibility)

    doLast {
        val soFiles = stagedJniLibsDir.get().asFile.walkTopDown().filter { it.extension == "so" }.toList()
        require(soFiles.isNotEmpty()) { "No .so files staged for alignment verification." }
        val readelf = "$llvmBinDir/llvm-readelf"
        soFiles.forEach { so ->
            val output = providers.exec {
                commandLine(readelf, "-l", so.absolutePath)
            }.standardOutput.asText.get()

            // Each program-header LOAD line ends in its alignment (hex). Collect them all.
            val aligns = output.lineSequence()
                .filter { it.trimStart().startsWith("LOAD") }
                .mapNotNull { line -> line.trim().split(Regex("\\s+")).lastOrNull() }
                .map { token -> token.removePrefix("0x").toLong(16) }
                .toList()
            require(aligns.isNotEmpty()) { "No LOAD segments found in ${so.name}; readelf output unexpected." }
            val bad = aligns.filter { it < minLoadAlignment }
            require(bad.isEmpty()) {
                "16 KB alignment check FAILED for ${so.parentFile.name}/${so.name}: " +
                    "LOAD segment alignment(s) ${bad.map { "0x%x".format(it) }} below 0x4000."
            }
            logger.lifecycle(
                "16 KB alignment OK: ${so.parentFile.name}/${so.name} " +
                    "(${aligns.size} LOAD segments, all >= 0x4000)",
            )
        }
    }
}

android {
    namespace = "one.meshcore.swiftcore"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = androidNdkVersion

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += abis.map { it.androidAbi } }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // jextract emits the Java bindings under MeshCoreBridge/.build during the Swift build.
            java.srcDir(jextractJavaDir)
            // File, not DirectoryProperty: AGP 9 rejects Provider source dirs (the old
            // android.sourceset.disallowProvider=false flag is gone in AGP 10). Staging still
            // runs first via preBuild -> verifySoAlignment.
            jniLibs.srcDir(file("build/generated/jniLibs"))
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The generated bindings compile against (and consumers need) the swift-java runtime.
    api(libs.swiftkit.core)
    testImplementation(libs.junit)
}

// The generated Java and the staged .so's are produced by the Swift toolchain, so every Android
// compile/package task must run the alignment-gated staging first. preBuild is the AGP entry point
// that all variants funnel through.
tasks.named("preBuild") { dependsOn(verifySoAlignment) }

// jextract always emits a module-level class named after the Swift module (MeshCoreBridgeKit),
// separate from the exported types. Here it is an empty shell (a lib-name constant + a redundant
// loader; every type class loads the library itself). Keep it out of the .aar so consumers only
// see the real bindings and never a stray class named after the module. No generated type depends
// on it — the other files mention "MeshCoreBridgeKit" only in a header comment and their LIB_NAME.
tasks.withType<JavaCompile>().configureEach {
    exclude("**/one/meshcore/bridge/MeshCoreBridgeKit.java")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    source.setFrom("src/test/kotlin")
}

@file:Suppress("UnstableApiUsage")

rootProject.name = "MeshCoreOne"

pluginManagement {
    // KSP 2.2.20-2.0.4 casts androidJvm compilations to KotlinJvmAndroidCompilation.
    // The included build replaces KspConfigurations with the AGP 9 KMP library-plugin
    // guards from KSP 2.3.2 so :shared can use com.android.kotlin.multiplatform.library
    // without bumping Kotlin. See ksp-android-kmp-compat/.
    includeBuild("ksp-android-kmp-compat")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // swift-java's runtime (org.swift.swiftkit:swiftkit-core) is pre-1.0 and not on Maven
        // Central; :swift-core resolves it from mavenLocal after a one-time publish. Scoped by
        // group so no other dependency is silently sourced from a developer's local cache.
        mavenLocal {
            content { includeGroup("org.swift.swiftkit") }
        }
    }
}

include(":shared")
include(":sharedDeviceTests")
include(":sharedBenchmarks")
include(":chartBenchmark")
include(":chartBenchmarkMacro")
include(":androidApp")
include(":desktopApp")

// Swift MeshCoreBridge packaged as an .aar. :shared androidMain already consumes it
// (MeshCoreSession.kt wraps the native bridge). One-time: `make swift-java-local`.
include(":swift-core")

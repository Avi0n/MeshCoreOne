plugins {
    kotlin("jvm") version "2.2.20"
    `java-gradle-plugin`
}

group = "one.meshcore"
version = "2.2.20-2.0.4"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val kspGradlePlugin by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    kspGradlePlugin("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.2.20-2.0.4")
    compileOnly("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.2.20-2.0.4")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
    compileOnly("com.android.tools.build:gradle-api:9.1.1")
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(kspGradlePlugin.map { zipTree(it) }) {
        exclude("**/KspConfigurations.class")
        exclude("**/KspConfigurations\$*.class")
    }
}

gradlePlugin {
    plugins {
        create("ksp") {
            id = "com.google.devtools.ksp"
            implementationClass = "com.google.devtools.ksp.gradle.KspGradleSubplugin"
        }
    }
}

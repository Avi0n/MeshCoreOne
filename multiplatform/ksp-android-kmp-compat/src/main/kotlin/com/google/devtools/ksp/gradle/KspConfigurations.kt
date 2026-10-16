package com.google.devtools.ksp.gradle

import com.google.devtools.ksp.gradle.AndroidPluginIntegration.useLegacyVariantApi
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBaseApiPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePluginWrapper
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinCommonCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmAndroidCompilation

/**
 * Stock KSP 2.2.20-2.0.4 [KspConfigurations] with the AGP 9 KMP library-plugin guards from
 * KSP 2.3.2. Without them, `find()` casts every `androidJvm` compilation to
 * [KotlinJvmAndroidCompilation] and configuration fails under
 * `com.android.kotlin.multiplatform.library`.
 *
 * See https://github.com/google/ksp/issues/2476
 */
class KspConfigurations(private val project: Project) {
    companion object {
        private const val PREFIX = "ksp"
    }

    private val allowAllTargetConfiguration =
        project.providers.gradleProperty("ksp.allow.all.target.configuration")
            .orNull
            ?.toBoolean()
            ?: true

    private val configurationForAll = project.configurations.create(PREFIX).apply {
        isCanBeConsumed = false
        isCanBeResolved = false
        isVisible = false
    }

    private fun configurationNameOf(vararg parts: String): String {
        return parts.joinToString("") { part ->
            part.replaceFirstChar { it.uppercase() }
        }.replaceFirstChar { it.lowercase() }
    }

    private fun createConfiguration(
        name: String,
        readableSetName: String,
    ): Configuration {
        return project.configurations.maybeCreate(name).apply {
            description = "KSP dependencies for the '$readableSetName' source set."
            isCanBeResolved = false
            isCanBeConsumed = false
            isVisible = false
        }
    }

    private fun getAndroidConfigurationName(kotlinTarget: KotlinTarget?, sourceSet: String): String {
        val isMain = sourceSet.endsWith("main", ignoreCase = true)
        val nameWithoutMain = when {
            isMain -> sourceSet.substring(0, sourceSet.length - 4)
            else -> sourceSet
        }
        return configurationNameOf(PREFIX, kotlinTarget?.name ?: "", nameWithoutMain)
    }

    private fun getKotlinConfigurationName(compilation: KotlinCompilation<*>, sourceSet: KotlinSourceSet): String {
        val isMain = compilation.name == KotlinCompilation.MAIN_COMPILATION_NAME
        val isDefault = sourceSet.name == compilation.defaultSourceSet.name && compilation !is KotlinCommonCompilation
        val name = if (isMain && isDefault) {
            when (val targetName = compilation.target.name) {
                "jsLegacy", "jsIr" -> "js"
                else -> targetName
            }
        } else if (compilation is KotlinCommonCompilation) {
            sourceSet.name + compilation.target.name.replaceFirstChar(Char::uppercaseChar)
        } else {
            sourceSet.name
        }
        return configurationNameOf(PREFIX, name)
    }

    init {
        project.plugins.withType(KotlinBasePluginWrapper::class.java).configureEach {
            decorateKotlinProject(project.kotlinExtension, project)
        }

        project.plugins.withType(KotlinBaseApiPlugin::class.java).configureEach {
            createAndroidSourceSetConfigurations(project, kotlinTarget = null)
        }

        project.pluginManager.withPlugin("com.android.base") {
            if (!project.useLegacyVariantApi()) {
                val androidComponents =
                    project.extensions.findByType(com.android.build.api.variant.AndroidComponentsExtension::class.java)
                androidComponents?.addKspConfigurations(useGlobalConfiguration = allowAllTargetConfiguration)
            }
        }
    }

    private fun decorateKotlinProject(kotlin: KotlinProjectExtension, project: Project) {
        when (kotlin) {
            is KotlinSingleTargetExtension<*> -> decorateKotlinTarget(kotlin.target, isKotlinMultiplatform = false)
            is KotlinMultiplatformExtension -> {
                kotlin.targets.configureEach { target ->
                    decorateKotlinTarget(target, isKotlinMultiplatform = true)
                }

                var reported = false
                configurationForAll.dependencies.whenObjectAdded {
                    if (!reported) {
                        reported = true
                        val msg = "The 'ksp' configuration is deprecated in Kotlin Multiplatform projects. " +
                            "Please use target-specific configurations like 'kspJvm' instead."

                        if (allowAllTargetConfiguration) {
                            project.logger.warn(msg)
                        } else {
                            throw InvalidUserCodeException(msg)
                        }
                    }
                }
            }
        }
    }

    private fun decorateKotlinTarget(target: KotlinTarget, isKotlinMultiplatform: Boolean) {
        if (isPlainAndroidTarget(target) || isOldKmpAndroidTarget(target)) {
            if (project.useLegacyVariantApi() || isKotlinMultiplatform) {
                createAndroidSourceSetConfigurations(target.project, target)
            }
        } else {
            createConfiguration(
                name = configurationNameOf(PREFIX, target.name),
                readableSetName = target.name,
            )
            target.compilations.configureEach { compilation ->
                compilation.kotlinSourceSets.forEach { sourceSet ->
                    createConfiguration(
                        name = getKotlinConfigurationName(compilation, sourceSet),
                        readableSetName = sourceSet.name,
                    )
                }
            }
        }
    }

    private fun isOldKmpAndroidTarget(target: KotlinTarget): Boolean {
        val isKotlinAndroidTargetClass = try {
            target is KotlinAndroidTarget
        } catch (_: Throwable) {
            false
        }
        return target.platformType == KotlinPlatformType.androidJvm &&
            isMppProject() && isKotlinAndroidTargetClass
    }

    private fun isPlainAndroidTarget(target: KotlinTarget): Boolean {
        return target.platformType == KotlinPlatformType.androidJvm && !isMppProject()
    }

    private fun isMppProject() = project.pluginManager.hasPlugin("kotlin-multiplatform")

    fun find(compilation: KotlinCompilation<*>): Set<Configuration> {
        val results = mutableListOf<String>()
        if (compilation is KotlinCommonCompilation) {
            results.add(getKotlinConfigurationName(compilation, compilation.defaultSourceSet))
        }
        compilation.kotlinSourceSets.mapTo(results) {
            getKotlinConfigurationName(compilation, it)
        }

        if (isPlainAndroidTarget(compilation.target) || isOldKmpAndroidTarget(compilation.target)) {
            compilation as KotlinJvmAndroidCompilation
            AndroidPluginIntegration.getCompilationSourceSets(compilation).mapTo(results) {
                getAndroidConfigurationName(compilation.target, it)
            }
        }

        if (allowAllTargetConfiguration) {
            results.add(configurationForAll.name)
        }

        return results.mapNotNull {
            compilation.target.project.configurations.findByName(it)
        }.toSet()
    }

    private fun createAndroidSourceSetConfigurations(project: Project, kotlinTarget: KotlinTarget?) {
        AndroidPluginIntegration.forEachAndroidSourceSet(project) { sourceSet ->
            createConfiguration(
                name = getAndroidConfigurationName(kotlinTarget, sourceSet),
                readableSetName = "$sourceSet (Android)",
            )
        }
    }
}

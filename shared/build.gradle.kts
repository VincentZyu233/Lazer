import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.BufferedReader
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The About page names the commit a build came from, so that fact has to be read out of the
// checkout while the build runs. The task declares no outputs on purpose: a commit moves without
// touching any file an up-to-date check could watch, which would keep stamping the previous hash
// into new builds. It rewrites the source only when the text differs, so compilation stays
// incremental even though the task itself always runs.
val buildInfoSources = layout.buildDirectory.dir("generated/lazerBuildInfo/kotlin")
val generateLazerBuildInfo = tasks.register<LazerBuildInfoTask>("generateLazerBuildInfo") {
    checkoutDir = rootProject.layout.projectDirectory
    outputDir = buildInfoSources
}
tasks.configureEach {
    if (name.startsWith("compile")) dependsOn(generateLazerBuildInfo)
}

kotlin {
    // Kotlin/Native HostManager does not know "windows aarch64".
    // Registering iOS targets on Windows ARM makes configuration/run throw:
    //   Unknown host target: windows aarch64
    // Only enable them on macOS hosts that can actually build them.
    val hostOs = System.getProperty("os.name").orEmpty()
    val isMacOs = hostOs.startsWith("Mac", ignoreCase = true)
    if (isMacOs) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "Shared"
                isStatic = true
            }
        }
    }

    jvm()

    android {
        namespace = "dev.naominet.lazer.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        // LazerBuildInfo.kt is written by generateLazerBuildInfo, which every compile task here
        // depends on, so the directory is populated before the compiler looks at it.
        getByName("commonMain") {
            kotlin.srcDir(buildInfoSources)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.ktor.client.okhttp)
        }
        // iosMain only exists when iOS targets are registered (macOS hosts).
        findByName("iosMain")?.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        commonMain.dependencies {
            // Gateway exposes JsonObject in a few raw-response methods, so consumers need this
            // type on their compile classpath rather than only at the shared module's runtime.
            api(libs.kotlinx.serialization.json)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.miuix.ui)
            // Exposed so platform modules can compose the backdrop library's own modifiers
            // (switchers, magnifiers) instead of re-wrapping every recipe here.
            api(libs.backdrop)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

/** Asks the checkout which commit this build is, for the About page to name it. */
abstract class LazerBuildInfoTask : DefaultTask() {
    @get:Internal
    abstract val checkoutDir: DirectoryProperty

    @get:Internal
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun writeBuildInfo() {
        val head = git("log", "-1", "--pretty=format:%h|%H|%cI")?.split('|')?.takeIf { it.size == 3 }
        val file = File(outputDir.get().asFile, "dev/naominet/lazer/LazerBuildInfo.kt")
        val text = """
            package dev.naominet.lazer

            /** Generated by `:shared:generateLazerBuildInfo`. Blank fields mean git had nothing to read. */
            object LazerBuildInfo {
                const val commit: String = "${head?.get(0).orEmpty().asLiteral()}"
                const val commitHash: String = "${head?.get(1).orEmpty().asLiteral()}"
                const val commitTime: String = "${head?.get(2).orEmpty().asLiteral()}"
            }
        """.trimIndent() + "\n"
        if (file.takeIf(File::exists)?.readText() != text) {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
    }

    private fun git(vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", *arguments))
            .directory(checkoutDir.get().asFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
        output.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
    }.getOrNull()

    private fun String.asLiteral() = replace("\\", "\\\\").replace("\"", "\\\"")
}

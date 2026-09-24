import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    // Keep sibling shared-module plugin versions visible when androidApp is imported as a
    // standalone Gradle root in IDEA. They remain unapplied to the Android application itself.
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// Release signing is resolved up front but never required at configuration time, so unrelated
// tasks (debug APK, desktop packaging, CI) configure and run without a keystore.
val releaseKeystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val releaseStoreFile = providers.environmentVariable("LAZER_KEYSTORE_FILE")
    .orElse(releaseKeystoreProperties.getProperty("storeFile") ?: "")
    .get()
val releaseStorePassword = providers.environmentVariable("LAZER_KEYSTORE_PASSWORD")
    .orElse(releaseKeystoreProperties.getProperty("storePassword") ?: "")
    .get()
val releaseKeyAlias = providers.environmentVariable("LAZER_KEY_ALIAS")
    .orElse(releaseKeystoreProperties.getProperty("keyAlias") ?: "")
    .get()
val releaseKeyPassword = providers.environmentVariable("LAZER_KEY_PASSWORD")
    .orElse(releaseKeystoreProperties.getProperty("keyPassword") ?: "")
    .get()
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all(String::isNotBlank)

// Fail loudly only when a release artifact is actually requested, and point at the fix. A dedicated
// task keeps this out of configuration-time evaluation (CI builds without a keystore) and stays
// configuration-cache safe by capturing a plain Boolean.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    val signingConfigured = hasReleaseSigning
    doLast {
        check(signingConfigured) {
            "Release signing is not configured. Set LAZER_KEYSTORE_FILE, " +
                "LAZER_KEYSTORE_PASSWORD, LAZER_KEY_ALIAS and LAZER_KEY_PASSWORD, " +
                "or create keystore.properties in the project root."
        }
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

// Every surface that takes a tap answers with a haptic, at the strength the reader chose in
// Settings. That is a convention the compiler cannot see, so a plain source scan enforces it: a raw
// `Modifier.clickable`, an unwrapped Material control or a bare `LazerSlider` fails the build.
// `// haptic-raw` exempts a line that answers through a gesture the scan cannot follow.
val verifyTapHaptics = tasks.register("verifyTapHaptics") {
    group = "verification"
    description = "Fails when a clickable surface answers without a haptic."
    val sourceRoot = file("src/main/kotlin")
    inputs.dir(sourceRoot)
    doLast {
        val material = Regex("""(^|[^A-Za-z_.])(IconButton|TextButton|Button|OutlinedButton|FilledTonalButton|FilterChip|ExtendedFloatingActionButton|DropdownMenuItem)\(""")
        val rawModifier = Regex("""\.(clickable|selectable)\(""")
        val rawSlider = Regex("""(^|[^A-Za-z_.])LazerSlider\(""")
        val rawCombined = Regex("""\.combinedClickable\(""")
        val answered = Regex("""tapFeedback|tapped|answer\(\)""")
        val offenders = mutableListOf<String>()

        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (line.contains("// haptic-raw")) return@forEachIndexed
                val where = "${file.name}:${index + 1}"
                val window = lines.drop(index).take(14).joinToString("\n")
                val needsAnswer = material.containsMatchIn(line) ||
                    rawModifier.containsMatchIn(line) ||
                    rawCombined.containsMatchIn(line)
                when {
                    needsAnswer && !answered.containsMatchIn(window) ->
                        offenders += "$where  未接震动:onClick 要走 tapFeedback(...),或改用手边的 tap 版控件"
                    rawSlider.containsMatchIn(line) ->
                        offenders += "$where  裸 LazerSlider:改用 TapSlider,振动只落在松手那一下"
                }
            }
        }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Tap haptics coverage failed:\n" + offenders.joinToString("\n") { "  $it" },
            )
        }
    }
}
tasks.named("check") { dependsOn(verifyTapHaptics) }

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.materialIconsExtended)
    implementation(libs.compose.material3)
    implementation(libs.miuix.ui)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Publishes real-time lyrics to the system SuperLyric service.
    implementation("com.github.HChenX:SuperLyricApi:3.4")
    debugImplementation(libs.compose.uiTooling)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

android {
    namespace = "dev.naominet.lazer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.naominet.lazer"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = rootProject.extra["lazerVersionCode"] as Int
        versionName = rootProject.extra["lazerVersionName"] as String

        // CI builds a slimmer APK for a single ABI (e.g. -PlazerAbis=arm64-v8a). Local builds keep
        // every ABI unless the property is supplied.
        providers.gradleProperty("lazerAbis").orNull
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.takeIf(List<String>::isNotEmpty)
            ?.let { abis -> ndk { abiFilters.addAll(abis) } }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

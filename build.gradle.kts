plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// One release fact source: shared/.../LazerRelease.kt. The About page reads that object and the app
// modules read it from here, so the number on screen is the number the artefact carries.
val releaseFacts = rootDir.resolve("shared/src/commonMain/kotlin/dev/naominet/lazer/LazerRelease.kt")
fun releaseField(name: String): String =
    Regex("""$name = "?([^"\n]*)"?\s*$""", RegexOption.MULTILINE)
        .find(releaseFacts.readText())
        ?.groupValues
        ?.get(1)
        ?: error("$name is missing from ${releaseFacts.name}")

val lazerVersionName = releaseField("versionName")
extra["lazerVersionName"] = lazerVersionName
extra["lazerVersionCode"] = releaseField("versionCode").toInt()
// Windows installers want three parts, so 1.2 ships as 1.2.0 rather than failing the package task.
extra["lazerPackageVersion"] = if (lazerVersionName.count { it == '.' } >= 2) {
    lazerVersionName
} else {
    "$lazerVersionName.0"
}

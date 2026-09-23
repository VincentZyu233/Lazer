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
// The pattern excludes line terminators from the value and trims it: the repository is checked out
// with CRLF on some machines and LF on CI, and an anchored pattern captured the carriage return
// into the version code and failed the build there.
val releaseFacts = rootDir.resolve("shared/src/commonMain/kotlin/dev/naominet/lazer/LazerRelease.kt")
fun releaseField(name: String): String =
    Regex("""$name\s*=\s*"?([^"\r\n]+)"?""")
        .find(releaseFacts.readText())
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: error("$name is missing from ${releaseFacts.name}")

val lazerVersionName = releaseField("versionName")
extra["lazerVersionName"] = lazerVersionName
extra["lazerVersionCode"] = releaseField("versionCode").toInt()
// Windows installers want three parts, so a two-part version ships as 1.3.0.
extra["lazerPackageVersion"] = if (lazerVersionName.count { it == '.' } >= 2) {
    lazerVersionName
} else {
    "$lazerVersionName.0"
}

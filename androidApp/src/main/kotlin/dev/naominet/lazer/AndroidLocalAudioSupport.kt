package dev.naominet.lazer

/** The first Android local-file slice is deliberately limited to the lossless formats we exercise. */
internal fun isSupportedAndroidLocalAudio(displayName: String?, mimeType: String?): Boolean {
    val extension = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
    if (extension in setOf("wav", "wave", "flac")) return true
    return mimeType?.lowercase() in setOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave")
}

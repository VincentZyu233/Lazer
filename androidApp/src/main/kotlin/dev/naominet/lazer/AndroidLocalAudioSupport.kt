package dev.naominet.lazer

/** The local-file picker accepts the lossless formats currently supported by Android playback. */
internal fun isSupportedAndroidLocalAudio(displayName: String?, mimeType: String?): Boolean {
    val extension = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
    if (extension in setOf("wav", "wave", "flac", "dsf", "dff")) return true
    return mimeType?.lowercase() in setOf(
        "audio/flac",
        "audio/x-flac",
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/x-dsf",
        "audio/x-dff",
        "audio/x-dsd",
    )
}

internal fun isAndroidDsdAudio(displayName: String?, mimeType: String?): Boolean {
    val extension = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
    if (extension in setOf("dsf", "dff")) return true
    return mimeType?.lowercase() in setOf("audio/x-dsf", "audio/x-dff", "audio/x-dsd")
}

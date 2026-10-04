package dev.naominet.lazer

/** Software volume must be bypassed only for an active or imminently opening direct signal path. */
internal fun shouldBypassDesktopDigitalVolume(
    nativePlayback: Boolean,
    bitPerfectActive: Boolean,
    doPActive: Boolean,
    bitPerfectOpening: Boolean,
    doPOpening: Boolean,
    nativeDsdActive: Boolean = false,
    nativeDsdOpening: Boolean = false,
): Boolean = nativePlayback &&
    (bitPerfectActive || doPActive || bitPerfectOpening || doPOpening || nativeDsdActive || nativeDsdOpening)

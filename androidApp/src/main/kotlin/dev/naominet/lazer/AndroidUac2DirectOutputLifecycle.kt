package dev.naominet.lazer

/** Serializes Media3 output creation with provider release and device-detach cleanup. */
internal class AndroidUac2DirectOutputLifecycle<Transport>(
    private val closeTransport: (Transport, detached: Boolean) -> Unit,
) {
    private val lock = Any()
    private var closed = false
    private var detached = false
    private var activeTransport: Transport? = null

    val isAvailable: Boolean
        get() = synchronized(lock) { !closed && !detached }

    /** The factory, output construction, and ownership handoff share one lifecycle lock. */
    fun <Output> createOutput(
        createTransport: () -> Transport,
        createAudioOutput: (Transport) -> Output,
    ): Output = synchronized(lock) {
        checkAvailable()

        // Media3 may recreate its output after a format change. Finish releasing the prior USB
        // owner before claiming the replacement stream tuple.
        activeTransport.also { activeTransport = null }?.let {
            closeTransport(it, false)
        }
        checkAvailable()

        val transport = createTransport()
        try {
            val output = createAudioOutput(transport)
            // The callback used while building the output can re-enter this lifecycle. Do not
            // install a transport if it caused release or detach before ownership handoff.
            checkAvailable()
            activeTransport = transport
            output
        } catch (error: Throwable) {
            try {
                closeTransport(transport, detached)
            } catch (closeError: Throwable) {
                if (closeError !== error) error.addSuppressed(closeError)
            }
            throw error
        }
    }

    /** Fences future output creation before closing the currently owned USB session. */
    fun onDeviceDetached() {
        val transport = synchronized(lock) {
            if (closed || detached) return
            detached = true
            activeTransport.also { activeTransport = null }
        }
        transport?.let { closeTransport(it, true) }
    }

    /** Prevents new output creation before releasing provider resources and the active transport. */
    fun closeProvider(releaseProvider: () -> Unit) {
        val transport = synchronized(lock) {
            if (closed) return
            closed = true
            activeTransport.also { activeTransport = null }
        }

        var failure: Throwable? = null
        try {
            releaseProvider()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            transport?.let { closeTransport(it, false) }
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else if (failure !== error) {
                failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun checkAvailable() {
        check(!closed) { "USB direct-output provider is closed" }
        check(!detached) { "Selected USB direct-output device is detached" }
    }
}

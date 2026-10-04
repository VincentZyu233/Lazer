package dev.naominet.lazer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class DesktopUpnpRendererStatus(
    val sinkProtocolInfo: String? = null,
    val avTransportActions: Set<String>? = null,
    val currentTransportActions: Set<String>? = null,
    val supportsRelativeTimeSeek: Boolean? = null,
    val renderingControlActions: Set<String>? = null,
    val rendererVolume: Int? = null,
    val rendererVolumeMaximum: Int? = null,
    val isPlaybackSnapshot: Boolean = false,
    val transportState: String? = null,
    val transportStatus: String? = null,
    val speed: String? = null,
    val trackUri: String? = null,
    val trackMetadata: String? = null,
    val positionMillis: Long? = null,
    val durationMillis: Long? = null,
    val supplementalErrors: List<String> = emptyList(),
    val connectionState: DesktopUpnpConnectionState = DesktopUpnpConnectionState.UNKNOWN,
    val connectionError: String? = null,
)

internal enum class DesktopUpnpConnectionState {
    UNKNOWN,
    CONNECTED,
    DISCONNECTED,
}

/** A socket-level failure while contacting a renderer, distinct from a SOAP action fault. */
internal class DesktopUpnpConnectionException(cause: IOException) :
    IOException("Renderer connection is temporarily unavailable.", cause)

/** The discovered renderer service endpoint no longer exists at its advertised URL. */
internal class DesktopUpnpStaleEndpointException(
    val serviceKind: DesktopUpnpRendererServiceKind,
    val statusCode: Int,
) : IOException(
    "Renderer ${serviceKind.serviceName} control endpoint is no longer available (HTTP $statusCode); rediscovery is required.",
) {
    init {
        require(statusCode == HTTP_NOT_FOUND || statusCode == HTTP_GONE) {
            "Only HTTP 404 and 410 indicate a stale UPnP control endpoint."
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
    }
}

/** A SOAP action fault is separate from a transport failure so recovery can handle transition 701. */
internal class DesktopUpnpSoapFaultException(
    val action: String,
    val errorCode: Int?,
    val description: String?,
) : IOException(description ?: errorCode?.toString() ?: "UPnP $action returned a SOAP fault.")

internal fun desktopUpnpActionEnabled(status: DesktopUpnpRendererStatus?, action: String): Boolean {
    if (status?.avTransportActions?.let { action !in it } == true) return false
    if (status?.currentTransportActions?.let { action !in it } == true) return false
    return true
}

internal fun desktopUpnpActionAdvertised(status: DesktopUpnpRendererStatus?, action: String): Boolean =
    status?.avTransportActions?.let { action in it } ?: true

internal fun desktopUpnpRelativeTimeSeekEnabled(status: DesktopUpnpRendererStatus?): Boolean =
    status?.supportsRelativeTimeSeek == true && desktopUpnpActionEnabled(status, "Seek")

internal fun desktopUpnpRendererVolumeAdjustEnabled(status: DesktopUpnpRendererStatus?): Boolean =
    status?.rendererVolume != null &&
        status.renderingControlActions?.containsAll(setOf("GetVolume", "SetVolume")) == true &&
        status.rendererVolumeMaximum != 0

internal fun desktopUpnpRendererVolumeSliderEnabled(status: DesktopUpnpRendererStatus?): Boolean =
    desktopUpnpRendererVolumeAdjustEnabled(status) &&
        (status?.rendererVolumeMaximum ?: 0) > 0

internal fun desktopUpnpRendererVolumeStepEnabled(
    status: DesktopUpnpRendererStatus?,
    delta: Int,
): Boolean {
    if (delta != -1 && delta != 1 || !desktopUpnpRendererVolumeAdjustEnabled(status)) return false
    val current = status?.rendererVolume ?: return false
    val maximum = status.rendererVolumeMaximum ?: MAX_UPNP_VOLUME_VALUE
    return current + delta in 0..maximum
}

private fun parseDesktopUpnpRendererVolume(raw: String?, maximum: Int?): Int? {
    val value = raw?.toIntOrNull()?.takeIf { it in 0..MAX_UPNP_VOLUME_VALUE } ?: return null
    return value.takeIf { maximum == null || it <= maximum }
}

internal enum class DesktopUpnpTransportCommand {
    PLAY,
    PAUSE,
    STOP,
}

internal fun interface DesktopUpnpSoapTransport {
    suspend fun invoke(
        device: DesktopUpnpRendererDevice,
        serviceKind: DesktopUpnpRendererServiceKind,
        action: String,
        arguments: Map<String, String>,
    ): Map<String, String>
}

internal fun interface DesktopUpnpScpdTransport {
    suspend fun fetch(uri: URI): ByteArray
}

/** SOAP control point for the renderer. This never reads or changes the local DAC output. */
internal class DesktopUpnpRendererClient(
    private val soap: DesktopUpnpSoapTransport = DesktopHttpUpnpSoapTransport,
    private val scpd: DesktopUpnpScpdTransport = DesktopHttpUpnpScpdTransport,
) {
    private data class CachedCapabilities(
        val value: DesktopUpnpScpdCapabilities,
        val fetchedAtNanos: Long,
    )

    private val capabilitiesByEndpoint = ConcurrentHashMap<String, CachedCapabilities>()

    /** Drops endpoint capability snapshots after rediscovery of a restarted renderer. */
    fun invalidateCapabilities(device: DesktopUpnpRendererDevice) {
        val keyPrefix = "${device.identity}|"
        capabilitiesByEndpoint.keys.removeIf { it.startsWith(keyPrefix) }
    }

    suspend fun readStatus(device: DesktopUpnpRendererDevice): DesktopUpnpRendererStatus {
        val supplementalErrors = mutableListOf<String>()
        suspend fun optionalAction(action: String, service: DesktopUpnpRendererServiceKind, args: Map<String, String> = emptyMap()): Map<String, String> = try {
            soap.invoke(
                device,
                service,
                action,
                args,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            supplementalErrors += "$action: ${error.message ?: error.javaClass.simpleName}"
            emptyMap()
        }
        val avTransportCapabilities = readAvTransportCapabilities(device, supplementalErrors)
        val renderingControlCapabilities = readServiceCapabilities(
            device,
            DesktopUpnpRendererServiceKind.RENDERING_CONTROL,
            supplementalErrors,
        )
        val connectionInfo = optionalAction(
            "GetProtocolInfo",
            DesktopUpnpRendererServiceKind.CONNECTION_MANAGER,
            emptyMap(),
        )
        val transportInfo = soap.invoke(
            device,
            DesktopUpnpRendererServiceKind.AV_TRANSPORT,
            "GetTransportInfo",
            mapOf("InstanceID" to "0"),
        )
        val positionInfo = optionalAction(
            "GetPositionInfo",
            DesktopUpnpRendererServiceKind.AV_TRANSPORT,
            mapOf("InstanceID" to "0"),
        )
        val currentTransportActions = if (
            avTransportCapabilities?.actions?.contains("GetCurrentTransportActions") == true
        ) {
            optionalAction(
                "GetCurrentTransportActions",
                DesktopUpnpRendererServiceKind.AV_TRANSPORT,
                mapOf("InstanceID" to "0"),
            )["Actions"]?.let(::parseDesktopUpnpActionList)
        } else {
            null
        }
        val rendererVolume = if (renderingControlCapabilities?.actions?.contains("GetVolume") == true) {
            val rawVolume = optionalAction(
                "GetVolume",
                DesktopUpnpRendererServiceKind.RENDERING_CONTROL,
                linkedMapOf("InstanceID" to "0", "Channel" to "Master"),
            )["CurrentVolume"]
            parseDesktopUpnpRendererVolume(rawVolume, renderingControlCapabilities.volumeMaximum).also { volume ->
                if (volume == null && supplementalErrors.none { it.startsWith("GetVolume:") }) {
                    supplementalErrors += "GetVolume: renderer returned an invalid volume value."
                }
            }
        } else {
            null
        }

        return DesktopUpnpRendererStatus(
            sinkProtocolInfo = connectionInfo["Sink"]?.takeIf(String::isNotBlank),
            avTransportActions = avTransportCapabilities?.actions,
            currentTransportActions = currentTransportActions,
            supportsRelativeTimeSeek = avTransportCapabilities?.supportsRelativeTimeSeek,
            renderingControlActions = renderingControlCapabilities?.actions,
            rendererVolume = rendererVolume,
            rendererVolumeMaximum = renderingControlCapabilities?.volumeMaximum,
            transportState = transportInfo["CurrentTransportState"],
            transportStatus = transportInfo["CurrentTransportStatus"],
            speed = transportInfo["CurrentSpeed"],
            trackUri = positionInfo["TrackURI"]?.takeIf(String::isNotBlank),
            trackMetadata = positionInfo["TrackMetaData"]?.takeIf(String::isNotBlank),
            positionMillis = parseDesktopUpnpTime(positionInfo["RelTime"]),
            durationMillis = parseDesktopUpnpTime(positionInfo["TrackDuration"]),
            supplementalErrors = supplementalErrors,
            connectionState = DesktopUpnpConnectionState.CONNECTED,
        )
    }

    /** Lightweight active-playback poll; avoids repeating protocol and capability discovery. */
    suspend fun readPlaybackSnapshot(device: DesktopUpnpRendererDevice): DesktopUpnpRendererStatus = try {
        val transportInfo = soap.invoke(
            device,
            DesktopUpnpRendererServiceKind.AV_TRANSPORT,
            "GetTransportInfo",
            mapOf("InstanceID" to "0"),
        )
        val positionInfo = soap.invoke(
            device,
            DesktopUpnpRendererServiceKind.AV_TRANSPORT,
            "GetPositionInfo",
            mapOf("InstanceID" to "0"),
        )
        DesktopUpnpRendererStatus(
            isPlaybackSnapshot = true,
            transportState = transportInfo["CurrentTransportState"],
            transportStatus = transportInfo["CurrentTransportStatus"],
            speed = transportInfo["CurrentSpeed"],
            trackUri = positionInfo["TrackURI"],
            trackMetadata = positionInfo["TrackMetaData"],
            positionMillis = parseDesktopUpnpTime(positionInfo["RelTime"]),
            durationMillis = parseDesktopUpnpTime(positionInfo["TrackDuration"]),
            connectionState = DesktopUpnpConnectionState.CONNECTED,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: DesktopUpnpStaleEndpointException) {
        DesktopUpnpRendererStatus(
            isPlaybackSnapshot = true,
            transportState = "UNAVAILABLE",
            transportStatus = "UNKNOWN",
            connectionState = DesktopUpnpConnectionState.DISCONNECTED,
            connectionError = error.message ?: "Renderer control endpoint is stale; rediscovery is required.",
        )
    } catch (error: DesktopUpnpConnectionException) {
        // Publish loss of reachability so consumers cannot mistake a cached PLAYING state for
        // current renderer state. The next scheduled poll opens a fresh HTTP connection.
        DesktopUpnpRendererStatus(
            isPlaybackSnapshot = true,
            transportState = "UNAVAILABLE",
            transportStatus = "UNKNOWN",
            connectionState = DesktopUpnpConnectionState.DISCONNECTED,
            connectionError = error.message ?: "Renderer connection is temporarily unavailable.",
        )
    }

    private suspend fun readAvTransportCapabilities(
        device: DesktopUpnpRendererDevice,
        supplementalErrors: MutableList<String>,
    ): DesktopUpnpScpdCapabilities? = readServiceCapabilities(
        device,
        DesktopUpnpRendererServiceKind.AV_TRANSPORT,
        supplementalErrors,
    )

    private suspend fun readServiceCapabilities(
        device: DesktopUpnpRendererDevice,
        serviceKind: DesktopUpnpRendererServiceKind,
        supplementalErrors: MutableList<String>,
    ): DesktopUpnpScpdCapabilities? {
        val endpoint = device.services[serviceKind] ?: return null
        val scpdUri = endpoint.scpdUri ?: return null
        if (!isDesktopUpnpControlUriAllowed(device.descriptionUri, scpdUri)) {
            supplementalErrors += "${serviceKind.serviceName} SCPD: service description URL is outside the device origin."
            return null
        }
        val key = "${device.identity}|$scpdUri"
        val now = System.nanoTime()
        capabilitiesByEndpoint[key]?.let { cached ->
            val ageNanos = now - cached.fetchedAtNanos
            if (ageNanos >= 0L && ageNanos < SCPD_CACHE_TTL_NANOS) return cached.value
        }
        return try {
            val capabilities = parseDesktopUpnpScpdCapabilities(scpd.fetch(scpdUri))
            capabilitiesByEndpoint[key] = CachedCapabilities(capabilities, System.nanoTime())
            capabilities
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            supplementalErrors += "${serviceKind.serviceName} SCPD: ${error.message ?: error.javaClass.simpleName}"
            null
        }
    }

    suspend fun setVolume(device: DesktopUpnpRendererDevice, desiredVolume: Int): DesktopUpnpRendererStatus {
        val before = readStatus(device)
        return setVolume(device, desiredVolume, before)
    }

    suspend fun adjustVolume(device: DesktopUpnpRendererDevice, delta: Int): DesktopUpnpRendererStatus {
        require(delta == -1 || delta == 1) { "Renderer volume can only be adjusted one step at a time." }
        val before = readStatus(device)
        val current = before.rendererVolume
            ?: throw IOException("Renderer volume is unavailable; read its status first.")
        return setVolume(device, current + delta, before)
    }

    private suspend fun setVolume(
        device: DesktopUpnpRendererDevice,
        desiredVolume: Int,
        before: DesktopUpnpRendererStatus,
    ): DesktopUpnpRendererStatus {
        if (!desktopUpnpRendererVolumeAdjustEnabled(before)) {
            throw IOException("Renderer does not advertise readable and writable Master volume.")
        }
        val maximum = before.rendererVolumeMaximum ?: MAX_UPNP_VOLUME_VALUE
        if (desiredVolume !in 0..maximum) {
            throw IOException("Desired renderer volume is outside the advertised device range.")
        }
        soap.invoke(
            device,
            DesktopUpnpRendererServiceKind.RENDERING_CONTROL,
            "SetVolume",
            linkedMapOf(
                "InstanceID" to "0",
                "Channel" to "Master",
                "DesiredVolume" to desiredVolume.toString(),
            ),
        )
        val confirmed = readStatus(device)
        if (confirmed.rendererVolume == null) {
            val readError = confirmed.supplementalErrors.firstOrNull { it.startsWith("GetVolume:") }
            throw IOException(readError ?: "Renderer did not return a volume value after setting it.")
        }
        return confirmed
    }

    suspend fun control(device: DesktopUpnpRendererDevice, command: DesktopUpnpTransportCommand) {
        val action = when (command) {
            DesktopUpnpTransportCommand.PLAY -> "Play" to mapOf("Speed" to "1")
            DesktopUpnpTransportCommand.PAUSE -> "Pause" to emptyMap()
            DesktopUpnpTransportCommand.STOP -> "Stop" to emptyMap()
        }
        soap.invoke(
            device,
            DesktopUpnpRendererServiceKind.AV_TRANSPORT,
            action.first,
            mapOf("InstanceID" to "0") + action.second,
        )
    }

    suspend fun setMediaUri(
        device: DesktopUpnpRendererDevice,
        mediaUri: URI,
        didlMetadata: String,
    ) {
        require(isHttpUri(mediaUri)) { "Renderer media URL must be HTTP or HTTPS." }
        soap.invoke(
            device,
            DesktopUpnpRendererServiceKind.AV_TRANSPORT,
            "SetAVTransportURI",
            linkedMapOf(
                "InstanceID" to "0",
                "CurrentURI" to mediaUri.toASCIIString(),
                "CurrentURIMetaData" to didlMetadata,
            ),
        )
    }

    suspend fun seek(device: DesktopUpnpRendererDevice, positionMillis: Long) {
        require(positionMillis >= 0L) { "Seek position must be non-negative." }
        soap.invoke(
            device,
            DesktopUpnpRendererServiceKind.AV_TRANSPORT,
            "Seek",
            mapOf(
                "InstanceID" to "0",
                "Unit" to "REL_TIME",
                "Target" to formatDesktopUpnpTime(positionMillis),
            ),
        )
    }
}

internal object DesktopHttpUpnpScpdTransport : DesktopUpnpScpdTransport {
    override suspend fun fetch(uri: URI): ByteArray = withContext(Dispatchers.IO) {
        require(isHttpUri(uri)) { "UPnP SCPD URL must use HTTP or HTTPS." }
        val connection = uri.toURL().openConnection() as? HttpURLConnection
            ?: throw IOException("UPnP SCPD requires an HTTP endpoint.")
        connection.connectTimeout = SCPD_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = SCPD_READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/xml, application/xml")
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("UPnP SCPD returned HTTP $responseCode.")
            }
            if (connection.contentLengthLong > MAX_SCPD_RESPONSE_BYTES) {
                throw IOException("UPnP SCPD response exceeds the size limit.")
            }
            connection.inputStream.use(::readBoundedScpdResponse)
        } finally {
            connection.disconnect()
        }
    }
}

private fun readBoundedScpdResponse(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4_096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() + count > MAX_SCPD_RESPONSE_BYTES) {
            throw IOException("UPnP SCPD response exceeds the size limit.")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun parseDesktopUpnpActionList(value: String): Set<String> = value
    .split(',')
    .map(String::trim)
    .filter { it.matches(XML_IDENTIFIER) }
    .toSet()

internal fun mergeDesktopUpnpPlaybackSnapshot(
    current: DesktopUpnpRendererStatus,
    snapshot: DesktopUpnpRendererStatus,
): DesktopUpnpRendererStatus {
    val disconnected = snapshot.connectionState == DesktopUpnpConnectionState.DISCONNECTED
    return current.copy(
        transportState = snapshot.transportState ?: if (disconnected) null else current.transportState,
        transportStatus = snapshot.transportStatus ?: if (disconnected) null else current.transportStatus,
        speed = snapshot.speed ?: if (disconnected) null else current.speed,
        trackUri = snapshot.trackUri?.takeIf(String::isNotBlank) ?: current.trackUri,
        trackMetadata = snapshot.trackMetadata?.takeIf(String::isNotBlank) ?: current.trackMetadata,
        positionMillis = if (disconnected) null else snapshot.positionMillis ?: current.positionMillis,
        durationMillis = if (disconnected) null else snapshot.durationMillis ?: current.durationMillis,
        connectionState = snapshot.connectionState.takeUnless { it == DesktopUpnpConnectionState.UNKNOWN }
            ?: current.connectionState,
        connectionError = when (snapshot.connectionState) {
            DesktopUpnpConnectionState.DISCONNECTED -> snapshot.connectionError
            DesktopUpnpConnectionState.CONNECTED -> null
            DesktopUpnpConnectionState.UNKNOWN -> current.connectionError
        },
        isPlaybackSnapshot = false,
    )
}

internal fun mergeDesktopUpnpEventStatus(
    current: DesktopUpnpRendererStatus,
    event: DesktopUpnpEvent,
): DesktopUpnpRendererStatus {
    val properties = event.properties
    fun eventText(name: String, oldValue: String?): String? = if (name in properties) {
        properties[name]?.let { value -> if (name == "CurrentTrackMetaData") value else value.trim() }
            ?.takeIf(String::isNotBlank)
    } else {
        oldValue
    }
    fun eventTime(name: String, oldValue: Long?): Long? = if (name in properties) {
        parseDesktopUpnpTime(properties[name])
    } else {
        oldValue
    }
    val trackUri = when {
        "CurrentTrackURI" in properties -> eventText("CurrentTrackURI", current.trackUri)
        "AVTransportURI" in properties -> eventText("AVTransportURI", current.trackUri)
        else -> current.trackUri
    }
    return current.copy(
        currentTransportActions = if ("CurrentTransportActions" in properties) {
            parseDesktopUpnpActionList(properties["CurrentTransportActions"].orEmpty())
        } else {
            current.currentTransportActions
        },
        transportState = eventText("TransportState", current.transportState),
        transportStatus = eventText("TransportStatus", current.transportStatus),
        speed = eventText("CurrentSpeed", current.speed),
        trackUri = trackUri,
        trackMetadata = eventText("CurrentTrackMetaData", current.trackMetadata),
        positionMillis = eventTime("RelativeTimePosition", current.positionMillis),
        durationMillis = eventTime("CurrentTrackDuration", current.durationMillis),
    )
}

internal object DesktopHttpUpnpSoapTransport : DesktopUpnpSoapTransport {
    override suspend fun invoke(
        device: DesktopUpnpRendererDevice,
        serviceKind: DesktopUpnpRendererServiceKind,
        action: String,
        arguments: Map<String, String>,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        require(action.matches(XML_IDENTIFIER)) { "Invalid UPnP action name." }
        require(arguments.keys.all { it.matches(XML_IDENTIFIER) }) { "Invalid UPnP argument name." }
        val endpoint = device.services[serviceKind]
            ?: throw IOException("Renderer does not expose ${serviceKind.serviceName}.")
        require(isDesktopUpnpControlUriAllowed(device.descriptionUri, endpoint.controlUri)) {
            "UPnP control URL is outside the device origin."
        }

        val requestBody = buildDesktopUpnpSoapRequest(endpoint.serviceType, action, arguments)
        val connection = endpoint.controlUri.toURL().openConnection() as? HttpURLConnection
            ?: throw IOException("UPnP SOAP requires an HTTP endpoint.")
        connection.connectTimeout = SOAP_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = SOAP_READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPAction", "\"${endpoint.serviceType}#$action\"")
        connection.setRequestProperty("Accept", "text/xml, application/xml")
        connection.setFixedLengthStreamingMode(requestBody.size)

        try {
            connection.outputStream.use { it.write(requestBody) }
            val responseCode = connection.responseCode
            if (responseCode == HTTP_NOT_FOUND || responseCode == HTTP_GONE) {
                throw DesktopUpnpStaleEndpointException(serviceKind, responseCode)
            }
            val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = responseStream?.use(::readBoundedSoapResponse) ?: ByteArray(0)
            val values = if (response.isNotEmpty()) parseDesktopUpnpSoapResponse(response, action) else emptyMap()
            if (responseCode !in 200..299) {
                val errorCode = values["errorCode"]?.toIntOrNull()
                val description = values["errorDescription"]
                if (errorCode != null || description != null) {
                    throw DesktopUpnpSoapFaultException(action, errorCode, description)
                }
                throw IOException("UPnP $action returned HTTP $responseCode.")
            }
            values
        } catch (error: SocketException) {
            throw DesktopUpnpConnectionException(error)
        } catch (error: SocketTimeoutException) {
            throw DesktopUpnpConnectionException(error)
        } catch (error: UnknownHostException) {
            throw DesktopUpnpConnectionException(error)
        } finally {
            connection.disconnect()
        }
    }

    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_GONE = 410
}

internal fun isDesktopUpnpControlUriAllowed(descriptionUri: URI, controlUri: URI): Boolean =
    isHttpUri(descriptionUri) && isHttpUri(controlUri) && isSameOrigin(descriptionUri, controlUri)

internal fun chooseDesktopUpnpHttpMime(sinkProtocolInfo: String?, candidateMimeTypes: List<String>): String? {
    if (sinkProtocolInfo.isNullOrBlank()) return null
    val sinkFormats = sinkProtocolInfo.split(',').mapNotNull { raw ->
        val parts = raw.trim().split(':', limit = 4)
        if (parts.size != 4 ||
            !(parts[0].equals("http-get", ignoreCase = true) || parts[0] == "*") ||
            !(parts[1] == "*" || parts[1].isNotBlank())
        ) return@mapNotNull null
        parts[2].substringBefore(';').trim().lowercase().takeIf(String::isNotBlank)
    }
    return candidateMimeTypes.firstOrNull { candidate ->
        val normalizedCandidate = candidate.trim().lowercase()
        normalizedCandidate in sinkFormats || "*" in sinkFormats ||
            sinkFormats.any { advertised ->
                advertised.endsWith("/*") && normalizedCandidate.startsWith(advertised.removeSuffix("*"))
            }
    }
}

internal fun buildDesktopUpnpDidlMetadata(
    title: String,
    artist: String?,
    album: String?,
    resourceUri: URI,
    mimeType: String,
    fileSize: Long,
    durationMillis: Long?,
): String {
    require(isHttpUri(resourceUri)) { "Renderer media URL must be HTTP or HTTPS." }
    require(mimeType.lowercase() in setOf("audio/wav", "audio/x-wav", "audio/flac")) {
        "Unsupported UPnP media MIME type."
    }
    require(fileSize >= 0L) { "Media size must be non-negative." }
    val duration = durationMillis?.takeIf { it > 0L }?.let(::formatDesktopUpnpTime)
    return buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        append("<dc:title>").append(escapeXmlText(title.ifBlank { "Local audio" })).append("</dc:title>")
        artist?.takeIf(String::isNotBlank)?.let {
            append("<upnp:artist>").append(escapeXmlText(it)).append("</upnp:artist>")
        }
        album?.takeIf(String::isNotBlank)?.let {
            append("<upnp:album>").append(escapeXmlText(it)).append("</upnp:album>")
        }
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        append("<res protocolInfo=\"http-get:*:").append(escapeXmlAttribute(mimeType)).append(":*\" ")
        append("size=\"").append(fileSize).append('"')
        duration?.let { append(" duration=\"").append(escapeXmlAttribute(it)).append('"') }
        append('>').append(escapeXmlText(resourceUri.toASCIIString())).append("</res>")
        append("</item></DIDL-Lite>")
    }
}

internal fun buildDesktopUpnpSoapRequest(
    serviceType: String,
    action: String,
    arguments: Map<String, String>,
): ByteArray {
    require(serviceType.matches(UPNP_SERVICE_IDENTIFIER)) { "Invalid UPnP service type." }
    require(action.matches(XML_IDENTIFIER)) { "Invalid UPnP action name." }
    require(arguments.keys.all { it.matches(XML_IDENTIFIER) }) { "Invalid UPnP argument name." }
    val body = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(escapeXmlAttribute(serviceType)).append("\">")
        arguments.forEach { (name, value) ->
            append('<').append(name).append('>').append(escapeXmlText(value)).append("</").append(name).append('>')
        }
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }
    return body.toByteArray(Charsets.UTF_8)
}

internal fun parseDesktopUpnpSoapResponse(xml: ByteArray, action: String): Map<String, String> {
    require(xml.size in 1..MAX_SOAP_RESPONSE_BYTES) { "UPnP SOAP response is empty or too large." }
    require(action.matches(XML_IDENTIFIER)) { "Invalid UPnP action name." }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    val root = document.documentElement ?: throw IOException("UPnP SOAP response has no envelope.")
    val body = directUpnpChild(root, "Body") ?: throw IOException("UPnP SOAP response has no body.")
    val fault = directUpnpChild(body, "Fault")
    if (fault != null) {
        val errorCode = firstDescendantText(fault, "errorCode")?.toIntOrNull()
        val error = firstDescendantText(fault, "errorDescription")
            ?: firstDescendantText(fault, "faultstring")
            ?: errorCode?.toString()
            ?: "Renderer returned a SOAP fault."
        throw DesktopUpnpSoapFaultException(action, errorCode, error)
    }
    val response = directUpnpChildren(body).firstOrNull { it.localName == "${action}Response" }
        ?: throw IOException("UPnP SOAP response does not contain ${action}Response.")
    return directUpnpChildren(response).associate { it.localName to it.textContent.trim() }
}

internal fun parseDesktopUpnpTime(value: String?): Long? {
    val match = value?.trim()?.let { UPNP_TIME.matchEntire(it) } ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: return null
    val minutes = match.groupValues[2].toLongOrNull() ?: return null
    val seconds = match.groupValues[3].toLongOrNull() ?: return null
    if (minutes !in 0..59 || seconds !in 0..59) return null
    val fraction = match.groupValues[4].take(3).padEnd(3, '0').toLongOrNull() ?: 0L
    return runCatching {
        val totalSeconds = Math.addExact(Math.multiplyExact(hours, 3_600L), minutes * 60L + seconds)
        Math.addExact(Math.multiplyExact(totalSeconds, 1_000L), fraction)
    }
        .getOrNull()
}

internal fun formatDesktopUpnpTime(positionMillis: Long): String {
    require(positionMillis >= 0L) { "Position must be non-negative." }
    val totalSeconds = positionMillis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    val millis = positionMillis % 1_000L
    return "%02d:%02d:%02d.%03d".format(java.util.Locale.ROOT, hours, minutes, seconds, millis)
}

private fun readBoundedSoapResponse(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4_096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() + count > MAX_SOAP_RESPONSE_BYTES) {
            throw IOException("UPnP SOAP response exceeds the size limit.")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun directUpnpChild(parent: Element, name: String): Element? =
    directUpnpChildren(parent).firstOrNull { it.localName == name }

private fun directUpnpChildren(parent: Element): List<Element> = buildList {
    val children = parent.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child.nodeType == Node.ELEMENT_NODE) add(child as Element)
    }
}

private fun firstDescendantText(parent: Element, name: String): String? {
    if (parent.localName == name) return parent.textContent.trim().takeIf(String::isNotEmpty)
    for (child in directUpnpChildren(parent)) firstDescendantText(child, name)?.let { return it }
    return null
}

private fun escapeXmlText(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character.toString()
            },
        )
    }
}

private fun escapeXmlAttribute(value: String): String = escapeXmlText(value)

private val XML_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
private val UPNP_SERVICE_IDENTIFIER = Regex(
    "(?:urn:schemas-upnp-org:service:(?:AVTransport|ConnectionManager|RenderingControl):[1-9][0-9]*|" +
        "urn:av-openhome-org:service:Playlist:[1-9][0-9]*)",
    RegexOption.IGNORE_CASE,
)
private val UPNP_TIME = Regex("(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d+))?")
private const val SOAP_CONNECT_TIMEOUT_MILLIS = 2_000
private const val SOAP_READ_TIMEOUT_MILLIS = 5_000
private const val MAX_SOAP_RESPONSE_BYTES = 256 * 1024
private const val SCPD_CONNECT_TIMEOUT_MILLIS = 2_000
private const val SCPD_READ_TIMEOUT_MILLIS = 5_000
private const val MAX_SCPD_RESPONSE_BYTES = 256 * 1024
private val SCPD_CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(10)
private const val MAX_UPNP_VOLUME_VALUE = 65_535

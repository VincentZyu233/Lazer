package dev.naominet.lazer

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLConnection
import java.util.Collections
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal enum class DesktopUpnpRendererServiceKind(val serviceName: String) {
    AV_TRANSPORT("AVTransport"),
    CONNECTION_MANAGER("ConnectionManager"),
    RENDERING_CONTROL("RenderingControl"),
    OPENHOME_PLAYLIST("Playlist"),
}

internal data class DesktopUpnpServiceEndpoint(
    val serviceType: String,
    val controlUri: URI,
    val eventSubUri: URI?,
    val scpdUri: URI?,
)

/** A discovered remote player. This is separate from DesktopAudioOutputDevice (a local PCM sink). */
internal data class DesktopUpnpRendererDevice(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val descriptionUri: URI,
    val services: Map<DesktopUpnpRendererServiceKind, DesktopUpnpServiceEndpoint>,
) {
    val identity: String get() = "upnp:$udn"

    val supportsAvTransport: Boolean
        get() = DesktopUpnpRendererServiceKind.AV_TRANSPORT in services

    val hasOpenHomePlaylist: Boolean
        get() = DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST in services
}

internal data class DesktopSsdpReply(
    val sourceAddress: InetAddress,
    val location: URI,
)

internal fun parseDesktopSsdpReply(sourceAddress: InetAddress, response: String): DesktopSsdpReply? {
    val lines = response.split("\r\n", "\n")
    val status = lines.firstOrNull()?.trim()?.uppercase() ?: return null
    if (status != "HTTP/1.1 200 OK" && status != "HTTP/1.0 200 OK") return null

    val locationValue = lines.drop(1).asSequence()
        .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0 || !line.substring(0, colon).trim().equals("LOCATION", ignoreCase = true)) {
                null
            } else {
                line.substring(colon + 1).trim()
            }
        }
        .firstOrNull()
        ?.takeIf(String::isNotBlank)
        ?: return null
    val location = runCatching { URI(locationValue) }.getOrNull() ?: return null
    if (!isHttpUri(location)) return null
    return DesktopSsdpReply(sourceAddress, location)
}

internal fun desktopUpnpMSearchRequest(searchTarget: String): ByteArray = buildString {
    append("M-SEARCH * HTTP/1.1\r\n")
    append("HOST: 239.255.255.250:1900\r\n")
    append("MAN: \"ssdp:discover\"\r\n")
    append("MX: 2\r\n")
    append("ST: ").append(searchTarget).append("\r\n")
    append("USER-AGENT: Lazer/1.0 UPnP/1.1\r\n\r\n")
}.toByteArray(Charsets.US_ASCII)

internal fun parseDesktopUpnpRendererDescription(
    xml: ByteArray,
    descriptionUri: URI,
): List<DesktopUpnpRendererDevice> {
    require(xml.size in 1..MAX_DEVICE_DESCRIPTION_BYTES) { "UPnP device description is empty or too large." }
    require(isHttpUri(descriptionUri)) { "UPnP description URL must use HTTP or HTTPS." }

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
    val root = document.documentElement ?: return emptyList()
    val urlBase = directChildText(root, "URLBase")
        ?.let { runCatching { descriptionUri.resolve(URI(it)) }.getOrNull() }
        ?.takeIf { isSameOrigin(descriptionUri, it) }
        ?: descriptionUri

    val renderers = mutableListOf<DesktopUpnpRendererDevice>()
    fun visit(device: Element) {
        val deviceType = directChildText(device, "deviceType").orEmpty()
        val rendererVersion = MEDIA_RENDERER_TYPE.matchEntire(deviceType)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it > 0 }
        if (rendererVersion != null) {
            val udn = directChildText(device, "UDN")?.trim().orEmpty()
            val name = directChildText(device, "friendlyName")?.trim().orEmpty()
            if (udn.isNotBlank() && name.isNotBlank()) {
                val serviceList = directChild(device, "serviceList")
                val services = serviceList?.let { parseServices(it, urlBase) }.orEmpty()
                renderers += DesktopUpnpRendererDevice(
                    udn = udn,
                    friendlyName = name,
                    manufacturer = directChildText(device, "manufacturer")?.trim()?.takeIf(String::isNotEmpty),
                    modelName = directChildText(device, "modelName")?.trim()?.takeIf(String::isNotEmpty),
                    modelNumber = directChildText(device, "modelNumber")?.trim()?.takeIf(String::isNotEmpty),
                    descriptionUri = descriptionUri,
                    services = services,
                )
            }
        }

        directChild(device, "deviceList")?.let { deviceList ->
            directChildren(deviceList, "device").forEach(::visit)
        }
    }

    directChild(root, "device")?.let(::visit)
    return renderers.distinctBy(DesktopUpnpRendererDevice::identity)
}

private fun parseServices(
    serviceList: Element,
    baseUri: URI,
): Map<DesktopUpnpRendererServiceKind, DesktopUpnpServiceEndpoint> {
    val endpoints = mutableMapOf<DesktopUpnpRendererServiceKind, Pair<Int, DesktopUpnpServiceEndpoint>>()
    for (service in directChildren(serviceList, "service")) {
        val serviceType = directChildText(service, "serviceType")?.trim().orEmpty()
        val version = when {
            OPENHOME_PLAYLIST_SERVICE_TYPE.matchEntire(serviceType) != null -> {
                val match = OPENHOME_PLAYLIST_SERVICE_TYPE.matchEntire(serviceType) ?: continue
                (match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: continue) to
                    DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST
            }
            else -> UPnP_SERVICE_TYPE.matchEntire(serviceType)?.let { match ->
                val kind = DesktopUpnpRendererServiceKind.entries.firstOrNull {
                    it != DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST &&
                        it.serviceName.equals(match.groupValues[1], ignoreCase = true)
                } ?: continue
                val serviceVersion = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: continue
                serviceVersion to kind
            } ?: continue
        }
        val controlUri = resolveSameOrigin(baseUri, directChildText(service, "controlURL")) ?: continue
        val endpoint = DesktopUpnpServiceEndpoint(
            serviceType = serviceType,
            controlUri = controlUri,
            eventSubUri = resolveSameOrigin(baseUri, directChildText(service, "eventSubURL")),
            scpdUri = resolveSameOrigin(baseUri, directChildText(service, "SCPDURL")),
        )
        val (serviceVersion, kind) = version
        if (endpoints[kind]?.first?.let { it >= serviceVersion } != true) {
            endpoints[kind] = serviceVersion to endpoint
        }
    }
    return endpoints.mapValues { it.value.second }
}

private fun directChild(parent: Element, name: String): Element? =
    directChildren(parent, name).firstOrNull()

private fun directChildren(parent: Element, name: String): List<Element> = buildList {
    val children = parent.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child.nodeType == Node.ELEMENT_NODE && child.localName == name) {
            add(child as Element)
        }
    }
}

private fun directChildText(parent: Element, name: String): String? =
    directChild(parent, name)?.textContent?.trim()?.takeIf(String::isNotEmpty)

private fun resolveSameOrigin(baseUri: URI, raw: String?): URI? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val resolved = runCatching { baseUri.resolve(URI(value)) }.getOrNull() ?: return null
    return resolved.takeIf { isHttpUri(it) && isSameOrigin(baseUri, it) && it.userInfo == null }
}

internal fun isHttpUri(uri: URI): Boolean =
    (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
        !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
        (uri.port == -1 || uri.port in 1..65_535)

internal fun isSameOrigin(first: URI, second: URI): Boolean =
    first.scheme.equals(second.scheme, ignoreCase = true) &&
        first.host.equals(second.host, ignoreCase = true) &&
        effectivePort(first) == effectivePort(second) && second.userInfo == null

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}

internal fun isDesktopUpnpLocationFromResponder(location: URI, responder: InetAddress): Boolean {
    val resolved = desktopUpnpNumericAddress(location) ?: return false
    return resolved.address.contentEquals(responder.address)
}

internal fun desktopUpnpNumericAddress(uri: URI): InetAddress? {
    if (!isHttpUri(uri)) return null
    val host = uri.host.orEmpty().removePrefix("[").removeSuffix("]").replace("%25", "%")
    val addressPart = host.substringBefore('%')
    val isNumericAddress = when {
        ':' in addressPart -> addressPart.all { it.isDigit() || it in ":.abcdefABCDEF" }
        IPV4_LITERAL.matches(addressPart) -> addressPart.split('.').all { octet ->
            octet.toIntOrNull()?.let { it in 0..255 } == true
        }
        else -> false
    }
    if (!isNumericAddress) return null
    if ('%' in host) {
        val scope = host.substringAfter('%')
        if (scope.isBlank() || !scope.all { it.isLetterOrDigit() || it in "_.-" }) return null
    }
    return runCatching { InetAddress.getByName(host) }.getOrNull()
}

internal fun interface DesktopSsdpSearch {
    fun search(timeoutMillis: Long): List<DesktopSsdpReply>
}

internal fun interface DesktopUpnpDescriptionLoader {
    fun load(uri: URI): ByteArray
}

internal class DesktopUpnpRendererDiscovery(
    private val ssdpSearch: DesktopSsdpSearch = DesktopJdkSsdpSearch,
    private val descriptionLoader: DesktopUpnpDescriptionLoader = DesktopHttpDescriptionLoader,
) {
    suspend fun discover(timeoutMillis: Long = DEFAULT_SSDP_TIMEOUT_MILLIS): List<DesktopUpnpRendererDevice> {
        require(timeoutMillis in MIN_SSDP_TIMEOUT_MILLIS..MAX_SSDP_TIMEOUT_MILLIS) {
            "UPnP discovery timeout is outside the supported range."
        }
        return withContext(Dispatchers.IO) {
            val descriptions = ssdpSearch.search(timeoutMillis)
                .asSequence()
                .filter { isDesktopUpnpLocationFromResponder(it.location, it.sourceAddress) }
                .distinctBy { it.location.normalize().toASCIIString() }
                .take(MAX_DEVICE_DESCRIPTIONS)
                .map { it.location }
                .toList()

            val descriptionGate = Semaphore(MAX_CONCURRENT_DESCRIPTIONS)
            val discoveredRenderers = coroutineScope {
                descriptions.map { uri ->
                    async {
                        descriptionGate.withPermit {
                            runCatching {
                                parseDesktopUpnpRendererDescription(descriptionLoader.load(uri), uri)
                            }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll()
            }
            discoveredRenderers.flatten().distinctBy(DesktopUpnpRendererDevice::identity)
                .sortedWith { first, second ->
                    val byName = first.friendlyName.compareTo(second.friendlyName, ignoreCase = true)
                    if (byName != 0) byName else first.identity.compareTo(second.identity)
                }
        }
    }
}

private object DesktopJdkSsdpSearch : DesktopSsdpSearch {
    private val searchTargets = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:av-openhome-org:service:Playlist:1",
        "upnp:rootdevice",
    )

    override fun search(timeoutMillis: Long): List<DesktopSsdpReply> {
        val activeInterfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { networkInterface ->
                    runCatching {
                        networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isVirtual &&
                            Collections.list(networkInterface.inetAddresses).any { address ->
                                address is Inet4Address || address is Inet6Address
                            }
                    }.getOrDefault(false)
                }
        }.getOrDefault(emptyList())
        if (activeInterfaces.isEmpty()) return emptyList()

        val destination = InetSocketAddress(SSDP_MULTICAST_ADDRESS, SSDP_PORT)
        val replies = linkedMapOf<String, DesktopSsdpReply>()
        MulticastSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.timeToLive = 2
            socket.soTimeout = RECEIVE_POLL_MILLIS.toInt()

            for (networkInterface in activeInterfaces) {
                runCatching {
                    socket.networkInterface = networkInterface
                    for (target in searchTargets) {
                        val data = desktopUpnpMSearchRequest(target)
                        socket.send(DatagramPacket(data, data.size, destination))
                    }
                }
            }

            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            val buffer = ByteArray(MAX_SSDP_DATAGRAM_BYTES)
            while (true) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) break
                socket.soTimeout = (remainingNanos / 1_000_000L)
                    .coerceIn(1L, RECEIVE_POLL_MILLIS)
                    .toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val source = packet.address ?: continue
                val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                val reply = parseDesktopSsdpReply(source, text) ?: continue
                val key = "${source.hostAddress}|${reply.location.normalize()}"
                replies.putIfAbsent(key, reply)
            }
        }
        return replies.values.toList()
    }
}

private object DesktopHttpDescriptionLoader : DesktopUpnpDescriptionLoader {
    override fun load(uri: URI): ByteArray {
        require(isHttpUri(uri)) { "UPnP description URL must use HTTP or HTTPS." }
        val connection = uri.toURL().openConnection() as URLConnection
        connection.connectTimeout = DESCRIPTION_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = DESCRIPTION_READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "text/xml, application/xml;q=0.9, */*;q=0.1")
        if (connection is java.net.HttpURLConnection) {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) {
                throw IOException("UPnP device description returned HTTP ${connection.responseCode}.")
            }
        }
        try {
            val length = connection.contentLengthLong
            if (length > MAX_DEVICE_DESCRIPTION_BYTES) {
                throw IOException("UPnP device description exceeds the size limit.")
            }
            val output = ByteArrayOutputStream()
            connection.getInputStream().use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_DEVICE_DESCRIPTION_BYTES) {
                        throw IOException("UPnP device description exceeds the size limit.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            (connection as? java.net.HttpURLConnection)?.disconnect()
        }
    }
}

private const val MAX_DEVICE_DESCRIPTION_BYTES = 512 * 1024
private const val MAX_SSDP_DATAGRAM_BYTES = 8 * 1024
private const val MAX_DEVICE_DESCRIPTIONS = 32
private const val MAX_CONCURRENT_DESCRIPTIONS = 8
private const val DEFAULT_SSDP_TIMEOUT_MILLIS = 3_000L
private const val MIN_SSDP_TIMEOUT_MILLIS = 500L
private const val MAX_SSDP_TIMEOUT_MILLIS = 10_000L
private const val RECEIVE_POLL_MILLIS = 120L
private const val DESCRIPTION_CONNECT_TIMEOUT_MILLIS = 1_500
private const val DESCRIPTION_READ_TIMEOUT_MILLIS = 1_500
private const val SSDP_PORT = 1900
private val SSDP_MULTICAST_ADDRESS = InetAddress.getByName("239.255.255.250")
private val MEDIA_RENDERER_TYPE = Regex("urn:schemas-upnp-org:device:MediaRenderer:(\\d+)", RegexOption.IGNORE_CASE)
private val UPnP_SERVICE_TYPE = Regex("urn:schemas-upnp-org:service:(AVTransport|ConnectionManager|RenderingControl):(\\d+)", RegexOption.IGNORE_CASE)
private val OPENHOME_PLAYLIST_SERVICE_TYPE = Regex("urn:av-openhome-org:service:Playlist:([1-9][0-9]*)", RegexOption.IGNORE_CASE)
private val IPV4_LITERAL = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")

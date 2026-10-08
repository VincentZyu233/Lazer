package dev.naominet.lazer

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class DesktopOpenHomePlaylistArgument(
    val name: String,
    val direction: Direction,
    val dataType: String,
) {
    internal enum class Direction { IN, OUT }
}

internal data class DesktopOpenHomePlaylistCapabilities(
    val actions: Map<String, List<DesktopOpenHomePlaylistArgument>>,
    /** Evented SCPD state variables, mapped from their names to UPnP data types. */
    val eventedStateVariables: Map<String, String>,
)

internal data class DesktopOpenHomePlaylistIdArray(
    val token: Long,
    /** OpenHome IDs are opaque queue identities; this list must never be interpreted as indices. */
    val ids: List<Long>,
)

internal data class DesktopOpenHomePlaylistTrack(
    val id: Long,
    val uri: String,
    val metadata: String,
)

/** A typed control point for the OpenHome Playlist service (av-openhome-org:service:Playlist). */
internal class DesktopOpenHomePlaylistClient(
    private val soap: DesktopUpnpSoapTransport = DesktopHttpUpnpSoapTransport,
    private val scpd: DesktopUpnpScpdTransport = DesktopHttpUpnpScpdTransport,
) {
    private data class CachedCapabilities(
        val value: DesktopOpenHomePlaylistCapabilities,
        val fetchedAtNanos: Long,
    )

    private val capabilityCache = ConcurrentHashMap<String, CachedCapabilities>()

    suspend fun capabilities(device: DesktopUpnpRendererDevice): DesktopOpenHomePlaylistCapabilities {
        val endpoint = playlistEndpoint(device)
        val scpdUri = endpoint.scpdUri
            ?: throw IOException("OpenHome Playlist service does not publish an SCPD URL.")
        if (!isDesktopUpnpControlUriAllowed(device.descriptionUri, scpdUri)) {
            throw IOException("OpenHome Playlist SCPD URL is outside the device origin.")
        }
        val cacheKey = "${device.identity}|${endpoint.serviceType}|$scpdUri"
        val now = System.nanoTime()
        capabilityCache[cacheKey]?.let { cached ->
            val age = now - cached.fetchedAtNanos
            if (age >= 0 && age < SCPD_CACHE_TTL_NANOS) return cached.value
        }
        val parsed = try {
            parseDesktopOpenHomePlaylistCapabilities(scpd.fetch(scpdUri))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Could not read OpenHome Playlist capabilities: ${error.message}", error)
        }
        capabilityCache[cacheKey] = CachedCapabilities(parsed, System.nanoTime())
        return parsed
    }

    fun invalidateCapabilities(device: DesktopUpnpRendererDevice) {
        val prefix = "${device.identity}|"
        capabilityCache.keys.removeIf { it.startsWith(prefix) }
    }

    suspend fun transportState(device: DesktopUpnpRendererDevice): String =
        invoke(device, "TransportState", emptyMap(), emptyMap(), mapOf("Value" to "string"))
            .requiredText("Value")

    suspend fun id(device: DesktopUpnpRendererDevice): Long =
        invoke(device, "Id", emptyMap(), emptyMap(), mapOf("Value" to "ui4"))
            .requiredUint("Value")

    suspend fun tracksMax(device: DesktopUpnpRendererDevice): Long =
        invoke(device, "TracksMax", emptyMap(), emptyMap(), mapOf("Value" to "ui4"))
            .requiredUint("Value")

    suspend fun idArray(device: DesktopUpnpRendererDevice): DesktopOpenHomePlaylistIdArray {
        val result = invoke(
            device,
            "IdArray",
            emptyMap(),
            emptyMap(),
            mapOf("Token" to "ui4", "Array" to "bin.base64"),
        )
        val token = result.requiredUint("Token")
        val ids = parseDesktopOpenHomePlaylistIdArray(result.requiredText("Array"))
        return DesktopOpenHomePlaylistIdArray(token, ids)
    }

    suspend fun idArrayChanged(device: DesktopUpnpRendererDevice, token: Long): Boolean {
        require(token in 0L..UINT32_MAX) { "OpenHome ID array token must fit an unsigned 32-bit value." }
        val result = invoke(
            device,
            "IdArrayChanged",
            mapOf("Token" to token.toString()),
            mapOf("Token" to "ui4"),
            mapOf("Value" to "boolean"),
        )
        return when (result.requiredText("Value").trim().lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> throw IOException("OpenHome IdArrayChanged returned an invalid boolean value.")
        }
    }

    suspend fun protocolInfo(device: DesktopUpnpRendererDevice): String =
        invoke(device, "ProtocolInfo", emptyMap(), emptyMap(), mapOf("Value" to "string"))
            .requiredText("Value")

    suspend fun readList(device: DesktopUpnpRendererDevice, ids: List<Long>): List<DesktopOpenHomePlaylistTrack> {
        require(ids.size <= MAX_READ_LIST_IDS) { "OpenHome ReadList request contains too many IDs." }
        ids.forEach { require(it in 1L..UINT32_MAX) { "OpenHome track IDs must be non-zero unsigned 32-bit values." } }
        val idList = ids.joinToString(" ")
        require(idList.length <= MAX_READ_LIST_ID_CHARS) { "OpenHome ReadList request is too large." }
        val result = invoke(
            device,
            "ReadList",
            linkedMapOf("IdList" to idList),
            mapOf("IdList" to "string"),
            mapOf("TrackList" to "string"),
        )
        return parseDesktopOpenHomePlaylistReadList(result.requiredText("TrackList"))
    }

    suspend fun insert(
        device: DesktopUpnpRendererDevice,
        afterId: Long,
        uri: String,
        metadata: String,
    ): Long {
        require(afterId in 0L..UINT32_MAX) { "OpenHome AfterId must be an unsigned 32-bit value." }
        require(uri.isNotBlank() && uri.length <= MAX_TRACK_URI_CHARS) { "OpenHome track URI is empty or too long." }
        require(metadata.length <= MAX_TRACK_METADATA_CHARS) { "OpenHome track metadata is too large." }
        val result = invoke(
            device,
            "Insert",
            linkedMapOf("AfterId" to afterId.toString(), "Uri" to uri, "Metadata" to metadata),
            mapOf("AfterId" to "ui4", "Uri" to "string", "Metadata" to "string"),
            mapOf("NewId" to "ui4"),
        )
        return result.requiredUint("NewId").also {
            if (it == 0L) throw IOException("OpenHome Insert returned the reserved zero ID.")
        }
    }

    /** Inserts in input order: first at the head (AfterId=0), then after each returned opaque ID. */
    suspend fun insertAtHead(
        device: DesktopUpnpRendererDevice,
        tracks: List<Pair<String, String>>,
    ): List<Long> {
        require(tracks.size <= MAX_INSERT_BATCH_TRACKS) { "OpenHome insert batch contains too many tracks." }
        val ids = ArrayList<Long>(tracks.size)
        var afterId = 0L
        for ((uri, metadata) in tracks) {
            val newId = insert(device, afterId, uri, metadata)
            ids += newId
            afterId = newId
        }
        return ids
    }

    suspend fun deleteAll(device: DesktopUpnpRendererDevice) {
        invoke(device, "DeleteAll", emptyMap(), emptyMap(), emptyMap())
    }

    suspend fun deleteId(device: DesktopUpnpRendererDevice, id: Long) {
        require(id in 1L..UINT32_MAX) { "OpenHome track IDs must be non-zero unsigned 32-bit values." }
        invoke(device, "DeleteId", mapOf("Value" to id.toString()), mapOf("Value" to "ui4"), emptyMap())
    }

    suspend fun play(device: DesktopUpnpRendererDevice) = invokeNoArg(device, "Play")
    suspend fun pause(device: DesktopUpnpRendererDevice) = invokeNoArg(device, "Pause")
    suspend fun stop(device: DesktopUpnpRendererDevice) = invokeNoArg(device, "Stop")
    suspend fun next(device: DesktopUpnpRendererDevice) = invokeNoArg(device, "Next")
    suspend fun previous(device: DesktopUpnpRendererDevice) = invokeNoArg(device, "Previous")

    suspend fun seekId(device: DesktopUpnpRendererDevice, id: Long) {
        require(id in 1L..UINT32_MAX) { "OpenHome track IDs must be non-zero unsigned 32-bit values." }
        invoke(device, "SeekId", mapOf("Value" to id.toString()), mapOf("Value" to "ui4"), emptyMap())
    }

    suspend fun seekSecondAbsolute(device: DesktopUpnpRendererDevice, second: Long) {
        require(second in 0L..UINT32_MAX) { "OpenHome seek seconds must fit an unsigned 32-bit value." }
        invoke(
            device,
            "SeekSecondAbsolute",
            mapOf("Value" to second.toString()),
            mapOf("Value" to "ui4"),
            emptyMap(),
        )
    }

    private suspend fun invokeNoArg(device: DesktopUpnpRendererDevice, action: String) {
        invoke(device, action, emptyMap(), emptyMap(), emptyMap())
    }

    private suspend fun invoke(
        device: DesktopUpnpRendererDevice,
        action: String,
        arguments: Map<String, String>,
        expectedInputs: Map<String, String>,
        expectedOutputs: Map<String, String>,
    ): Map<String, String> {
        playlistEndpoint(device)
        val actionArguments = capabilities(device).actions[action]
            ?: throw IOException("OpenHome Playlist service does not advertise $action.")
        val actualInputs = actionArguments.filter { it.direction == DesktopOpenHomePlaylistArgument.Direction.IN }
            .associate { it.name to it.dataType }
        val actualOutputs = actionArguments.filter { it.direction == DesktopOpenHomePlaylistArgument.Direction.OUT }
            .associate { it.name to it.dataType }
        if (actualInputs != expectedInputs || actualOutputs != expectedOutputs) {
            throw IOException("OpenHome Playlist $action has an unsupported SCPD signature.")
        }
        if (arguments.keys != expectedInputs.keys) {
            throw IllegalArgumentException("OpenHome Playlist $action arguments do not match its typed signature.")
        }
        return soap.invoke(device, DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST, action, arguments)
    }

    private fun playlistEndpoint(device: DesktopUpnpRendererDevice): DesktopUpnpServiceEndpoint {
        val endpoint = device.services[DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST]
            ?: throw IOException("Renderer does not expose OpenHome Playlist.")
        if (!OPENHOME_PLAYLIST_URN.matches(endpoint.serviceType)) {
            throw IOException("Renderer published an invalid OpenHome Playlist service type.")
        }
        if (!isDesktopUpnpControlUriAllowed(device.descriptionUri, endpoint.controlUri)) {
            throw IOException("OpenHome Playlist control URL is outside the device origin.")
        }
        return endpoint
    }
}

internal fun parseDesktopOpenHomePlaylistCapabilities(xml: ByteArray): DesktopOpenHomePlaylistCapabilities {
    require(xml.size in 1..MAX_OPENHOME_SCPD_BYTES) { "OpenHome SCPD is empty or too large." }
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
    val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
        ?: throw IOException("OpenHome SCPD has no root element.")
    if (root.localName != "scpd" || root.namespaceURI != UPNP_SCPD_NAMESPACE) {
        throw IOException("OpenHome SCPD has an unexpected root namespace or element.")
    }
    val stateTypes = linkedMapOf<String, String>()
    val eventedStateVariables = linkedMapOf<String, String>()
    val serviceStateTable = child(root, "serviceStateTable")
        ?: throw IOException("OpenHome SCPD has no service state table.")
    directChildren(serviceStateTable, "stateVariable").forEach { variable ->
        val name = text(variable, "name") ?: throw IOException("OpenHome SCPD state variable has no name.")
        val type = text(variable, "dataType") ?: throw IOException("OpenHome SCPD state variable has no data type.")
        if (stateTypes.putIfAbsent(name, type) != null) throw IOException("OpenHome SCPD contains a duplicate state variable.")
        if (isDesktopOpenHomeStateVariableEvented(variable)) eventedStateVariables[name] = type
    }
    val actionList = child(root, "actionList") ?: throw IOException("OpenHome SCPD has no action list.")
    val actions = linkedMapOf<String, List<DesktopOpenHomePlaylistArgument>>()
    directChildren(actionList, "action").forEach { actionElement ->
        val name = text(actionElement, "name") ?: throw IOException("OpenHome SCPD action has no name.")
        if (!XML_IDENTIFIER.matches(name)) throw IOException("OpenHome SCPD contains an invalid action name.")
        val argumentList = child(actionElement, "argumentList")
        val parsedArguments = argumentList?.let { args ->
            directChildren(args, "argument").map { argument ->
                val argumentName = text(argument, "name")
                    ?: throw IOException("OpenHome SCPD argument has no name.")
                if (!XML_IDENTIFIER.matches(argumentName)) throw IOException("OpenHome SCPD contains an invalid argument name.")
                val direction = when (text(argument, "direction")) {
                    "in" -> DesktopOpenHomePlaylistArgument.Direction.IN
                    "out" -> DesktopOpenHomePlaylistArgument.Direction.OUT
                    else -> throw IOException("OpenHome SCPD argument has an invalid direction.")
                }
                val relatedStateVariable = text(argument, "relatedStateVariable")
                    ?: throw IOException("OpenHome SCPD argument has no related state variable.")
                val dataType = stateTypes[relatedStateVariable]
                    ?: throw IOException("OpenHome SCPD argument references an unknown state variable.")
                DesktopOpenHomePlaylistArgument(argumentName, direction, dataType)
            }
        }.orEmpty()
        if (parsedArguments.map { it.name }.distinct().size != parsedArguments.size) {
            throw IOException("OpenHome SCPD action contains duplicate argument names.")
        }
        if (actions.putIfAbsent(name, parsedArguments) != null) {
            throw IOException("OpenHome SCPD contains a duplicate action.")
        }
    }
    if (actions.isEmpty()) throw IOException("OpenHome SCPD advertises no actions.")
    return DesktopOpenHomePlaylistCapabilities(
        actions = actions,
        eventedStateVariables = eventedStateVariables,
    )
}

/** UPnP defaults an omitted sendEvents attribute to yes; malformed values fail closed. */
private fun isDesktopOpenHomeStateVariableEvented(variable: Element): Boolean =
    when (variable.getAttributeNode("sendEvents")?.value?.trim()?.lowercase()) {
        null -> true
        "yes", "1" -> true
        "no", "0" -> false
        else -> false
    }

internal fun parseDesktopOpenHomePlaylistIdArray(
    encoded: String,
    maximumEntries: Int = MAX_OPENHOME_ID_ARRAY_ENTRIES,
): List<Long> {
    require(maximumEntries in 0..MAX_OPENHOME_ID_ARRAY_ENTRIES) { "OpenHome ID array bound is invalid." }
    require(encoded.length <= MAX_OPENHOME_BASE64_CHARS) { "OpenHome ID array exceeds the encoded size limit." }
    val normalized = buildString(encoded.length) {
        encoded.forEach { character ->
            when (character) {
                ' ', '\t', '\r', '\n' -> Unit
                else -> append(character)
            }
        }
    }
    require(normalized.length <= encodedLengthFor(maximumEntries * 4)) { "OpenHome ID array exceeds the entry limit." }
    val bytes = try {
        Base64.getDecoder().decode(normalized)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("OpenHome ID array is not valid base64.", error)
    }
    require(Base64.getEncoder().encodeToString(bytes) == normalized) { "OpenHome ID array is not canonical base64." }
    require(bytes.size % 4 == 0) { "OpenHome ID array length is not a multiple of four bytes." }
    require(bytes.size / 4 <= maximumEntries) { "OpenHome ID array exceeds the entry limit." }
    val ids = ArrayList<Long>(bytes.size / 4)
    var offset = 0
    while (offset < bytes.size) {
        val id = ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)
        require(id != 0L) { "OpenHome ID array contains the reserved zero ID." }
        ids += id
        offset += 4
    }
    require(ids.distinct().size == ids.size) { "OpenHome ID array contains duplicate track IDs." }
    return ids
}

internal fun parseDesktopOpenHomePlaylistReadList(xml: String): List<DesktopOpenHomePlaylistTrack> {
    require(xml.length <= MAX_OPENHOME_READ_LIST_BYTES) { "OpenHome ReadList response is too large." }
    val bytes = xml.toByteArray(Charsets.UTF_8)
    require(bytes.size in 1..MAX_OPENHOME_READ_LIST_BYTES) { "OpenHome ReadList response is empty or too large." }
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
    val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        ?: throw IOException("OpenHome ReadList response has no root element.")
    if (root.localName != "TrackList") throw IOException("OpenHome ReadList response root is not TrackList.")
    val rootChildren = elementChildren(root)
    if (rootChildren.any { it.localName != "Entry" }) throw IOException("OpenHome ReadList contains an unexpected element.")
    val entries = rootChildren
    require(entries.size <= MAX_READ_LIST_IDS) { "OpenHome ReadList response contains too many tracks." }
    val tracks = entries.map { entry ->
        val idText = text(entry, "Id") ?: throw IOException("OpenHome ReadList entry has no ID.")
        val id = parseUint(idText, "OpenHome track ID").also {
            if (it == 0L) throw IOException("OpenHome ReadList entry uses the reserved zero ID.")
        }
        val fields = elementChildren(entry)
        if (fields.size != 3 || fields.map { it.localName }.toSet() != setOf("Id", "Uri", "Metadata")) {
            throw IOException("OpenHome ReadList entry has missing or duplicate fields.")
        }
        val uri = child(entry, "Uri")?.textContent ?: throw IOException("OpenHome ReadList entry has no URI.")
        val metadata = child(entry, "Metadata")?.textContent
            ?: throw IOException("OpenHome ReadList entry has no metadata field.")
        DesktopOpenHomePlaylistTrack(id, uri, metadata)
    }
    if (tracks.map { it.id }.distinct().size != tracks.size) throw IOException("OpenHome ReadList contains duplicate IDs.")
    return tracks
}

private fun parseUint(raw: String, label: String): Long {
    if (raw.length !in 1..10 || raw.any { it !in '0'..'9' }) throw IOException("$label is not an unsigned 32-bit integer.")
    return raw.toLongOrNull()?.takeIf { it <= UINT32_MAX }
        ?: throw IOException("$label is outside the unsigned 32-bit range.")
}

private fun Map<String, String>.requiredText(name: String): String = this[name]
    ?: throw IOException("OpenHome service response is missing $name.")

private fun Map<String, String>.requiredUint(name: String): Long = parseUint(requiredText(name), name)

private fun child(parent: Element, name: String): Element? = directChildren(parent, name).firstOrNull()

private fun text(parent: Element, name: String): String? = child(parent, name)?.textContent?.trim()

private fun directChildren(parent: Element, name: String): List<Element> = buildList {
    elementChildren(parent).filterTo(this) { it.localName == name }
}

private fun elementChildren(parent: Element): List<Element> = buildList {
    val children = parent.childNodes
    for (index in 0 until children.length) {
        val node = children.item(index)
        if (node.nodeType == Node.ELEMENT_NODE) add(node as Element)
    }
}

private fun encodedLengthFor(byteCount: Int): Int = ((byteCount + 2) / 3) * 4

private val OPENHOME_PLAYLIST_URN = Regex("urn:av-openhome-org:service:Playlist:[1-9][0-9]*", RegexOption.IGNORE_CASE)
private val XML_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
private const val UPNP_SCPD_NAMESPACE = "urn:schemas-upnp-org:service-1-0"
private const val UINT32_MAX = 4_294_967_295L
private const val MAX_OPENHOME_SCPD_BYTES = 256 * 1024
private const val MAX_OPENHOME_READ_LIST_BYTES = 1024 * 1024
private const val MAX_OPENHOME_ID_ARRAY_ENTRIES = 48_000
private const val MAX_OPENHOME_BASE64_CHARS = 256 * 1024
private const val MAX_READ_LIST_IDS = 10_000
private const val MAX_READ_LIST_ID_CHARS = 120_000
private const val MAX_INSERT_BATCH_TRACKS = 10_000
private const val MAX_TRACK_URI_CHARS = 8_192
private const val MAX_TRACK_METADATA_CHARS = 64 * 1024
private val SCPD_CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(10)

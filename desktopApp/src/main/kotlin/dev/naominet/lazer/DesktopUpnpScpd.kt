package dev.naominet.lazer

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

internal data class DesktopUpnpScpdCapabilities(
    val actions: Set<String>,
    val supportsRelativeTimeSeek: Boolean,
    val volumeMaximum: Int? = null,
)

/**
 * Parses service actions, relative-time seek support, and a numeric RenderingControl volume maximum.
 * A missing or vendor-defined volume maximum stays unknown; callers must not treat it as 100.
 *
 * A successfully parsed empty action list returns a capability value with an empty action set.
 * Callers should keep a missing or not-yet-fetched description separate (for example as null) from
 * this known-empty value.
 */
internal fun parseDesktopUpnpScpdCapabilities(xml: ByteArray): DesktopUpnpScpdCapabilities {
    require(xml.size in 1..MAX_SCPD_BYTES) { "UPnP service description is empty or too large." }

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
    val builder = factory.newDocumentBuilder().apply {
        // Keep the parser fail-closed even if a provider does not honor one of the feature flags.
        setEntityResolver { _, _ -> throw SAXException("External XML entities are not permitted.") }
        setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = throw exception
            override fun error(exception: SAXParseException) = throw exception
            override fun fatalError(exception: SAXParseException) = throw exception
        })
    }
    val document = builder.parse(ByteArrayInputStream(xml))
    val root = document.documentElement
        ?: throw SAXException("UPnP service description has no root element.")
    if (!root.isScpdElement("scpd")) {
        throw SAXException("UPnP service description has an unexpected root element.")
    }

    val actionList = root.directScpdChildren("actionList").singleOrNull()
        ?: throw SAXException("UPnP service description has no unique actionList.")
    val actions = linkedSetOf<String>()
    val actionElements = actionList.directScpdChildren("action")
    for (action in actionElements) {
        actions += action.requiredScpdText("name", "UPnP action has no unique name element.")
    }
    val serviceStateTable = root.directScpdChildren("serviceStateTable").singleOrNull()
    val supportsRelativeTimeSeek = serviceStateTable != null && actionElements.any { action ->
        action.directScpdChildText("name") == "Seek" &&
            action.supportsRelativeTimeSeek(serviceStateTable)
    }
    val volumeMaximum = serviceStateTable?.volumeMaximum()
    return DesktopUpnpScpdCapabilities(
        actions = actions.toSet(),
        supportsRelativeTimeSeek = supportsRelativeTimeSeek,
        volumeMaximum = volumeMaximum,
    )
}

private fun Element.isScpdElement(localName: String): Boolean =
    namespaceURI == UPNP_SCPD_NAMESPACE && this.localName == localName

private fun Element.directScpdChildren(localName: String): List<Element> = buildList {
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child.nodeType == Node.ELEMENT_NODE) {
            val element = child as Element
            if (element.isScpdElement(localName)) add(element)
        }
    }
}

private fun Element.directScpdChildText(localName: String): String? =
    directScpdChildren(localName).singleOrNull()?.scpdTextOrNull()

private fun Element.requiredScpdText(localName: String, error: String): String =
    directScpdChildren(localName).singleOrNull()?.scpdTextOrNull()
        ?: throw SAXException(error)

private fun Element.scpdTextOrNull(): String? {
    if (hasElementChild()) throw SAXException("UPnP SCPD text field must contain text only.")
    val value = textContent.trim()
    if (value.length > MAX_SCPD_ACTION_NAME_LENGTH || !SCPD_ACTION_NAME.matches(value)) {
        throw SAXException("UPnP action name is invalid or too long.")
    }
    return value
}

private fun Element.supportsRelativeTimeSeek(serviceStateTable: Element): Boolean {
    val argumentList = directScpdChildren("argumentList").singleOrNull() ?: return false
    val unitArguments = argumentList.directScpdChildren("argument").filter {
        it.directScpdChildText("name") == "Unit"
    }
    val unitArgument = unitArguments.singleOrNull() ?: return false
    if (unitArgument.directScpdChildText("direction") != "in") return false
    val relatedStateVariable = unitArgument.directScpdChildText("relatedStateVariable")
        ?.takeIf(String::isNotEmpty)
        ?: return false

    val matchingStateVariables = serviceStateTable.directScpdChildren("stateVariable").filter {
        it.directScpdChildText("name") == relatedStateVariable
    }
    val stateVariable = matchingStateVariables.singleOrNull() ?: return false
    val allowedValueList = stateVariable.directScpdChildren("allowedValueList").singleOrNull()
        ?: return false
    return allowedValueList.directScpdChildren("allowedValue").any { value ->
        if (value.hasElementChild()) throw SAXException("SCPD allowed values must contain text only.")
        value.textContent.trim() == "REL_TIME"
    }
}

private fun Element.volumeMaximum(): Int? {
    val volumeState = directScpdChildren("stateVariable")
        .filter { it.directScpdChildText("name") == "Volume" }
        .singleOrNull() ?: return null
    if (volumeState.directScpdPlainChildText("dataType") != "ui2") return null
    val range = volumeState.directScpdChildren("allowedValueRange").singleOrNull() ?: return null
    val minimum = range.directScpdPlainChildText("minimum")?.toIntOrNull() ?: return null
    val maximum = range.directScpdPlainChildText("maximum")?.toIntOrNull() ?: return null
    val step = range.directScpdPlainChildText("step")?.toIntOrNull() ?: return null
    return maximum.takeIf { minimum == 0 && step == 1 && it in 0..MAX_UPNP_VOLUME_VALUE }
}

private fun Element.directScpdPlainChildText(localName: String): String? =
    directScpdChildren(localName).singleOrNull()?.plainScpdTextOrNull()

private fun Element.plainScpdTextOrNull(): String? {
    if (hasElementChild()) return null
    return textContent.trim().takeIf(String::isNotEmpty)
}

private fun Element.hasElementChild(): Boolean {
    val children = childNodes
    for (index in 0 until children.length) {
        if (children.item(index).nodeType == Node.ELEMENT_NODE) return true
    }
    return false
}

private const val UPNP_SCPD_NAMESPACE = "urn:schemas-upnp-org:service-1-0"
private const val MAX_SCPD_BYTES = 256 * 1024
private const val MAX_SCPD_ACTION_NAME_LENGTH = 128
private const val MAX_UPNP_VOLUME_VALUE = 65_535
private val SCPD_ACTION_NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")

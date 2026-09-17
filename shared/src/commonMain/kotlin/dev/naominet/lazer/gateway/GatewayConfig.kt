package dev.naominet.lazer.gateway

private val GatewayCookieNamePattern = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")
private val GatewaySetCookieAttributeNames = setOf(
    "domain",
    "expires",
    "httponly",
    "max-age",
    "partitioned",
    "path",
    "priority",
    "sameparty",
    "samesite",
    "secure",
)

/**
 * Converts a Cookie header or the Gateway's flattened Set-Cookie response into a compact Cookie
 * header. Set-Cookie attributes are discarded and the last value wins when a name is repeated.
 */
fun normalizeGatewaySessionCookie(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val cookies = linkedMapOf<String, String>()
    value.split(';').forEach { rawSegment ->
        val segment = rawSegment.trim()
            .removePrefix("Set-Cookie:")
            .removePrefix("set-cookie:")
            .trim()
        val separator = segment.indexOf('=')
        if (separator <= 0) return@forEach
        val name = segment.substring(0, separator).trim()
        val cookieValue = segment.substring(separator + 1).trim()
        if (!GatewayCookieNamePattern.matches(name)) return@forEach
        if (name.lowercase() in GatewaySetCookieAttributeNames) return@forEach
        if ('\r' in cookieValue || '\n' in cookieValue) return@forEach
        cookies[name] = cookieValue
    }
    return cookies.entries
        .joinToString(separator = "; ") { (name, cookieValue) -> "$name=$cookieValue" }
        .takeIf(String::isNotEmpty)
}

/** Settings for the built-in client. Upstream origins are fixed by the route table. */
data class GatewayConfig(
    val userAgent: String? = null,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be greater than zero." }
    }

    public companion object {
        public const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 30_000
    }
}

/** Stores the Gateway session cookie independently from any platform-specific persistence layer. */
interface GatewaySessionStore {
    var cookie: String?
}

/** A simple session store suitable for a screen or application lifetime. */
class InMemoryGatewaySessionStore(
    override var cookie: String? = null,
) : GatewaySessionStore

/** The Gateway returned an HTTP error before a valid API response could be decoded. */
class GatewayHttpException(
    val statusCode: Int,
    val endpoint: String,
    val responseBody: String,
) : IllegalStateException("Gateway request to $endpoint failed with HTTP $statusCode.")

/** The Gateway returned a successful HTTP response that was not valid JSON. */
class GatewayProtocolException(
    val endpoint: String,
    cause: Throwable,
) : IllegalStateException("Gateway request to $endpoint returned malformed JSON.", cause)

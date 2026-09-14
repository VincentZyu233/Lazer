package dev.naominet.lazer.gateway

const val DEFAULT_GATEWAY_BASE_URL: String = "https://music.naominet.dev"

private val GatewayBaseUrlPattern = Regex(
    pattern = """^https?://(?:\[[0-9a-f:.]+]|[^\s/?#:@]+)(?::\d{1,5})?(?:/[^\s?#]*)?$""",
    option = RegexOption.IGNORE_CASE,
)

/**
 * Turns a user-entered Gateway origin into a stable base URL. Missing schemes use HTTPS; query
 * parameters, fragments and embedded credentials are rejected so they cannot leak into requests.
 */
fun normalizeGatewayBaseUrl(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val candidate = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https://${trimmed.substring(8)}"
        trimmed.startsWith("http://", ignoreCase = true) -> "http://${trimmed.substring(7)}"
        "://" in trimmed -> return null
        else -> "https://$trimmed"
    }.trimEnd('/')
    return candidate.takeIf(GatewayBaseUrlPattern::matches)
}

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

/**
 * Connection settings for an API Enhanced Gateway instance.
 *
 * The default points at the Gateway documented by the project. Applications can point this at
 * their own deployment instead, which is recommended for authenticated traffic.
 */
data class GatewayConfig(
    val baseUrl: String = DEFAULT_GATEWAY_BASE_URL,
    val realIp: String? = null,
    val randomChineseIp: Boolean = true,
    val userAgent: String? = null,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    init {
        val trimmedBaseUrl = baseUrl.trim()
        require(trimmedBaseUrl.startsWith("http://") || trimmedBaseUrl.startsWith("https://")) {
            "baseUrl must use HTTP or HTTPS."
        }
        require('?' !in trimmedBaseUrl && '#' !in trimmedBaseUrl) {
            "baseUrl must not contain query parameters or fragments."
        }
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be greater than zero." }
    }

    internal val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')

    internal fun endpointUrl(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//")) {
            "Gateway paths must start with one slash."
        }
        require("//" !in path && "://" !in path && '?' !in path && '#' !in path) {
            "Pass a relative Gateway path without query parameters or fragments."
        }
        return normalizedBaseUrl + path
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

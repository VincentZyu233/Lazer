package dev.naominet.lazer.gateway

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Builds the Gateway client with this platform's engine named explicitly. The argument-less
 * `HttpClient {}` reads its engine from a registry each engine module fills when it is first loaded,
 * and Kotlin/Native links away a module nothing refers to, so the registry lookup throws at startup.
 */
internal expect fun createLazerHttpClient(config: GatewayConfig): HttpClient

internal fun HttpClientConfig<*>.applyLazerGatewayDefaults(config: GatewayConfig) {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = config.requestTimeoutMillis
        connectTimeoutMillis = config.requestTimeoutMillis
        socketTimeoutMillis = config.requestTimeoutMillis
    }
    install(ContentNegotiation) {
        json(gatewayJson)
    }
}

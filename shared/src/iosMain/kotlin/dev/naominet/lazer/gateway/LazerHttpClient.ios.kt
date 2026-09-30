package dev.naominet.lazer.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun createLazerHttpClient(config: GatewayConfig): HttpClient =
    HttpClient(Darwin) { applyLazerGatewayDefaults(config) }

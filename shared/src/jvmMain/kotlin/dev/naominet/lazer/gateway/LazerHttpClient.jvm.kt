package dev.naominet.lazer.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

internal actual fun createLazerHttpClient(config: GatewayConfig): HttpClient =
    HttpClient(CIO) { applyLazerGatewayDefaults(config) }

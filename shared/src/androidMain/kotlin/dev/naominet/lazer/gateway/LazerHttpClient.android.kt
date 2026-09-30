package dev.naominet.lazer.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createLazerHttpClient(config: GatewayConfig): HttpClient =
    HttpClient(OkHttp) { applyLazerGatewayDefaults(config) }

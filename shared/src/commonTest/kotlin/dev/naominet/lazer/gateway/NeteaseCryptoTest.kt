package dev.naominet.lazer.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class NeteaseCryptoTest {
    @Test
    fun `eapi encryption matches the reference implementation`() {
        assertEquals(
            "1AF0E93B0E3EA03CE4E7F1B6AD7BD32BC198D7B70109AB343E0FC0C4A8F27C96AB8B7B5BE4DAD3E93B5186EA15C23045897B46981EF5BC30113659CE8F3814F29731744D18B7B45FDE561F3B50EB2983179F56A9B1DFA958D6D0313D5E3AB7EC",
            NeteaseCrypto.eapi("/api/search/get", "{\"s\":\"quiet\",\"type\":1}"),
        )
    }
}

package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXException

class DesktopUpnpScpdTest {
    @Test
    fun `parser reads actions independent of namespace prefix`() {
        val defaultNamespace = parseDesktopUpnpScpdCapabilities(
            scpdXml(
                """
                    <action><name>Play</name></action>
                    <action><name>Pause</name></action>
                """.trimIndent(),
            ).toByteArray(Charsets.UTF_8),
        )
        val explicitPrefix = parseDesktopUpnpScpdCapabilities(
            prefixedScpdXml(
                """
                    <u:action><u:name>Play</u:name></u:action>
                    <u:action><u:name>Pause</u:name></u:action>
                """.trimIndent(),
            ).toByteArray(Charsets.UTF_8),
        )

        assertEquals(setOf("Play", "Pause"), defaultNamespace.actions)
        assertEquals(defaultNamespace, explicitPrefix)
    }

    @Test
    fun `fetched empty action list is known empty and distinct from not fetched`() {
        val notFetched: Set<String>? = null
        val fetched = parseDesktopUpnpScpdCapabilities(scpdXml("").toByteArray(Charsets.UTF_8))

        assertNull(notFetched)
        assertNotNull(fetched)
        assertTrue(fetched.actions.isEmpty())
        assertFalse(fetched.supportsRelativeTimeSeek)
    }

    @Test
    fun `duplicate action names collapse into a capability set`() {
        val actions = parseDesktopUpnpScpdCapabilities(
            scpdXml(
                """
                    <action><name>Play</name></action>
                    <action><name>Play</name></action>
                    <action><name>Pause</name></action>
                """.trimIndent(),
            ).toByteArray(Charsets.UTF_8),
        )

        assertEquals(setOf("Play", "Pause"), actions.actions)
        assertEquals(2, actions.actions.size)
    }

    @Test
    fun `malformed XML is rejected`() {
        assertSaxFailure("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><actionList>")
    }

    @Test
    fun `oversized XML is rejected before parsing`() {
        val xml = ByteArray(MAX_TEST_SCPD_BYTES + 1) { ' '.code.toByte() }
        assertIllegalArgumentFailure { parseDesktopUpnpScpdCapabilities(xml) }
    }

    @Test
    fun `doctype and external entity declarations are rejected`() {
        val xml = """
            <!DOCTYPE scpd [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <actionList><action><name>&xxe;</name></action></actionList>
            </scpd>
        """.trimIndent()
        assertSaxFailure(xml)
    }

    @Test
    fun `overlong and invalid action names are rejected`() {
        val tooLong = "A".repeat(MAX_TEST_ACTION_NAME_LENGTH + 1)
        assertSaxFailure(scpdXml("<action><name>$tooLong</name></action>"))
        assertSaxFailure(scpdXml("<action><name>Set URI</name></action>"))
    }

    @Test
    fun `elements in another namespace do not count as SCPD actions`() {
        val xml = """
            <scpd xmlns="urn:schemas-upnp-org:service-1-0" xmlns:x="urn:attacker">
              <actionList><x:action><x:name>Play</x:name></x:action></actionList>
            </scpd>
        """.trimIndent()

        assertEquals(emptySet<String>(), parseDesktopUpnpScpdCapabilities(xml.toByteArray(Charsets.UTF_8)).actions)
    }

    @Test
    fun `unknown namespace root is rejected`() {
        val xml = """
            <scpd xmlns="urn:attacker"><actionList/></scpd>
        """.trimIndent()
        assertSaxFailure(xml)
    }

    @Test
    fun `Seek supports relative time only through its Unit state variable relation`() {
        val supported = parseDesktopUpnpScpdCapabilities(
            scpdWithSeek(relatedStateVariable = "A_ARG_TYPE_SeekMode", allowedValues = "<allowedValue>TRACK_NR</allowedValue><allowedValue>REL_TIME</allowedValue>")
                .toByteArray(Charsets.UTF_8),
        )
        val unsupported = parseDesktopUpnpScpdCapabilities(
            scpdWithSeek(relatedStateVariable = "A_ARG_TYPE_SeekMode", allowedValues = "<allowedValue>TRACK_NR</allowedValue>")
                .toByteArray(Charsets.UTF_8),
        )
        val unrelatedRelTime = parseDesktopUpnpScpdCapabilities(
            scpdWithSeek(
                relatedStateVariable = "A_ARG_TYPE_SeekMode",
                allowedValues = "<allowedValue>TRACK_NR</allowedValue>",
                unrelatedStateVariable = "<stateVariable sendEvents=\"no\"><name>OtherUnit</name><dataType>string</dataType><allowedValueList><allowedValue>REL_TIME</allowedValue></allowedValueList></stateVariable>",
            ).toByteArray(Charsets.UTF_8),
        )

        assertEquals(setOf("Seek"), supported.actions)
        assertTrue(supported.supportsRelativeTimeSeek)
        assertFalse(unsupported.supportsRelativeTimeSeek)
        assertFalse(unrelatedRelTime.supportsRelativeTimeSeek)
    }

    @Test
    fun `Seek Unit must be an input argument`() {
        val xml = scpdWithSeek(
            relatedStateVariable = "A_ARG_TYPE_SeekMode",
            allowedValues = "<allowedValue>REL_TIME</allowedValue>",
            direction = "out",
        )

        assertFalse(parseDesktopUpnpScpdCapabilities(xml.toByteArray(Charsets.UTF_8)).supportsRelativeTimeSeek)
    }

    @Test
    fun `Seek allowed value must be plain text rather than nested markup`() {
        val xml = scpdWithSeek(
            relatedStateVariable = "A_ARG_TYPE_SeekMode",
            allowedValues = "<allowedValue><extension>REL_TIME</extension></allowedValue>",
        )

        assertSaxFailure(xml)
    }

    @Test
    fun `RenderingControl volume range is read only when the device publishes a numeric scale`() {
        val numeric = parseDesktopUpnpScpdCapabilities(
            scpdWithVolume(maximum = "127").toByteArray(Charsets.UTF_8),
        )
        val vendorDefined = parseDesktopUpnpScpdCapabilities(
            scpdWithVolume(maximum = "Vendor defined").toByteArray(Charsets.UTF_8),
        )
        val invalid = parseDesktopUpnpScpdCapabilities(
            scpdWithVolume(maximum = "70000").toByteArray(Charsets.UTF_8),
        )

        assertEquals(127, numeric.volumeMaximum)
        assertNull(vendorDefined.volumeMaximum)
        assertNull(invalid.volumeMaximum)
    }
}

private fun scpdXml(actions: String): String = """
    <scpd xmlns="urn:schemas-upnp-org:service-1-0">
      <actionList>$actions</actionList>
    </scpd>
""".trimIndent()

private fun prefixedScpdXml(actions: String): String = """
    <s:scpd xmlns:s="urn:schemas-upnp-org:service-1-0" xmlns:u="urn:schemas-upnp-org:service-1-0">
      <s:actionList>$actions</s:actionList>
    </s:scpd>
""".trimIndent()

private fun scpdWithSeek(
    relatedStateVariable: String,
    allowedValues: String,
    direction: String = "in",
    unrelatedStateVariable: String = "",
): String = """
    <scpd xmlns="urn:schemas-upnp-org:service-1-0">
      <actionList>
        <action><name>Seek</name><argumentList><argument>
          <name>Unit</name><direction>$direction</direction>
          <relatedStateVariable>$relatedStateVariable</relatedStateVariable>
        </argument></argumentList></action>
      </actionList>
      <serviceStateTable>
        <stateVariable sendEvents="no">
          <name>$relatedStateVariable</name><dataType>string</dataType>
          <allowedValueList>$allowedValues</allowedValueList>
        </stateVariable>
        $unrelatedStateVariable
      </serviceStateTable>
    </scpd>
""".trimIndent()

private fun scpdWithVolume(maximum: String): String = """
    <scpd xmlns="urn:schemas-upnp-org:service-1-0">
      <actionList><action><name>GetVolume</name></action><action><name>SetVolume</name></action></actionList>
      <serviceStateTable><stateVariable sendEvents="no">
        <name>Volume</name><dataType>ui2</dataType><allowedValueRange>
          <minimum>0</minimum><maximum>$maximum</maximum><step>1</step>
        </allowedValueRange>
      </stateVariable></serviceStateTable>
    </scpd>
""".trimIndent()

private fun assertSaxFailure(xml: String) {
    try {
        parseDesktopUpnpScpdCapabilities(xml.toByteArray(Charsets.UTF_8))
        throw AssertionError("Expected malformed or disallowed SCPD XML to fail.")
    } catch (_: SAXException) {
    }
}

private fun assertIllegalArgumentFailure(block: () -> Unit) {
    try {
        block()
        throw AssertionError("Expected the input size limit to be enforced.")
    } catch (_: IllegalArgumentException) {
    }
}

private const val MAX_TEST_SCPD_BYTES = 256 * 1024
private const val MAX_TEST_ACTION_NAME_LENGTH = 128

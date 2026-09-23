package dev.sonora.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored login's envelope.
 *
 * Only the format is testable here — the Android Keystore that does the encrypting exists on a
 * device and nowhere else. A format that silently failed to parse would be indistinguishable from a
 * forgotten login, which is why it is worth pinning down.
 */
class CredentialEnvelopeTest {

    private val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val body = byteArrayOf(-1, -2, -3, 42, 0, 127)

    @Test
    fun `an envelope round-trips`() {
        val decoded = CredentialEnvelope.decode(CredentialEnvelope.encode(iv, body))

        assertArrayEquals(iv, decoded?.first)
        assertArrayEquals(body, decoded?.second)
    }

    @Test
    fun `text that is not an envelope reports nothing`() {
        assertNull(CredentialEnvelope.decode(""))
        assertNull(CredentialEnvelope.decode("no separator here"))
    }

    @Test
    fun `text that is not base64 reports nothing`() {
        assertNull(CredentialEnvelope.decode("not base64!:also not"))
    }

    @Test
    fun `more than two parts is not an envelope`() {
        assertNull(CredentialEnvelope.decode("one:two:three"))
    }
}

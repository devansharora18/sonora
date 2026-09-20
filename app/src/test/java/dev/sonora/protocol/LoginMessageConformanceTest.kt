package dev.sonora.protocol

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Conformance vector taken verbatim from the Nicotine+ SLSKPROTOCOL reference
 * (Server Code 1, "Message as Hex Stream").
 *
 * Round-trip tests only prove the reader and writer agree with each other. This proves
 * they agree with the actual protocol, which is the thing that has to be right.
 */
class LoginMessageConformanceTest {

    @Test
    fun `login frame matches the reference hex stream byte for byte`() {
        val username = "username"
        val password = "password"

        val body = MessageWriter()
            .writeString(username)
            .writeString(password)
            .writeUInt32(177) // major version
            .writeString(md5Hex(username + password))
            .writeUInt32(1) // minor version
            .toByteArray()

        val frame = Framing.SERVER.encode(code = 1, body = body)

        val expected = hex(
            "48 00 00 00 01 00 00 00 08 00 00 00 75 73 65 72 6e 61 6d 65 08 00 00 00 " +
                "70 61 73 73 77 6f 72 64 b1 00 00 00 20 00 00 00 64 35 31 63 39 61 37 65 " +
                "39 33 35 33 37 34 36 61 36 30 32 30 66 39 36 30 32 64 34 35 32 39 32 39 " +
                "01 00 00 00",
        )

        assertArrayEquals(expected, frame)
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hex(value: String): ByteArray =
        value.split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()
}

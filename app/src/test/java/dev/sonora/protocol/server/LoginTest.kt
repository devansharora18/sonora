package dev.sonora.protocol.server

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginTest {

    @Test
    fun `request body carries credentials, versions and the md5 hash`() {
        val body = Login.request("username", "password", majorVersion = 177, minorVersion = 1)

        val reader = MessageReader(body)

        assertEquals("username", reader.readString())
        assertEquals("password", reader.readString())
        assertEquals(177L, reader.readUInt32())
        assertEquals("d51c9a7e9353746a6020f9602d452929", reader.readString())
        assertEquals(1L, reader.readUInt32())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `parses a successful response`() {
        val body = MessageWriter()
            .writeBool(true)
            .writeString("Welcome to Soulseek!")
            .writeUInt32(0xC0A8_0101L) // 192.168.1.1
            .writeString("5f4dcc3b5aa765d61d8327deb882cf99") // md5("password")
            .writeBool(true)
            .toByteArray()

        val response = Login.parse(body)

        assertTrue(response is LoginResponse.Success)
        response as LoginResponse.Success
        assertEquals("Welcome to Soulseek!", response.greeting)
        assertEquals(0xC0A8_0101L, response.ownIp)
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", response.passwordHash)
        assertTrue(response.isSupporter)
    }

    @Test
    fun `parses a rejection without a detail`() {
        val body = MessageWriter().writeBool(false).writeString("INVALIDPASS").toByteArray()

        assertEquals(LoginResponse.Rejected("INVALIDPASS", null), Login.parse(body))
    }

    @Test
    fun `parses an invalid-username rejection with its detail`() {
        val body = MessageWriter()
            .writeBool(false)
            .writeString("INVALIDUSERNAME")
            .writeString("Nick too long.")
            .toByteArray()

        assertEquals(
            LoginResponse.Rejected("INVALIDUSERNAME", "Nick too long."),
            Login.parse(body),
        )
    }

    @Test
    fun `parses an invalid-username rejection with a missing detail`() {
        val body = MessageWriter().writeBool(false).writeString("INVALIDUSERNAME").toByteArray()

        assertEquals(LoginResponse.Rejected("INVALIDUSERNAME", null), Login.parse(body))
    }
}

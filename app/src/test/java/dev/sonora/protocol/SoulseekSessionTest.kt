package dev.sonora.protocol

import dev.sonora.protocol.server.FileSearch
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.SetStatus
import dev.sonora.protocol.server.SetWaitPort
import dev.sonora.protocol.server.SharedFoldersFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [SoulseekSession] against [FakeSoulseekServer], so it is covered without credentials,
 * a network, or the live spike.
 */
class SoulseekSessionTest {

    @Test
    fun `connect logs in and performs the session handshake`() {
        FakeSoulseekServer().use { server ->
            session(server).use { session ->
                val response = session.connect()

                assertEquals(FakeSoulseekServer.DEFAULT_RESPONSE, response)
                assertTrue(session.isConnected)

                // The three session messages must follow login, in this order.
                assertEquals(SetWaitPort.CODE, server.next().code)
                assertEquals(SetStatus.CODE, server.next().code)
                assertEquals(SharedFoldersFiles.CODE, server.next().code)
            }
        }
    }

    @Test
    fun `connect sends the supplied credentials`() {
        FakeSoulseekServer().use { server ->
            session(server).use { it.connect() }

            val reader = MessageReader(checkNotNull(server.loginBody))
            assertEquals("test_user", reader.readString())
            assertEquals("test_password", reader.readString())
        }
    }

    @Test
    fun `a rejected login is returned rather than thrown`() {
        val rejection = LoginResponse.Rejected("INVALIDPASS", null)

        FakeSoulseekServer(loginResponse = rejection).use { server ->
            session(server).use { session ->
                assertEquals(rejection, session.connect())
                assertFalse(session.isConnected)
            }
        }
    }

    @Test
    fun `an invalid-username rejection carries its detail through`() {
        val rejection = LoginResponse.Rejected("INVALIDUSERNAME", "Nick too long.")

        FakeSoulseekServer(loginResponse = rejection).use { server ->
            session(server).use { session ->
                assertEquals(rejection, session.connect())
            }
        }
    }

    @Test
    fun `search sends the query tagged with the token it returns`() {
        FakeSoulseekServer().use { server ->
            session(server).use { session ->
                session.connect()
                val token = session.search("aphex twin") { }

                val search = server.await(FileSearch.CODE)
                val reader = MessageReader(search.body)

                assertEquals(token, reader.readUInt32())
                assertEquals("aphex twin", reader.readString())
            }
        }
    }

    private fun session(server: FakeSoulseekServer) = SoulseekSession(
        username = "test_user",
        password = "test_password",
        host = "127.0.0.1",
        port = server.port,
        // Ephemeral: the peer listener must not collide with anything on the test machine.
        listenPort = 0,
    )
}

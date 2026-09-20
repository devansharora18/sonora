package dev.sonora.protocol.server

import dev.sonora.protocol.MessageReader
import org.junit.Assert.assertEquals
import org.junit.Test

class FileSearchTest {

    @Test
    fun `request carries the token then the query`() {
        val body = FileSearch.request(token = 0xDEAD_BEEFL, query = "aphex twin")

        val reader = MessageReader(body)
        assertEquals(0xDEAD_BEEFL, reader.readUInt32())
        assertEquals("aphex twin", reader.readString())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `empty query is still well formed`() {
        val body = FileSearch.request(token = 1, query = "")

        val reader = MessageReader(body)
        assertEquals(1L, reader.readUInt32())
        assertEquals("", reader.readString())
    }
}

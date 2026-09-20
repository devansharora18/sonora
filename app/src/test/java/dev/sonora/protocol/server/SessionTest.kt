package dev.sonora.protocol.server

import dev.sonora.protocol.MessageReader
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTest {

    @Test
    fun `set wait port carries the port`() {
        val body = SetWaitPort.request(2234)

        assertEquals(2234L, MessageReader(body).readUInt32())
        assertEquals(4, body.size)
    }

    @Test
    fun `set status carries the status`() {
        val body = SetStatus.request(SetStatus.ONLINE)

        assertEquals(2L, MessageReader(body).readUInt32())
        assertEquals(4, body.size)
    }

    @Test
    fun `shared folders files carries both counts`() {
        val body = SharedFoldersFiles.request(directories = 12, files = 3456)

        val reader = MessageReader(body)
        assertEquals(12L, reader.readUInt32())
        assertEquals(3456L, reader.readUInt32())
        assertEquals(0, reader.remaining)
    }
}

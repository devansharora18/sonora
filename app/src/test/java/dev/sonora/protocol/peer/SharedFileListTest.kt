package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.Zlib
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedFileListTest {

    @Test
    fun `encodes folders and files in the browse format`() {
        val body = SharedFileListResponse.encode(
            listOf(
                SharedFolder(
                    path = "Soulseek",
                    files = listOf(
                        SharedFile("a.flac", 1234, FileAttributes()),
                        SharedFile("b.mp3", 99, FileAttributes(bitrateKbps = 320)),
                    ),
                ),
            ),
        )

        val reader = MessageReader(Zlib.decompress(body))

        assertEquals(1L, reader.readUInt32())
        assertEquals("Soulseek", reader.readString())
        assertEquals(2L, reader.readUInt32())

        // First entry: no attributes, so no attribute block follows the count.
        assertEquals(1, reader.readByte())
        assertEquals("a.flac", reader.readString())
        assertEquals(1234L, reader.readUInt64())
        assertEquals(0L, reader.readUInt32())
        assertEquals(0L, reader.readUInt32())

        // Second entry carries a bitrate.
        assertEquals(1, reader.readByte())
        assertEquals("b.mp3", reader.readString())
        assertEquals(99L, reader.readUInt64())
        assertEquals(0L, reader.readUInt32())
        assertEquals(1L, reader.readUInt32())
        assertEquals(FileAttributes.BITRATE, reader.readUInt32())
        assertEquals(320L, reader.readUInt32())

        // Trailing field official clients always send as zero.
        assertEquals(0L, reader.readUInt32())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `an empty share still encodes a well-formed message`() {
        val reader = MessageReader(Zlib.decompress(SharedFileListResponse.encode(emptyList())))

        assertEquals(0L, reader.readUInt32())
        assertEquals(0L, reader.readUInt32())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `a file name is written as a name, not a path`() {
        // The distinction this pins down: a search response packs full virtual paths, a browse
        // response packs names with the folder carried separately. Getting it backwards would
        // make every file look like it sits at the root.
        val body = SharedFileListResponse.encode(
            listOf(SharedFolder("Soulseek\\Albums", listOf(SharedFile("song.flac", 10, FileAttributes())))),
        )

        val reader = MessageReader(Zlib.decompress(body))
        reader.readUInt32()
        assertEquals("Soulseek\\Albums", reader.readString())
        reader.readUInt32()

        reader.readByte()
        assertEquals("song.flac", reader.readString())
    }
}

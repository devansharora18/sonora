package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.Zlib

/**
 * Peer code 9. A peer sends this over a `P` connection when it has a file search match,
 * echoing the token from the originating [dev.sonora.protocol.server.FileSearch].
 *
 * The whole body is zlib-compressed, so it must be inflated before parsing.
 */
object FileSearchResponse {

    const val CODE = 9L

    /** Sanity bound so a corrupt count cannot drive a huge allocation. */
    private const val MAX_FILES = 100_000L

    fun parse(body: ByteArray): SearchResponse {
        val reader = MessageReader(Zlib.decompress(body))

        val username = reader.readString()
        val token = reader.readUInt32()
        val files = reader.readFileList()

        val hasFreeUploadSlot = reader.readBool()
        val averageSpeed = reader.readUInt32()
        val queueLength = reader.readUInt32()

        // Trailing fields are absent in some messages; only read them if they are present.
        if (reader.remaining >= 4) {
            reader.readUInt32() // unknown, always 0
        }
        val privateFiles = if (reader.remaining >= 4) reader.readFileList() else emptyList()

        return SearchResponse(
            username = username,
            token = token,
            files = files,
            hasFreeUploadSlot = hasFreeUploadSlot,
            averageSpeed = averageSpeed,
            queueLength = queueLength,
            privateFiles = privateFiles,
        )
    }

    private fun MessageReader.readFileList(): List<SharedFile> {
        val count = readUInt32()
        require(count <= MAX_FILES) { "implausible file count in search response: $count" }

        // Built incrementally rather than pre-sized, so a bogus count fails on the first
        // missing byte instead of allocating up front.
        val files = ArrayList<SharedFile>()
        repeat(count.toInt()) { files.add(readSharedFile()) }
        return files
    }
}

/** A parsed [FileSearchResponse]. */
data class SearchResponse(
    /** The peer that answered. */
    val username: String,
    /** Echoes the token from the originating search, so results can be matched to a query. */
    val token: Long,
    val files: List<SharedFile>,
    val hasFreeUploadSlot: Boolean,
    val averageSpeed: Long,
    val queueLength: Long,
    val privateFiles: List<SharedFile>,
)

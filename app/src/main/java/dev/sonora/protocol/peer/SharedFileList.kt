package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageWriter
import dev.sonora.protocol.Zlib

/** One folder in a shared-file list, with the files directly inside it. */
data class SharedFolder(val path: String, val files: List<SharedFile>)

/** Peer code 4. A peer asking what we share. Body is empty. */
object SharedFileListRequest {
    const val CODE = 4L
}

/**
 * Peer code 5. The reply to [SharedFileListRequest] — what another user sees when they browse us.
 *
 * The prose reference does not describe this payload, so the format is taken from Nicotine+'s
 * `SharedFileListResponse`: zlib-compressed (level 4), holding a folder count, then per folder
 * its path, a file count and the entries, then a trailing uint32 that official clients always
 * send as zero. Private shares would follow that and never do here.
 *
 * Each entry carries the file's **name**, not its full path — the folder is given separately.
 * That is the opposite of a search response, which packs full virtual paths, and Nicotine+'s
 * shares module is what settles it: the browse stream is built from a copy of the file record
 * with the path field overwritten by the basename.
 */
object SharedFileListResponse {

    const val CODE = 5L

    fun encode(folders: List<SharedFolder>): ByteArray {
        val writer = MessageWriter().writeUInt32(folders.size.toLong())

        for (folder in folders) {
            writer.writeString(folder.path)
            writer.writeUInt32(folder.files.size.toLong())

            for (file in folder.files) {
                writer.writeEntry(file)
            }
        }

        // Unknown purpose; official clients always send zero.
        writer.writeUInt32(0L)

        return Zlib.compress(writer.toByteArray())
    }

    private fun MessageWriter.writeEntry(file: SharedFile): MessageWriter {
        writeByte(1) // Entry code, always 1.
        writeString(file.filename)
        writeUInt64(file.size)

        // Obsolete extension field: length-prefixed, and sent empty by every current client.
        writeUInt32(0L)

        val attributes = file.attributes
        val present = buildList {
            attributes.bitrateKbps?.let { add(FileAttributes.BITRATE to it) }
            attributes.durationSeconds?.let { add(FileAttributes.DURATION to it) }
            attributes.sampleRateHz?.let { add(FileAttributes.SAMPLE_RATE to it) }
            attributes.bitDepth?.let { add(FileAttributes.BIT_DEPTH to it) }
        }

        writeUInt32(present.size.toLong())
        for ((code, value) in present) {
            writeUInt32(code)
            writeUInt32(value)
        }

        return this
    }
}

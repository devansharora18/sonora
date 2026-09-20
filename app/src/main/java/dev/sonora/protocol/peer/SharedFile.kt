package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader

/**
 * One file offered by a peer. Shared by every message that carries a file list — search
 * responses and shared-file-list responses.
 */
data class SharedFile(
    val filename: String,
    val size: Long,
    val attributes: FileAttributes,
)

/**
 * Attributes attached to a [SharedFile]. Which are present depends on the file format and
 * the sending client, so all are optional.
 */
data class FileAttributes(
    val bitrateKbps: Long? = null,
    val durationSeconds: Long? = null,
    val vbr: Boolean? = null,
    val sampleRateHz: Long? = null,
    val bitDepth: Long? = null,
) {
    companion object {
        const val BITRATE = 0L
        const val DURATION = 1L
        const val VBR = 2L
        const val SAMPLE_RATE = 4L
        const val BIT_DEPTH = 5L

        internal fun fromRaw(raw: Map<Long, Long>): FileAttributes = FileAttributes(
            bitrateKbps = raw[BITRATE],
            durationSeconds = raw[DURATION],
            vbr = raw[VBR]?.let { it != 0L },
            sampleRateHz = raw[SAMPLE_RATE],
            bitDepth = raw[BIT_DEPTH],
        )
    }
}

/** Reads one packed file entry — the shape shared by every file list in the protocol. */
internal fun MessageReader.readSharedFile(): SharedFile {
    val code = readByte()
    require(code == 1) { "unsupported file entry code $code" }

    val filename = readString()
    val size = readFileSize()

    // A uint32 length followed by the extension bytes. Long obsolete; skipped on parse and
    // sent as empty, matching Nicotine+.
    skip(readUInt32().toInt())

    val attributeCount = readUInt32()
    val raw = HashMap<Long, Long>(attributeCount.toInt().coerceIn(0, 16))
    repeat(attributeCount.toInt()) {
        val attributeCode = readUInt32()
        raw[attributeCode] = readUInt32()
    }

    return SharedFile(
        // Paths use backslash separators on the wire; normalised the same way other clients do.
        filename = filename.replace('/', '\\'),
        size = size,
        attributes = FileAttributes.fromRaw(raw),
    )
}

/**
 * Reads a uint64 file size, working around a Soulseek NS bug.
 *
 * For files over 2 GiB that client fills the top four bytes with `0xFFFFFFFF` instead of
 * zeros, which unpacks as a nonsense size around 16 EiB. The size is really a uint32 in that
 * case. A genuine size that large is impossible, so the high byte is a safe discriminator.
 */
internal fun MessageReader.readFileSize(): Long =
    if (remaining >= 8 && byteAt(7) == 0xFF) {
        val size = readUInt32()
        skip(4)
        size
    } else {
        readUInt64()
    }

package dev.sonora.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/**
 * Some peer messages deflate their entire body with zlib (RFC 1950) — `FileSearchResponse`
 * and `SharedFileListResponse` among them. Everything after the message code is compressed,
 * so the body must be inflated before parsing.
 *
 * Verified against Nicotine+'s `slskmessages.py`, which compresses the whole packed body
 * with level 4.
 */
object Zlib {

    /**
     * Upper bound on decompressed output. The compressed body arrives from an untrusted peer,
     * so unbounded inflation is a trivial memory-exhaustion vector. Matches Nicotine+'s limit.
     */
    const val MAX_DECOMPRESSED_BYTES = 128 * 1024 * 1024

    fun decompress(bytes: ByteArray, maxBytes: Int = MAX_DECOMPRESSED_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)

        InflaterInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                require(out.size() <= maxBytes) {
                    "decompressed message exceeds $maxBytes bytes"
                }
            }
        }

        return out.toByteArray()
    }
}

package dev.sonora.backend

import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Tags read from a file. Any field may be absent, which is common for peer-shared rips. */
data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/**
 * Reads embedded tags, with a cache.
 *
 * Tag reading goes through a native extractor, and the library rescans whenever it is opened, so
 * without a cache the same files would be re-read on every visit. The key includes size and
 * modification time, so a replaced file is re-read rather than serving stale tags.
 */
internal object TagReader {

    private val cache = ConcurrentHashMap<String, TrackMetadata>()

    fun read(file: File): TrackMetadata {
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        return cache.getOrPut(key) { extract(file) }
    }

    private fun extract(file: File): TrackMetadata {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(file.absolutePath)
            TrackMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            )
        } catch (_: Exception) {
            // A file that cannot be read simply falls back to its filename.
            TrackMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * Resolves technical audio quality information (format, bitrate, bit depth, sample rate)
 * from an audio file, with caching.
 */
object AudioQuality {

    private val LOSSLESS_EXTENSIONS = setOf("flac", "wav", "alac", "aif", "aiff", "ape", "wv")
    private val cache = ConcurrentHashMap<String, String>()

    fun from(file: File, durationMs: Long = 0L): String {
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        return cache.getOrPut(key) { resolve(file, durationMs) }
    }

    private fun resolve(file: File, durationMs: Long): String {
        val ext = file.extension.lowercase()
        val fileType = when (ext) {
            "flac" -> "FLAC"
            "wav" -> "WAV"
            "mp3" -> "MP3"
            "m4a", "aac" -> "AAC"
            "ogg" -> "OGG"
            "opus" -> "OPUS"
            "wma" -> "WMA"
            "alac" -> "ALAC"
            "aiff", "aif" -> "AIFF"
            else -> file.extension.uppercase().ifBlank { "AUDIO" }
        }

        val isLossless = ext in LOSSLESS_EXTENSIONS

        return if (isLossless) {
            val (bitDepth, sampleRate) = readLosslessDetails(file)
            val parts = buildList {
                add(fileType)
                if (bitDepth != null && bitDepth > 0) add("${bitDepth}-bit")
                if (sampleRate != null && sampleRate > 0) add(formatSampleRate(sampleRate))
            }
            parts.joinToString("  \u00b7  ")
        } else {
            val bitrate = readBitrateKbps(file, durationMs)
            val parts = buildList {
                add(fileType)
                if (bitrate != null && bitrate > 0) add("${bitrate} kbps")
            }
            parts.joinToString("  \u00b7  ")
        }
    }

    internal fun formatSampleRate(sampleRateHz: Int): String {
        val khz = sampleRateHz / 1000.0
        return if (khz % 1.0 == 0.0) {
            "${khz.toInt()} kHz"
        } else {
            "%.1f kHz".format(khz)
        }
    }

    private fun readLosslessDetails(file: File): Pair<Int?, Int?> {
        val ext = file.extension.lowercase()
        if (ext == "flac") {
            val flacHeader = readFlacHeader(file)
            if (flacHeader.first != null || flacHeader.second != null) {
                return flacHeader
            }
        } else if (ext == "wav") {
            val wavHeader = readWavHeader(file)
            if (wavHeader.first != null || wavHeader.second != null) {
                return wavHeader
            }
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            val bitDepth = if (android.os.Build.VERSION.SDK_INT >= 31) {
                retriever.extractMetadata(39)?.toIntOrNull()
            } else null
            Pair(bitDepth, sampleRate)
        } catch (_: Exception) {
            Pair(null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    internal fun readFlacHeader(file: File): Pair<Int?, Int?> {
        return runCatching {
            file.inputStream().use { stream ->
                val header = ByteArray(26)
                val read = stream.read(header)
                if (read >= 22 && header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()
                ) {
                    val b18 = header[18].toInt() and 0xFF
                    val b19 = header[19].toInt() and 0xFF
                    val b20 = header[20].toInt() and 0xFF
                    val b21 = header[21].toInt() and 0xFF

                    val sampleRate = (b18 shl 12) or (b19 shl 4) or (b20 ushr 4)
                    val bitDepth = (((b20 and 0x01) shl 4) or ((b21 and 0xF0) ushr 4)) + 1
                    Pair(bitDepth.takeIf { it in 4..32 }, sampleRate.takeIf { it in 8000..384000 })
                } else null
            }
        }.getOrNull() ?: Pair(null, null)
    }

    internal fun readWavHeader(file: File): Pair<Int?, Int?> {
        return runCatching {
            file.inputStream().use { stream ->
                val header = ByteArray(44)
                val read = stream.read(header)
                if (read >= 36 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte() &&
                    header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte()
                ) {
                    if (header[12] == 'f'.code.toByte() && header[13] == 'm'.code.toByte() &&
                        header[14] == 't'.code.toByte() && header[15] == ' '.code.toByte()
                    ) {
                        val sampleRate = (header[24].toInt() and 0xFF) or
                                ((header[25].toInt() and 0xFF) shl 8) or
                                ((header[26].toInt() and 0xFF) shl 16) or
                                ((header[27].toInt() and 0xFF) shl 24)
                        val bitDepth = (header[34].toInt() and 0xFF) or
                                ((header[35].toInt() and 0xFF) shl 8)
                        Pair(bitDepth.takeIf { it in 4..32 }, sampleRate.takeIf { it in 8000..384000 })
                    } else null
                } else null
            }
        }.getOrNull() ?: Pair(null, null)
    }

    private fun readBitrateKbps(file: File, durationMs: Long): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val bps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            if (bps != null && bps > 0) {
                (bps / 1000).toInt()
            } else {
                val dur = if (durationMs > 0) durationMs else {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
                if (dur > 0) {
                    ((file.length() * 8) / (dur / 1000.0) / 1000.0).toInt()
                } else null
            }
        } catch (_: Exception) {
            if (durationMs > 0) {
                ((file.length() * 8) / (durationMs / 1000.0) / 1000.0).toInt()
            } else null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

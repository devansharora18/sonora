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

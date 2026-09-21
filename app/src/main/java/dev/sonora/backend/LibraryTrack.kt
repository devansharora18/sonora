package dev.sonora.backend

import java.io.File

/**
 * A downloaded file in the local library.
 *
 * Tags come first and the filename is only a fallback — peer-shared rips often carry no tags, so
 * both paths have to work.
 */
data class LibraryTrack(
    val file: File,
    val title: String,
    val artist: String?,
    val album: String?,
    val size: Long,
) {
    companion object {

        /** Leading track numbers: `07. `, `07 - `, `07_`, `1-04 `. */
        private val LEADING_TRACK_NUMBER = Regex("^\\d{1,3}\\s*[-._)]\\s*")

        fun from(file: File, metadata: TrackMetadata = TrackMetadata()): LibraryTrack {
            val size = file.length()
            val fallback = fromFilename(file)

            val title = metadata.title?.takeIf { it.isNotBlank() } ?: fallback.first
            val artist = metadata.artist?.takeIf { it.isNotBlank() } ?: fallback.second

            return LibraryTrack(
                file = file,
                title = title,
                artist = artist,
                album = metadata.album?.takeIf { it.isNotBlank() },
                size = size,
            )
        }

        /** Best guess from the name: `Artist - Title`, with any track number stripped. */
        private fun fromFilename(file: File): Pair<String, String?> {
            val stem = file.nameWithoutExtension
            val cleaned = stem.replace(LEADING_TRACK_NUMBER, "").trim()
            val parts = cleaned.split(" - ", limit = 2)

            return if (parts.size == 2) {
                parts[1].trim() to parts[0].trim()
            } else {
                cleaned to null
            }
        }
    }
}

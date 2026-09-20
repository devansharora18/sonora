package dev.sonora.backend

import java.io.File

/**
 * A downloaded file in the local library.
 *
 * Artist and title are derived from the filename for now. Peers name files however they like, and
 * reading real tags needs a tag parser — worth doing, but a separate decision from showing the
 * library at all.
 */
data class LibraryTrack(
    val file: File,
    val artist: String?,
    val title: String,
    val size: Long,
) {
    companion object {

        /** Leading track numbers: `07. `, `07 - `, `07_`, `1-04 `. */
        private val LEADING_TRACK_NUMBER = Regex("^\\d{1,3}\\s*[-._)]\\s*")

        fun from(file: File): LibraryTrack {
            val stem = file.nameWithoutExtension
            val cleaned = stem.replace(LEADING_TRACK_NUMBER, "").trim()

            // "Artist - Title" is the common case.
            val parts = cleaned.split(" - ", limit = 2)

            return if (parts.size == 2) {
                LibraryTrack(file, parts[0].trim(), parts[1].trim(), file.length())
            } else {
                LibraryTrack(file, null, cleaned, file.length())
            }
        }
    }
}

package dev.sonora.metadata

/**
 * Turning a catalogue into a suggestion: what does this artist have that you do not?
 *
 * The comparison is deliberately loose. A tag and MusicBrainz rarely agree on punctuation or
 * case — `dont smile at me` against `Don't Smile at Me` — and treating those as different albums
 * would suggest things the user already owns, which is the one failure that makes this useless.
 */
object Discovery {

    /**
     * The releases in [releases] that are not among [owned] album titles.
     *
     * MusicBrainz order is preserved, which is chronological, so the list reads as a discography.
     */
    fun missingAlbums(owned: List<String>, releases: List<ReleaseGroup>): List<ReleaseGroup> {
        val known = owned.mapTo(HashSet()) { comparable(it) }

        return releases.filterNot { comparable(it.title) in known }
    }

    /** Letters and digits only, so punctuation and spacing cannot make two titles differ. */
    internal fun comparable(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }
}

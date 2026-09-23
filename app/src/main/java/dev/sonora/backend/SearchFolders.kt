package dev.sonora.backend

/**
 * Grouping search results by where the peer keeps them.
 *
 * A Soulseek result carries the uploader's whole virtual path, and a peer's folder is normally one
 * release — so "the same folder on the same peer" is the honest definition of an album here. It is
 * also the only one available: search results carry no album tag.
 */
object SearchFolders {

    /** The folder part of a result's virtual path, or empty when it sits at the peer's root. */
    fun folderOf(hit: SearchHit): String = hit.filename.substringBeforeLast('\\', "")

    /**
     * Every result from the same peer and folder as [hit], including it.
     *
     * Grouped by peer as well as folder because two peers can hold different releases under the
     * same folder name, and pulling half of each would be worse than pulling neither.
     */
    fun folderOf(hits: List<SearchHit>, hit: SearchHit): List<SearchHit> {
        val folder = folderOf(hit)
        return hits.filter { it.peer == hit.peer && folderOf(it) == folder }
    }
}

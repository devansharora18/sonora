package dev.sonora.backend

import dev.sonora.protocol.peer.FileAttributes

/** How results are ordered. */
enum class SortMode(val label: String) {
    RELEVANCE("Best match"),
    SPEED("Fastest"),
    AVAILABILITY("Free slot"),
    QUALITY("Best quality"),
    SIZE("Largest"),
}

/**
 * Where a result came from.
 *
 * The two are answered by different networks. MusicBrainz knows what exists and can describe an
 * album nobody on Soulseek is sharing; Soulseek has the files. Which one is worth looking at
 * depends entirely on what is being looked for, so the search screen can show either or both.
 */
enum class SearchSource(val label: String) {
    CATALOGUE("Catalogue"),
    SOULSEEK("Soulseek"),
}

/** One file offered by one peer, as shown in a results list. */
data class SearchHit(
    val peer: String,
    val filename: String,
    val size: Long,
    val attributes: FileAttributes,
    /** The peer's reported average upload speed, in bytes per second. Zero means unknown. */
    val averageSpeed: Long,
    /** True when the peer can start sending immediately instead of queueing us. */
    val hasFreeUploadSlot: Boolean,
    /** How many transfers are ahead of us on that peer. */
    val queueLength: Long,
)

/** Progress and contents of the current search. */
data class SearchState(
    val query: String = "",
    /** True while results are still streaming in. */
    val searching: Boolean = false,
    /**
     * Ordered by [sort]. Capped, because a broad query can match hundreds of thousands of files.
     */
    val hits: List<SearchHit> = emptyList(),
    /** Everything matched, including what [hits] dropped, so the UI can say "showing X of Y". */
    val matched: Int = 0,
    /** Distinct peers contributing results — the redundancy available for any one track. */
    val peers: Int = 0,
    val sort: SortMode = SortMode.RELEVANCE,
)

package dev.sonora.backend

import dev.sonora.protocol.peer.FileAttributes

/** One file offered by one peer, as shown in a results list. */
data class SearchHit(
    val peer: String,
    val filename: String,
    val size: Long,
    val attributes: FileAttributes,
)

/** Progress and contents of the current search. */
data class SearchState(
    val query: String = "",
    /** True while results are still streaming in. */
    val searching: Boolean = false,
    /** Capped: a broad query can match hundreds of thousands of files. */
    val hits: List<SearchHit> = emptyList(),
    /** Everything matched, including what [hits] dropped, so the UI can say "showing X of Y". */
    val matched: Int = 0,
)

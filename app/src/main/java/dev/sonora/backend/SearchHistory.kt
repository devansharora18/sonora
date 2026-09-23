package dev.sonora.backend

/**
 * The rules for the recent-search list.
 *
 * Pure, so the ordering and de-duplication are decided here and can be tested without a device.
 */
object SearchHistory {

    /** Long enough to be useful, short enough to stay a shortcut rather than a list to read. */
    const val MAX = 10

    /**
     * Records a query as the most recent, returning the new list.
     *
     * Case-insensitive de-duplication: searching "Ocean Eyes" after "ocean eyes" is the same
     * search, and keeping both would push real history out for no benefit.
     */
    fun record(history: List<String>, query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return history

        return (listOf(trimmed) + history.filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(MAX)
    }

    /** Forgets one query, matching it the same way [record] does. */
    fun remove(history: List<String>, query: String): List<String> =
        history.filterNot { it.equals(query, ignoreCase = true) }
}

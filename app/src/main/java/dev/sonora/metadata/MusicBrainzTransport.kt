package dev.sonora.metadata

import java.net.HttpURLConnection
import java.net.URL

/**
 * The real network fetch for MusicBrainz, kept apart from the caching logic so that logic can be
 * tested without a network.
 *
 * Two things here are not optional. MusicBrainz wants a descriptive `User-Agent` and blocks
 * anonymous ones, and it allows roughly one request per second — so requests are spaced rather
 * than fired, and a client that ignores that gets the whole app throttled.
 */
class MusicBrainzTransport(
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** Every real request, so "how few fetches" can be measured rather than assumed. */
    private val onTrace: (String) -> Unit = {},
) : (String) -> String? {

    @Volatile
    private var lastRequestAt = 0L

    override fun invoke(url: String): String? {
        spaceRequests()
        onTrace(url)

        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", MusicBrainz.USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }

            try {
                // A busy or unknown-artist response is not worth retrying here: the caller treats
                // null as "nothing to add", and the next lookup will ask again.
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** Blocks until a second has passed since the last request. */
    private fun spaceRequests() {
        val since = now() - lastRequestAt
        if (since < MIN_INTERVAL_MS) sleep(MIN_INTERVAL_MS - since)

        lastRequestAt = now()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000

        /** MusicBrainz asks for one request per second; this is that, with a little room. */
        const val MIN_INTERVAL_MS = 1_100L
    }
}

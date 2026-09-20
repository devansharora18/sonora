package dev.sonora.protocol

import dev.sonora.protocol.peer.SearchResponse
import dev.sonora.protocol.server.LoginResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The end-to-end spike: search the real network through the production [SoulseekSession].
 *
 * Exercises the whole chain — login, session messages, `FileSearch`, `ConnectToPeer`, the
 * outbound `PierceFireWall` fallback, and a zlib-compressed `FileSearchResponse`.
 *
 * Because this host has no inbound reachability (PRD D11), the **fallback** is the path that
 * matters here — and it is also the path every mobile user will take.
 *
 * Run with:
 * ```
 * SONORA_SOULSEEK_USER=... SONORA_SOULSEEK_PASS=... \
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveSearchSpike*' --info
 * ```
 *
 * Override the query with `SONORA_SEARCH_QUERY`.
 */
class LiveSearchSpike {

    @Test
    fun `searching the network returns results`() {
        val (username, password) = LiveServer.credentialsOrSkip()
        val query = LiveServer.query()

        val responses = LinkedBlockingQueue<SearchResponse>()

        SoulseekSession(
            username = username,
            password = password,
            onTrace = { println("[spike] $it") },
        ).use { session ->
            val login = session.connect()
            println("[spike] login: $login")
            assertTrue("login rejected: $login", login is LoginResponse.Success)

            session.search(query) { responses += it }
            println("[spike] search sent: query=\"$query\"")

            val first = responses.poll(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (first == null) {
                fail(
                    "no search response arrived — check the trace above to tell a network " +
                        "problem from a code problem",
                )
            }

            // Results stream in as peers answer rather than arriving in one batch, so let the
            // first tranche settle before counting — otherwise the numbers describe only the
            // single fastest peer.
            Thread.sleep(DRAIN_WINDOW_MS)

            val collected = mutableListOf(first as SearchResponse)
            responses.drainTo(collected)
            val files = collected.flatMap { it.files }

            println("[spike] ${collected.size} response(s), ${files.size} file(s)")
            files.take(5).forEach {
                println("[spike]   ${it.filename}  ${it.size} bytes  ${it.attributes}")
            }

            assertTrue("responses arrived but carried no files", files.isNotEmpty())
        }
    }

    private companion object {
        const val SEARCH_TIMEOUT_SECONDS = 60L
        const val DRAIN_WINDOW_MS = 5_000L
    }
}

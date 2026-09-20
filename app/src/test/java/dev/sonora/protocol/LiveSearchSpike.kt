package dev.sonora.protocol

import dev.sonora.protocol.peer.SharedFile
import dev.sonora.protocol.server.LoginResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

        // One query can return hundreds of thousands of files, so count them rather than
        // retaining them: what is under test is the protocol, not the results.
        val firstResponse = CountDownLatch(1)
        val responseCount = AtomicInteger()
        val fileCount = AtomicInteger()
        val sample = CopyOnWriteArrayList<SharedFile>()

        SoulseekSession(
            username = username,
            password = password,
            onTrace = { println("[spike] $it") },
        ).use { session ->
            val login = session.connect()
            println("[spike] login: $login")
            assertTrue("login rejected: $login", login is LoginResponse.Success)

            session.search(query) { response ->
                responseCount.incrementAndGet()
                fileCount.addAndGet(response.files.size)
                if (sample.size < SAMPLE_SIZE) {
                    sample.addAll(response.files.take(SAMPLE_SIZE - sample.size))
                }
                firstResponse.countDown()
            }
            println("[spike] search sent: query=\"$query\"")

            if (!firstResponse.await(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fail(
                    "no search response arrived — check the trace above to tell a network " +
                        "problem from a code problem",
                )
            }

            // Results stream in as peers answer rather than arriving in one batch, so let the
            // first tranche settle before counting — otherwise the numbers describe only the
            // fastest peers to respond.
            Thread.sleep(DRAIN_WINDOW_MS)

            println("[spike] ${responseCount.get()} response(s), ${fileCount.get()} file(s)")
            sample.forEach {
                println("[spike]   ${it.filename}  ${it.size} bytes  ${it.attributes}")
            }

            assertTrue("responses arrived but carried no files", fileCount.get() > 0)
        }
    }

    private companion object {
        const val SEARCH_TIMEOUT_SECONDS = 60L
        const val DRAIN_WINDOW_MS = 5_000L
        const val SAMPLE_SIZE = 5
    }
}

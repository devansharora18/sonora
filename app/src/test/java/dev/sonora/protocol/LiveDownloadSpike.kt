package dev.sonora.protocol

import dev.sonora.protocol.peer.SharedFile
import dev.sonora.protocol.server.LoginResponse
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The last live spike: fetch an actual file from an actual peer.
 *
 * Everything else is proven against the fake harness — which encodes *my* reading of the
 * protocol, including every place the prose spec turned out to be wrong. This is the only test
 * that checks that reading against reality.
 *
 * Deliberately picks small files: this runs against the real network and other people's upload
 * slots.
 *
 * Run with:
 * ```
 * SONORA_SOULSEEK_USER=... SONORA_SOULSEEK_PASS=... \
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveDownloadSpike*' --info
 * ```
 *
 * Override the query with `SONORA_SEARCH_QUERY`. If the spikes report as skipped, run
 * `./gradlew --stop` first — a reused daemon can lose the caller's environment.
 */
class LiveDownloadSpike {

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `downloads a file from the live network`() {
        val (username, password) = LiveServer.credentialsOrSkip()
        val query = LiveServer.query()

        val candidates = mutableListOf<Candidate>()
        val lock = Any()

        SoulseekSession(
            username = username,
            password = password,
            transferTimeoutMillis = ATTEMPT_TIMEOUT_MS,
            onTrace = { println("[spike] $it") },
        ).use { session ->
            val login = session.connect()
            println("[spike] login: $login")
            assertTrue("login rejected: $login", login is LoginResponse.Success)

            session.search(query) { response ->
                val found = response.files
                    .filter { it.size in MIN_BYTES..MAX_BYTES }
                    .map { Candidate(response.username, it, response.hasFreeUploadSlot) }

                synchronized(lock) { candidates.addAll(found) }
            }
            println("[spike] searching \"$query\" for files between $MIN_BYTES and $MAX_BYTES bytes")

            // Peers answer over several seconds, so gather a batch before choosing.
            Thread.sleep(CANDIDATE_WAIT_MS.toLong())

            // An uploader with no free slot will queue us, and the transfer may not start for
            // minutes or hours — so try those first, and only fall back to a queued peer.
            val ordered = synchronized(lock) { candidates.toList() }
                .sortedByDescending { it.hasFreeSlot }
            println(
                "[spike] ${ordered.size} candidate(s), " +
                    "${ordered.count { it.hasFreeSlot }} with a free upload slot",
            )

            var attempt = 0
            for (candidate in ordered) {
                if (attempt >= MAX_ATTEMPTS) break
                attempt++

                val destination = File.createTempFile("sonora-live-download", ".part")
                destination.deleteOnExit()

                println(
                    "[spike] attempt $attempt: ${candidate.peer} " +
                        "${candidate.file.filename} (${candidate.file.size} bytes, " +
                        "freeSlot=${candidate.hasFreeSlot})",
                )

                val outcome = session.download(
                    username = candidate.peer,
                    filename = candidate.file.filename,
                    destination = destination,
                    size = candidate.file.size,
                )

                println("[spike] attempt $attempt outcome: $outcome")

                if (outcome is DownloadOutcome.Completed) {
                    val onDisk = destination.length()
                    println("[spike] ${destination.absolutePath} is $onDisk bytes")

                    // The session already guarantees the byte count; this checks it against what
                    // the uploader advertised when we searched, which is the independent claim.
                    assertEquals(candidate.file.size, onDisk)
                    assertTrue("file is empty", onDisk > 0)
                    return
                }

                destination.delete()
            }

            fail("no download succeeded in $attempt attempts")
        }
    }

    private data class Candidate(
        val peer: String,
        val file: SharedFile,
        val hasFreeSlot: Boolean,
    )

    private companion object {
        /** Keep to small files: this hits real peers and real upload slots. */
        const val MIN_BYTES = 20L * 1024
        const val MAX_BYTES = 5L * 1024 * 1024

        const val MAX_ATTEMPTS = 3
        const val CANDIDATE_WAIT_MS = 8_000
        const val ATTEMPT_TIMEOUT_MS = 60_000L
        const val TEST_TIMEOUT_MS = 420_000L
    }
}

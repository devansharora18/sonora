package dev.sonora.backend.taste

import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TasteAutoplayTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val minute = 60_000L

    private fun engine(seed: Int = 7): TasteEngine = TasteEngine(
        TasteStore(File(folder.newFolder(), "taste.json"), debounceMs = 0),
        clock = { 0L },
        random = Random(seed),
    )

    private fun ref(title: String, artist: String = title) =
        TrackRef.of(title = title, artist = artist)

    private suspend fun TasteEngine.play(
        ref: TrackRef,
        at: Long,
        origin: Origin = Origin.USER,
        liked: Boolean = false,
    ) = recordPlay(
        ref,
        listenedMs = 100_000,
        durationMs = 100_000,
        origin = origin,
        liked = liked,
        now = at,
    )

    private suspend fun TasteEngine.pick(cur: TrackRef?, count: Int, radio: List<TrackRef> = emptyList()) =
        next(cur, count) { radio }

    @Test
    fun `a repeated transition ranks its target first`() = runBlocking {
        val engine = engine()
        val a = ref("A", "Artist A")
        val b = ref("B", "Artist B")

        var at = 0L
        repeat(10) {
            engine.play(a, at)
            engine.play(b, at + 1_000)
            at += 2_000
        }
        // Push A and B out of the recent-exclusion window, leaving them eligible.
        for (index in 1..41) {
            engine.play(ref("Filler $index", "F$index"), at)
            at += 1_000
        }

        val picked = engine.pick(a, 1)

        assertEquals(b.key, picked.first().key)
    }

    @Test
    fun `a batch never repeats a track or the current one`() = runBlocking {
        val engine = engine()
        val cur = ref("Cur", "CurArtist")
        val radio = (1..20).map { ref("Radio $it", "R$it") }

        engine.play(cur, at = 0)
        val picked = engine.pick(cur, 5, radio)

        assertEquals(5, picked.size)
        assertEquals(5, picked.map { it.key }.toSet().size)
        assertTrue(picked.none { it.key == cur.key })
    }

    @Test
    fun `the recent window is never served back`() = runBlocking {
        val engine = engine()
        var at = 0L
        val played = (1..45).map { ref("Played $it", "P$it") }
        for (track in played) {
            engine.play(track, at)
            at += 1_000
        }

        val picked = engine.next(played.last(), 5) { emptyList() }
        val recent = engine.snapshot().recent.takeLast(40).toSet()

        assertTrue(picked.all { it.key !in recent })
    }

    @Test
    fun `a cold start returns the radio`() = runBlocking {
        val engine = engine()
        val radio = (1..5).map { ref("Radio $it", "R$it") }

        val picked = engine.pick(cur = ref("Seed", "SeedArtist"), count = 3, radio = radio)

        assertEquals(radio.take(3).map { it.key }, picked.map { it.key })
    }

    @Test
    fun `the radio is never empty when the model is`() = runBlocking {
        val engine = engine()
        val radio = listOf(ref("Only", "R"))

        assertEquals(1, engine.pick(null, 3, radio).size)
    }

    @Test
    fun `a skipped target drops below a track that keeps its credit`() = runBlocking {
        val engine = engine(seed = 5)
        val a = ref("A", "Artist A")
        val good = ref("Good", "Artist B")
        val skipped = ref("Skipped", "Artist C")

        var at = 0L
        repeat(6) {
            engine.play(a, at)
            engine.play(good, at + 1_000)
            at += 3_000
        }
        repeat(6) {
            engine.play(a, at)
            engine.recordPlay(
                ref = skipped,
                listenedMs = 5_000,
                durationMs = 200_000,
                now = at + 1_000,
            )
            at += 3_000
        }

        val picked = engine.pick(a, 1)

        assertEquals(good.key, picked.first().key)
    }

    @Test
    fun `a same-artist streak yields to a different artist`() = runBlocking {
        // One batch from one seed is a coin flip — six candidates, four picks — so this asserts the
        // property across seeds. A uniform draw would put the other artist in about half the
        // batches; the repeat penalty is what has to make it most of them.
        val seeds = 1..12
        var included = 0

        for (seed in seeds) {
            val engine = engine(seed = seed)

            // Five tracks by one artist, all strongly liked, and one by another with no history.
            // Played more than a session apart, so no transition edges form: what is under test is
            // the repeat penalty, not the recorded chain Same1 -> Same2 -> … which would win on its
            // own and make the batch a replay of the fixture.
            val sameArtist = (1..5).map { ref("Same $it", "SameArtist") }
            val other = ref("Different", "OtherArtist")
            var at = 0L
            for (track in sameArtist) {
                engine.play(track, at, liked = true)
                at += TasteEngine.SESSION_GAP_MS + 1_000
            }
            engine.play(other, at, liked = true)

            val picked = engine.next(sameArtist.first(), 4) { listOf(other) }
            if (picked.any { it.artistKey == other.artistKey }) included++
        }

        assertTrue(
            "the other artist appeared in only $included of ${seeds.count()} batches",
            included >= 10,
        )
    }
}

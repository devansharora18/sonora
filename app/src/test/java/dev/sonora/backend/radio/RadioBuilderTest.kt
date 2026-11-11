package dev.sonora.backend.radio

import dev.sonora.backend.taste.TasteEngine
import dev.sonora.backend.taste.TasteStore
import dev.sonora.backend.taste.TrackRef
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RadioBuilderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun engine(): TasteEngine = TasteEngine(
        TasteStore(File(folder.newFolder(), "taste.json"), debounceMs = 0),
        clock = { 0L },
        random = Random(1),
    )

    private fun ref(title: String, artist: String, local: Boolean = false): TrackRef = TrackRef.of(
        title = title,
        artist = artist,
        ytmId = if (local) null else "v-$title",
        localPath = if (local) "/music/$title.flac" else null,
    )

    private fun setup(vararg names: String, variety: Float = 0.3f, familiar: Boolean = false) =
        names.fold(RadioSetup(variety = variety, familiarOnly = familiar)) { setup, name ->
            setup.withSeed(RadioSeed(name))
        }

    private fun builder(
        engine: TasteEngine,
        related: suspend (TrackRef) -> List<TrackRef> = { emptyList() },
        radio: suspend (RadioSeed) -> List<TrackRef>,
    ) = RadioBuilder(engine, radio, related)

    private fun mixedRadio(own: Int = 12, other: Int = 12): List<TrackRef> =
        List(own) { ref("own $it", "Billie Eilish") } + List(other) { ref("near $it", "Other Artist $it") }

    @Test
    fun `no picked artists builds nothing`() = runBlocking {
        val mix = builder(engine()) { emptyList() }
            .build(RadioSetup(), library = emptyList())

        assertTrue(mix.isEmpty())
    }

    @Test
    fun `the left of the slider is only the picked artists`() = runBlocking {
        val mix = builder(engine()) { mixedRadio() }
            .build(setup("Billie Eilish", variety = 0f), library = emptyList(), count = 10)

        assertTrue("expected only the picked artist, got ${mix.map { it.artist }}", mix.all { it.artist == "Billie Eilish" })
        assertEquals(10, mix.size)
    }

    @Test
    fun `the right of the slider is artists from outside the picks`() = runBlocking {
        val mix = builder(engine()) { mixedRadio() }
            .build(setup("Billie Eilish", variety = 1f), library = emptyList(), count = 10)

        assertEquals(10, mix.size)
        assertTrue("expected no picked artist, got ${mix.map { it.artist }}", mix.none { it.artist == "Billie Eilish" })
    }

    @Test
    fun `half way is half the picks and half the rest`() = runBlocking {
        val mix = builder(engine()) { mixedRadio(own = 30, other = 30) }
            .build(setup("Billie Eilish", variety = 0.5f), library = emptyList(), count = 20)

        val own = mix.count { it.artist == "Billie Eilish" }
        assertTrue("expected about ten picked-artist tracks, got $own of ${mix.size}", own in 8..12)
    }

    @Test
    fun `familiar only leaves out anything never played and never downloaded`() = runBlocking {
        val engine = engine()
        val played = ref("played", "Billie Eilish")
        engine.recordPlay(played, listenedMs = 200_000, durationMs = 200_000)

        val downloaded = ref("downloaded", "Other Artist", local = true)
        val unheard = ref("unheard", "Other Artist")

        val mix = builder(engine) { listOf(played, unheard, downloaded) }
            .build(setup("Billie Eilish", variety = 1f, familiar = true), library = emptyList(), count = 10)

        assertEquals(setOf(played.key, downloaded.key), mix.map { it.key }.toSet())
    }

    @Test
    fun `a seed whose radio fails does not empty the mix`() = runBlocking {
        val radio = List(8) { ref("song $it", "Khalid") }
        val mix = builder(engine()) { seed ->
            if (seed.name == "Broken") error("no answer") else radio
        }.build(setup("Broken", "Khalid", variety = 0f), library = emptyList(), count = 5)

        assertEquals(5, mix.size)
        assertTrue(mix.all { it.artist == "Khalid" })
    }

    @Test
    fun `a downloaded album by a picked artist joins the mix`() = runBlocking {
        val downloaded = ref("from disk", "Billie Eilish", local = true)

        val mix = builder(engine()) { emptyList() }
            .build(setup("Billie Eilish", variety = 0f), library = listOf(downloaded), count = 5)

        assertEquals(listOf(downloaded.key), mix.map { it.key })
    }

    @Test
    fun `a second hop is taken only when the first one is short`() = runBlocking {
        var hops = 0
        val radio = List(20) { ref("own $it", "Billie Eilish") }

        builder(engine(), related = { hops++; emptyList() }, radio = { radio })
            .build(setup("Billie Eilish", variety = 0f), library = emptyList(), count = 10)

        assertEquals(0, hops)

        builder(engine(), related = { hops++; emptyList() }, radio = { radio.take(2) })
            .build(setup("Billie Eilish", variety = 0f), library = emptyList(), count = 10)

        assertEquals(2, hops)
    }
}

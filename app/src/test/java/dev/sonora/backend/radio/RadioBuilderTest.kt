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

    private fun setup(vararg names: String, stop: Int = 1, familiar: Boolean = false) =
        names.fold(RadioSetup(stop = stop, familiarOnly = familiar)) { setup, name ->
            setup.withSeed(RadioSeed(name))
        }

    private fun builder(
        engine: TasteEngine,
        related: suspend (TrackRef) -> List<TrackRef> = { emptyList() },
        catalogue: suspend (RadioSeed) -> List<TrackRef> = { emptyList() },
        radio: suspend (RadioSeed) -> List<TrackRef>,
    ) = RadioBuilder(engine, radio, related, catalogue)

    private fun mixedRadio(own: Int = 12, other: Int = 12): List<TrackRef> =
        List(own) { ref("own $it", "Billie Eilish") } + List(other) { ref("near $it", "Other Artist $it") }

    private fun catalogue(size: Int) = List(size) { ref("catalogue $it", "Billie Eilish") }

    @Test
    fun `no picked artists builds nothing`() = runBlocking {
        val mix = builder(engine()) { emptyList() }
            .build(RadioSetup(), library = emptyList())

        assertTrue(mix.isEmpty())
    }

    @Test
    fun `the first stop is only the picked artists`() = runBlocking {
        val mix = builder(engine(), catalogue = { catalogue(30) }) { mixedRadio() }
            .build(setup("Billie Eilish", stop = 0), library = emptyList(), count = 20)

        assertEquals(20, mix.size)
        assertTrue("expected only the picked artist", mix.all { it.artist == "Billie Eilish" })
    }

    @Test
    fun `the second stop is eighty twenty`() = runBlocking {
        val mix = builder(engine(), catalogue = { catalogue(30) }) { mixedRadio(other = 40) }
            .build(setup("Billie Eilish", stop = 1), library = emptyList(), count = 20)

        val own = mix.count { it.artist == "Billie Eilish" }
        assertEquals("expected sixteen of twenty", 16, own)
    }

    @Test
    fun `the last stop is thirty seventy`() = runBlocking {
        val mix = builder(engine(), catalogue = { catalogue(40) }) { mixedRadio(own = 40, other = 40) }
            .build(setup("Billie Eilish", stop = 4), library = emptyList(), count = 20)

        val own = mix.count { it.artist == "Billie Eilish" }
        assertEquals("expected six of twenty", 6, own)
    }

    @Test
    fun `the artist's own catalogue is what fills a high share`() = runBlocking {
        // A radio answers with a crowd of neighbours and only a couple of the artist's own tracks;
        // without the catalogue a 100:0 mix would be two tracks long.
        val mix = builder(engine(), catalogue = { catalogue(40) }) { mixedRadio(own = 2, other = 20) }
            .build(setup("Billie Eilish", stop = 0), library = emptyList(), count = 30)

        assertEquals(30, mix.size)
        assertTrue(mix.all { it.artist == "Billie Eilish" })
    }

    @Test
    fun `the first stop is all picks even when the picks run out`() = runBlocking {
        // Five tracks' worth of catalogue and nothing else: the mix is five long, not twenty with
        // fifteen other artists filling it in. A stop is a promise about the whole queue.
        val mix = builder(engine(), catalogue = { catalogue(5) }) { mixedRadio(own = 0, other = 30) }
            .build(setup("Billie Eilish", stop = 0), library = emptyList(), count = 20)

        assertEquals(5, mix.size)
        assertTrue(mix.all { it.artist == "Billie Eilish" })
    }

    @Test
    fun `the neighbourhood running out is filled from the picks, not with strangers`() = runBlocking {
        val mix = builder(engine(), catalogue = { catalogue(30) }) { mixedRadio(own = 0, other = 2) }
            .build(setup("Billie Eilish", stop = 1), library = emptyList(), count = 20)

        assertEquals(20, mix.size)
        val own = mix.count { it.artist == "Billie Eilish" }
        assertTrue("expected at least sixteen picks, got $own", own >= 16)
    }

    @Test
    fun `a refill never offers a track the radio already queued`() = runBlocking {
        val first = builder(engine(), catalogue = { catalogue(20) }) { emptyList() }
            .build(setup("Billie Eilish", stop = 0), library = emptyList(), count = 10)

        val second = builder(engine(), catalogue = { catalogue(20) }) { emptyList() }
            .build(
                setup("Billie Eilish", stop = 0),
                library = emptyList(),
                count = 10,
                exclude = first.mapTo(HashSet()) { it.key },
            )

        assertEquals(10, second.size)
        assertTrue(first.map { it.key }.toSet().intersect(second.map { it.key }.toSet()).isEmpty())
    }

    @Test
    fun `a track credited to a collaborator is queued once`() = runBlocking {
        // An artist page lists collaborations credited to the other artist first, so the same
        // track arrives through the radio as an outsider and through the catalogue as a pick.
        val collab = ref("This Is What You Came For", "Calvin Harris")

        val mix = builder(
            engine(),
            catalogue = { listOf(collab) },
            radio = { listOf(collab, ref("Umbrella", "Rihanna")) },
        ).build(setup("Rihanna", stop = 1), library = emptyList(), count = 10)

        assertEquals(mix.distinctBy { it.key }.size, mix.size)
        assertEquals(1, mix.count { it.key == collab.key })
    }

    @Test
    fun `familiar only leaves out anything never played and never downloaded`() = runBlocking {
        val engine = engine()
        val played = ref("played", "Billie Eilish")
        engine.recordPlay(played, listenedMs = 200_000, durationMs = 200_000)

        val downloaded = ref("downloaded", "Other Artist", local = true)
        val unheard = ref("unheard", "Other Artist")

        val mix = builder(engine) { listOf(played, unheard, downloaded) }
            .build(setup("Billie Eilish", stop = 4, familiar = true), library = emptyList(), count = 10)

        assertEquals(setOf(played.key, downloaded.key), mix.map { it.key }.toSet())
    }

    @Test
    fun `a seed whose radio fails does not empty the mix`() = runBlocking {
        val radio = List(8) { ref("song $it", "Khalid") }
        val mix = builder(engine()) { seed ->
            if (seed.name == "Broken") error("no answer") else radio
        }.build(setup("Broken", "Khalid", stop = 0), library = emptyList(), count = 5)

        assertEquals(5, mix.size)
        assertTrue(mix.all { it.artist == "Khalid" })
    }

    @Test
    fun `a downloaded album by a picked artist joins the mix`() = runBlocking {
        val downloaded = ref("from disk", "Billie Eilish", local = true)

        val mix = builder(engine()) { emptyList() }
            .build(setup("Billie Eilish", stop = 0), library = listOf(downloaded), count = 5)

        assertEquals(listOf(downloaded.key), mix.map { it.key })
    }

    @Test
    fun `a second hop is taken only when the picks cannot fill their share`() = runBlocking {
        var hops = 0
        val radio = List(20) { ref("own $it", "Billie Eilish") }

        builder(engine(), related = { hops++; emptyList() }, radio = { radio })
            .build(setup("Billie Eilish", stop = 0), library = emptyList(), count = 10)

        assertEquals(0, hops)

        builder(engine(), related = { hops++; emptyList() }, radio = { radio.take(2) })
            .build(setup("Billie Eilish", stop = 0), library = emptyList(), count = 10)

        assertEquals(2, hops)
    }
}

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

    private fun setup(vararg names: String) =
        names.fold(RadioSetup()) { setup, name -> setup.withSeed(RadioSeed(name)) }

    private fun builder(
        engine: TasteEngine,
        related: suspend (TrackRef) -> List<TrackRef> = { emptyList() },
        radio: suspend (RadioSeed) -> List<TrackRef>,
    ) = RadioBuilder(engine, radio, related)

    @Test
    fun `no picked artists builds nothing`() = runBlocking {
        val mix = builder(engine()) { emptyList() }
            .build(RadioSetup(), RadioMode.BLENDED, library = emptyList())

        assertTrue(mix.isEmpty())
    }

    @Test
    fun `blended plays what the picked artist's radio returns`() = runBlocking {
        val radio = List(12) { ref("song $it", "Billie Eilish") }
        val mix = builder(engine()) { if (it.name == "Billie Eilish") radio else emptyList() }
            .build(setup("Billie Eilish"), RadioMode.BLENDED, library = emptyList(), count = 8)

        assertEquals(8, mix.size)
        assertTrue(mix.all { it.artist == "Billie Eilish" })
    }

    @Test
    fun `discover never returns a track already in the library`() = runBlocking {
        val radio = List(12) { ref("song $it", "Billie Eilish", local = it < 4) }
        val library = radio.take(4)

        val mix = builder(engine()) { radio }
            .build(setup("Billie Eilish"), RadioMode.DISCOVER, library = library, count = 6)

        val libraryKeys = library.map { it.key }.toSet()
        assertTrue(mix.none { it.key in libraryKeys })
    }

    @Test
    fun `discover never returns a track already played`() = runBlocking {
        val engine = engine()
        val radio = List(12) { ref("song $it", "Billie Eilish") }
        engine.recordPlay(radio.first(), listenedMs = 200_000, durationMs = 200_000)

        val mix = builder(engine) { radio }
            .build(setup("Billie Eilish"), RadioMode.DISCOVER, library = emptyList(), count = 6)

        assertTrue(mix.none { it.key == radio.first().key })
    }

    @Test
    fun `familiar only returns played and downloaded tracks and never a radio pick`() = runBlocking {
        val engine = engine()
        val played = ref("played", "Billie Eilish")
        engine.recordPlay(played, listenedMs = 200_000, durationMs = 200_000)

        val downloaded = ref("downloaded", "Billie Eilish", local = true)
        val unheard = ref("unheard", "Billie Eilish")

        val mix = builder(engine) { listOf(unheard) }
            .build(
                setup("Billie Eilish"),
                RadioMode.FAMILIAR,
                library = listOf(downloaded),
                count = 10,
            )

        assertEquals(setOf(played.key, downloaded.key), mix.map { it.key }.toSet())
    }

    @Test
    fun `a seed whose radio fails does not empty the mix`() = runBlocking {
        val radio = List(8) { ref("song $it", "Khalid") }
        val mix = builder(engine()) { seed ->
            if (seed.name == "Broken") error("no answer") else radio
        }.build(setup("Broken", "Khalid"), RadioMode.BLENDED, library = emptyList(), count = 5)

        assertEquals(5, mix.size)
        assertTrue(mix.all { it.artist == "Khalid" })
    }

    @Test
    fun `discover caps the picked artists to a third of the mix`() = runBlocking {
        val byPick = List(30) { ref("own $it", "Billie Eilish") }
        val byOthers = List(30) { ref("near $it", "Other Artist $it") }
        val mix = builder(engine()) { byPick + byOthers }
            .build(setup("Billie Eilish"), RadioMode.DISCOVER, library = emptyList(), count = 30)

        val selected = mix.count { it.artist == "Billie Eilish" }
        assertTrue("expected at most 10 picked-artist tracks, got $selected", selected <= 10)
        assertEquals(30, mix.size)
    }

    @Test
    fun `discover takes a second hop only when the first one is short`() = runBlocking {
        var hops = 0
        val radio = List(20) { ref("song $it", "Billie Eilish") }

        builder(engine(), radio = { radio }, related = { hops++; emptyList() })
            .build(setup("Billie Eilish"), RadioMode.DISCOVER, library = emptyList(), count = 10)

        assertEquals(0, hops)

        builder(engine(), radio = { radio.take(2) }, related = { hops++; emptyList() })
            .build(setup("Billie Eilish"), RadioMode.DISCOVER, library = emptyList(), count = 10)

        assertEquals(2, hops)
    }
}

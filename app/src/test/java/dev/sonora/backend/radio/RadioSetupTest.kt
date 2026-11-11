package dev.sonora.backend.radio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RadioSetupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun setup(vararg names: String) =
        names.fold(RadioSetup()) { setup, name -> setup.withSeed(RadioSeed(name)) }

    @Test
    fun `a seed already picked is not added twice`() {
        val once = setup("Billie Eilish")
        val twice = once.withSeed(RadioSeed("billie eilish"))

        assertEquals(1, twice.seeds.size)
    }

    @Test
    fun `a sixth seed is ignored`() {
        val full = setup("a", "b", "c", "d", "e")
        val overflow = full.withSeed(RadioSeed("f"))

        assertEquals(RadioSetup.MAX_SEEDS, overflow.seeds.size)
        assertTrue(overflow.seeds.none { it.name == "f" })
    }

    @Test
    fun `removing a seed folds the name the way picking it did`() {
        val remaining = setup("Billie Eilish", "Khalid").withoutSeed("BILLIE EILISH")

        assertEquals(listOf("Khalid"), remaining.seeds.map { it.name })
    }

    @Test
    fun `resolving a seed keeps its position and fills in the id`() {
        val resolved = setup("Khalid", "billie eilish").withResolved(
            RadioSeed(name = "Billie Eilish", browseId = "UC_x", artworkUrl = "https://art"),
        )

        assertEquals(listOf("Khalid", "Billie Eilish"), resolved.seeds.map { it.name })
        assertEquals("UC_x", resolved.seeds[1].browseId)
    }

    @Test
    fun `an empty name is not a seed`() {
        assertFalse(RadioSetup().withSeed(RadioSeed("  ")).isReady)
    }

    @Test
    fun `a saved radio reads back with its stop and familiar flag`() {
        val file = File(folder.root, "radio.json")
        val store = RadioStore(file)
        val radio = SavedRadio(
            id = "r1",
            name = "Billie Eilish, Khalid",
            seeds = setup("Billie Eilish", "Khalid").seeds,
            stop = 2,
            familiarOnly = true,
        )
        val state = RadioState(draft = setup("Khalid"), saved = listOf(radio))

        store.save(state)
        val read = store.load()

        assertEquals(1, read.saved.size)
        assertEquals(radio, read.saved.first())
        assertEquals(2, read.saved.first().stop)
        assertTrue(read.saved.first().familiarOnly)
        assertEquals(listOf("Khalid"), read.draft.seeds.map { it.name })
    }

    @Test
    fun `an unreadable document reads back as an empty state`() {
        val file = File(folder.root, "radio.json").apply { writeText("{not json") }

        val read = RadioStore(file).load()

        assertTrue(read.saved.isEmpty())
        assertTrue(read.draft.seeds.isEmpty())
    }

    @Test
    fun `a saved radio comes back into the draft with its own settings`() {
        val radio = SavedRadio(
            id = "r1",
            name = "Mix",
            seeds = listOf(RadioSeed("Khalid")),
            stop = 4,
            familiarOnly = true,
        )

        val draft = radio.toDraft()

        assertEquals(4, draft.stop)
        assertEquals(0.3f, draft.selectedShare, 0.0001f)
        assertTrue(draft.familiarOnly)
        assertEquals(listOf("Khalid"), draft.seeds.map { it.name })
    }

    @Test
    fun `the five stops are the five ratios`() {
        assertEquals(
            listOf(1.0f, 0.8f, 0.6f, 0.5f, 0.3f),
            RadioSetup.SHARES.toList(),
        )
        assertEquals(1.0f, RadioSetup(stop = 0).selectedShare, 0.0001f)
        assertEquals(0.3f, RadioSetup(stop = 4).selectedShare, 0.0001f)
    }

    @Test
    fun `a slider position snaps to the nearest stop`() {
        val setup = RadioSetup()

        assertEquals(0, setup.stopFor(0f))
        assertEquals(0, setup.stopFor(0.1f))
        assertEquals(1, setup.stopFor(0.3f))
        assertEquals(2, setup.stopFor(0.5f))
        assertEquals(4, setup.stopFor(1f))
    }

    @Test
    fun `a stop out of range is clamped rather than crashing`() {
        assertEquals(1.0f, RadioSetup(stop = -3).selectedShare, 0.0001f)
        assertEquals(0.3f, RadioSetup(stop = 99).selectedShare, 0.0001f)
    }

    @Test
    fun `saving the same artists twice keeps both radios`() {
        val a = SavedRadio(id = "a", name = "One", seeds = listOf(RadioSeed("Khalid")), stop = 0)
        val b = SavedRadio(id = "b", name = "Two", seeds = listOf(RadioSeed("Khalid")), stop = 4)

        val state = RadioState().withRadio(a).withRadio(b)

        assertEquals(listOf("b", "a"), state.saved.map { it.id }.sortedDescending())
    }
}

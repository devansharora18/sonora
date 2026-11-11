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
    fun `a stored setup reads back with its seeds and mode`() {
        val file = File(folder.root, "radio.json")
        val store = RadioStore(file)
        val original = setup("Billie Eilish", "Khalid").copy(mode = RadioMode.DISCOVER)

        store.save(original)
        val read = store.load()

        assertEquals(original.seeds, read.seeds)
        assertEquals(RadioMode.DISCOVER, read.mode)
    }

    @Test
    fun `an unreadable document reads back as an empty setup`() {
        val file = File(folder.root, "radio.json").apply { writeText("{not json") }

        val read = RadioStore(file).load()

        assertTrue(read.seeds.isEmpty())
        assertEquals(RadioMode.BLENDED, read.mode)
    }

    @Test
    fun `an unknown mode name falls back to the default`() {
        assertEquals(RadioMode.BLENDED, RadioMode.fromName("SOMETHING_NEW"))
    }
}

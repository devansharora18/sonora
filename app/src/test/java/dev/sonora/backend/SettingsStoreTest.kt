package dev.sonora.backend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a missing file loads the defaults`() {
        val store = SettingsStore(File(folder.root, "settings.json"))

        assertEquals(Settings(), store.load())
        assertEquals(true, store.load().includeDeviceMusic)
    }

    @Test
    fun `settings round-trip through the file`() {
        val file = File(folder.root, "settings.json")

        SettingsStore(file).save(Settings(includeDeviceMusic = false))

        assertEquals(Settings(includeDeviceMusic = false), SettingsStore(file).load())
    }

    @Test
    fun `an unreadable document loads the defaults rather than throwing`() {
        val file = File(folder.root, "settings.json")
        file.writeText("{ not json")

        assertEquals(Settings(), SettingsStore(file).load())
    }

    @Test
    fun `a document missing a newer field still loads`() {
        val file = File(folder.root, "settings.json")
        file.writeText("{}")

        assertEquals(Settings(), SettingsStore(file).load())
    }

    @Test
    fun `saving replaces the previous document`() {
        val file = File(folder.root, "settings.json")

        SettingsStore(file).save(Settings(includeDeviceMusic = false))
        SettingsStore(file).save(Settings(includeDeviceMusic = true))

        assertEquals(Settings(includeDeviceMusic = true), SettingsStore(file).load())
    }
}

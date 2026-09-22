package dev.sonora.backend

import java.io.File

/** Settings, stored as one JSON document. See [JsonFile]. */
class SettingsStore(file: File) {

    private val json = JsonFile(file, Settings.serializer())

    fun load(): Settings = json.read() ?: Settings()

    fun save(settings: Settings) = json.write(settings)
}

package dev.sonora.backend.radio

import dev.sonora.backend.JsonFile
import java.io.File

/** The picked radio artists, stored as one JSON document. See [JsonFile]. */
class RadioStore(file: File) {

    private val json = JsonFile(file, RadioSetup.serializer())

    fun load(): RadioSetup = json.read() ?: RadioSetup()

    fun save(setup: RadioSetup) = json.write(setup)
}

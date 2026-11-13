package dev.sonora.backend.radio

import dev.sonora.backend.JsonFile
import java.io.File

/** The radio draft and the saved radios, stored as one JSON document. See [JsonFile]. */
class RadioStore(file: File) {

    private val json = JsonFile(file, RadioState.serializer())

    fun load(): RadioState = json.read() ?: RadioState()

    fun save(state: RadioState) = json.write(state)
}

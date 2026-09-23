package dev.sonora.backend

import java.io.File
import kotlinx.serialization.builtins.ListSerializer

/** Recently played tracks, stored as one JSON document. See [JsonFile]. */
class PlayHistoryStore(file: File) {

    private val json = JsonFile(file, ListSerializer(PlayedTrack.serializer()))

    fun load(): List<PlayedTrack> = json.read().orEmpty()

    fun save(tracks: List<PlayedTrack>) = json.write(tracks)
}

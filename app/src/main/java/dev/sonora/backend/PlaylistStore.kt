package dev.sonora.backend

import java.io.File
import kotlinx.serialization.builtins.ListSerializer

/**
 * Playlists, stored as one JSON document.
 *
 * See [JsonFile] for why this is a file rather than a database.
 */
class PlaylistStore(file: File) {

    private val json = JsonFile(file, ListSerializer(Playlist.serializer()))

    fun load(): List<Playlist> = json.read().orEmpty()

    fun save(playlists: List<Playlist>) = json.write(playlists)
}

package dev.sonora.backend

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/**
 * Playlists, stored as a single JSON document.
 *
 * A file rather than a database: the whole set is small, only this process writes it, and it is
 * read once at startup, so a schema and a migration path would be more machinery than the data
 * justifies. If playlists ever need querying or partial updates, that is when a database earns its
 * place.
 *
 * Saving writes a sibling temp file and then replaces the original. Overwriting in place would
 * leave a truncated document if the process died mid-write, and a truncated document does not
 * parse — the playlists would be gone rather than merely out of date.
 */
class PlaylistStore(private val file: File) {

    private val json = Json { prettyPrint = true }

    /**
     * Reads the stored playlists, or none if there is nothing readable there.
     *
     * A document that fails to parse is treated as empty rather than fatal. The atomic write above
     * is what stops the app from damaging its own file; this covers the remaining case, where the
     * file was damaged from outside the app.
     */
    fun load(): List<Playlist> {
        if (!file.exists()) return emptyList()

        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString<List<Playlist>>(text) }.getOrDefault(emptyList())
    }

    fun save(playlists: List<Playlist>) {
        file.parentFile?.mkdirs()

        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(playlists))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

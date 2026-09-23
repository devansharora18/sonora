package dev.sonora.backend

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * A value kept as one JSON document.
 *
 * A file rather than a database: none of these are large, only this process writes them, and each
 * is read once, so a schema and a migration path would be more machinery than the data justifies.
 *
 * Writing goes through a sibling temp file and a rename. Overwriting in place would leave a
 * truncated document if the process died mid-write, and a truncated document does not parse — for
 * playlists that means the user's data is gone rather than merely stale.
 */
internal class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
) {

    private val json = Json { prettyPrint = true }

    /**
     * Reads the stored value, or null when there is nothing readable there.
     *
     * A document that fails to parse is treated as absent rather than fatal. The atomic write above
     * is what stops the app damaging its own file; this covers files damaged from outside.
     */
    fun read(): T? {
        if (!file.exists()) return null

        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(serializer, text) }.getOrNull()
    }

    fun write(value: T) {
        file.parentFile?.mkdirs()

        // A name unique to this write, not a fixed "<name>.tmp". Two writers — the same track
        // recorded twice, two tracks added at once — would otherwise share one temporary file: the
        // first renames it away and the second fails moving something that is no longer there.
        val temporary = File.createTempFile("${file.name}.", ".tmp", file.parentFile)

        try {
            temporary.writeText(json.encodeToString(serializer, value))

            // renameTo, not Files.move: both are a rename underneath, but move() checks and unlinks
            // an existing target first, which is a race two writers can lose. rename(2) replaces the
            // target atomically and never looks at it.
            temporary.renameTo(file)
        } finally {
            // Gone already if the rename succeeded; this covers a write that failed part-way.
            temporary.delete()
        }
    }
}

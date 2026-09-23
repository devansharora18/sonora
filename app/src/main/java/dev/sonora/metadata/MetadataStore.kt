package dev.sonora.metadata

import java.io.File
import java.security.MessageDigest

/**
 * Responses kept on disk, so a lookup that has already been made is not made again.
 *
 * MusicBrainz allows roughly one request per second and asks clients to cache rather than poll,
 * and the data changes slowly — an artist's discography is the same tomorrow. So the cache is
 * consulted before the network, always, and entries live for a long time.
 *
 * One file per entry rather than a single document: entries arrive independently, and rewriting
 * one large file on every write would make a cached read cost more than it saves.
 */
class MetadataStore(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** The stored body, or null when it is absent or older than [ttlMillis]. */
    fun read(key: String, ttlMillis: Long): String? {
        val file = fileFor(key)
        if (!file.exists()) return null

        val text = runCatching { file.readText() }.getOrNull() ?: return null

        // The fetch time is the first line, with the body after it.
        val newline = text.indexOf('\n')
        if (newline <= 0) return null

        val storedAt = text.substring(0, newline).toLongOrNull() ?: return null
        if (now() - storedAt > ttlMillis) return null

        return text.substring(newline + 1)
    }

    fun write(key: String, body: String) {
        directory.mkdirs()

        // Timed here rather than by the file's modification time: that is changed by copying,
        // restoring a backup, or anything else that touches the file, which would quietly make a
        // stale entry look fresh.
        runCatching { fileFor(key).writeText("${now()}\n$body") }
    }

    /**
     * The filename for a key.
     *
     * Hashed rather than used directly: a key is a URL, and URLs are neither valid filenames nor
     * short. The hash is stable, so the same request finds the same entry.
     */
    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }.take(32)

        return File(directory, "$name.json")
    }
}

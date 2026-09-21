package dev.sonora.backend

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where downloaded files live.
 *
 * Shared `Music/Soulseek` rather than app-private storage: these are the user's music files, and
 * putting them where every other player and file manager can see them is most of the point of
 * downloading them at all. App-private storage remains the fallback for when shared storage is not
 * writable, because a music app that silently stops downloading is worse than one saving somewhere
 * less convenient.
 *
 * API 30+ needs no permission to contribute to a media collection like `Music/`. Below that the
 * caller must hold `WRITE_EXTERNAL_STORAGE`, and [resolve] falls back until it does.
 */
object MusicDirectory {

    private const val FOLDER = "Soulseek"

    /** Where downloads went before they moved to shared storage. */
    private const val LEGACY_FOLDER = "downloads"

    data class Location(val directory: File, val shared: Boolean)

    /**
     * Resolved per call rather than cached: it depends on a permission that can be granted after
     * the process started.
     */
    @Suppress("DEPRECATION")
    fun resolve(context: Context): Location {
        val shared = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            FOLDER,
        )

        return if (usable(shared)) {
            Location(shared, shared = true)
        } else {
            Location(File(context.filesDir, LEGACY_FOLDER), shared = false)
        }
    }

    private fun usable(directory: File): Boolean = runCatching {
        // mkdirs is called for its side effect only: it returns false when the directory already
        // exists, so folding it into the result would report a perfectly good folder as unusable.
        directory.mkdirs()
        directory.isDirectory && directory.canWrite()
    }.getOrDefault(false)

    /**
     * Moves anything still in private storage into shared storage, and reports how many files moved.
     *
     * Idempotent by construction: once the legacy directory is gone this is a single `isDirectory`
     * check, so it is safe to run on every startup. A file already present at the destination is
     * left alone rather than overwritten, so an interrupted run cannot destroy what it has already
     * moved.
     */
    fun migrate(context: Context): Int {
        val legacy = File(context.filesDir, LEGACY_FOLDER)
        val target = resolve(context)

        if (!target.shared || !legacy.isDirectory) return 0

        var moved = 0

        for (source in legacy.listFiles().orEmpty()) {
            if (!source.isFile) continue

            val destination = File(target.directory, source.name)
            if (destination.exists()) continue

            // Files.move falls back to copy-then-delete across filesystems, which is the case here:
            // private storage and the emulated shared volume are different mounts.
            runCatching {
                target.directory.mkdirs()
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.onSuccess {
                // Migrated files arrived by a move, so the media provider has never seen them and
                // they would stay invisible to every other player.
                MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), null, null)
                moved++
            }
        }

        // Only when nothing is left, so a partial failure keeps the remainder for the next run.
        if (legacy.listFiles().isNullOrEmpty()) runCatching { legacy.delete() }

        return moved
    }
}

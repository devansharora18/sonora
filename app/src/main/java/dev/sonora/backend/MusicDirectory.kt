package dev.sonora.backend

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where downloaded files live.
 *
 * A folder the user picked through the system file picker is preferred. That is not just a
 * preference: files Sonora creates itself in shared storage are attributed to Sonora, and Android
 * deletes them when the app is uninstalled. When the system's own document provider creates the
 * file instead, it belongs to the user and survives.
 *
 * Without a chosen folder it falls back to `Music/Soulseek`, and then to app-private storage if
 * shared storage is not writable — a music app that silently stops downloading is worse than one
 * saving somewhere less convenient.
 */
object MusicDirectory {

    private const val FOLDER = "Soulseek"

    /** Where downloads went before they moved to shared storage. */
    private const val LEGACY_FOLDER = "downloads"

    data class Location(val directory: File, val shared: Boolean, val tree: Uri? = null)

    /**
     * Resolved per call rather than cached: it depends on a permission and a user choice that can
     * both change while the process is alive.
     */
    @Suppress("DEPRECATION")
    fun resolve(context: Context, treeUri: String? = null): Location {
        val tree = treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val chosen = tree?.let { pathOf(it) }
        if (chosen != null) return Location(File(chosen), shared = true, tree = tree)

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

    /**
     * The filesystem path behind a chosen tree, or null if it is not on the primary volume.
     *
     * Files created through the tree still land on the shared volume at a real path, so everything
     * downstream — the library scan, playback, artwork, playlists — can keep working with plain
     * files. Only the creation step has to go through the document provider.
     */
    @Suppress("DEPRECATION")
    private fun pathOf(tree: Uri): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val parts = id.split(':', limit = 2)
        if (parts.size != 2 || parts[0] != "primary") return null

        return "${Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
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

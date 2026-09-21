package dev.sonora.backend

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Music already on the device, read from the media provider.
 *
 * MediaStore rather than walking the filesystem: the system has already indexed these files along
 * with their tags, so this is one query instead of thousands of file reads — and it reaches music
 * in folders the app has no reason to know about.
 *
 * Reading it needs `READ_MEDIA_AUDIO` (or `READ_EXTERNAL_STORAGE` below API 33). Without it the
 * query returns only what the app itself contributed, which is the same as before this existed.
 */
object DeviceMusic {

    private const val TAG = "DeviceMusic"

    /** MediaStore reports missing tags as this rather than null. */
    private const val UNKNOWN = "<unknown>"

    private val PROJECTION = arrayOf(
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.SIZE,
    )

    /**
     * Every track the app is allowed to see, ordered by title.
     *
     * A failure here is swallowed to an empty list: a device whose media provider is unhappy should
     * leave the user with their downloads, not an empty screen.
     */
    @Suppress("DEPRECATION")
    fun list(context: Context): List<LibraryTrack> {
        val tracks = mutableListOf<LibraryTrack>()

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                // Excludes ringtones and notification sounds, which share this table. NULL has to be
                // allowed explicitly: the provider leaves IS_MUSIC unset for files it has not
                // classified yet, and `NULL != 0` is not true, so filtering on that alone silently
                // drops real music.
                "${MediaStore.Audio.Media.IS_MUSIC} IS NULL OR ${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null,
            )?.use { cursor ->
                val path = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val display = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (cursor.moveToNext()) {
                    val file = File(cursor.getString(path) ?: continue)

                    tracks += LibraryTrack(
                        file = file,
                        // MediaStore's title is the tag when there is one and the filename when
                        // there is not, which is the same fallback chain the download path uses.
                        title = cursor.getString(title)?.takeUnless { it.isUnknown() }
                            ?: file.nameWithoutExtension,
                        artist = cursor.getString(artist)?.takeUnless { it.isUnknown() },
                        album = cursor.getString(album)?.takeUnless { it.isUnknown() },
                        size = cursor.getLong(size),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "media query failed", it) }

        Log.d(TAG, "media library: ${tracks.size} track(s)")
        return tracks
    }

    private fun String.isUnknown(): Boolean = isBlank() || this == UNKNOWN
}

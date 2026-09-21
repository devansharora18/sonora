package dev.sonora.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Embedded cover art, decoded once per file and kept in memory.
 *
 * Covers are downscaled on decode: a full-size image per row would dwarf everything else in the
 * app. The cache is unbounded, which is fine for a hand-built library and would not be for a large
 * one — that is the point at which an image-loading library earns its dependency.
 */
private val artworkCache = ConcurrentHashMap<String, ImageBitmap?>()

@Composable
fun rememberArtwork(file: File): ImageBitmap? {
    var artwork by remember(file) { mutableStateOf(artworkCache[file.absolutePath]) }

    LaunchedEffect(file) {
        if (artworkCache.containsKey(file.absolutePath)) return@LaunchedEffect

        val loaded = withContext(Dispatchers.IO) { decodeArtwork(file) }
        artworkCache[file.absolutePath] = loaded
        artwork = loaded
    }

    return artwork
}

private fun decodeArtwork(file: File, targetPx: Int = 256): ImageBitmap? {
    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(file.absolutePath)
        val bytes = retriever.embeddedPicture ?: return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        val longest = max(decoded.width, decoded.height)
        val scaled = if (longest > targetPx) {
            val ratio = targetPx.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt(),
                (decoded.height * ratio).toInt(),
                true,
            )
        } else {
            decoded
        }

        scaled.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

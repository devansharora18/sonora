package dev.sonora.metadata

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Cover art for a catalogue release, from the Cover Art Archive.
 *
 * MusicBrainz stores no images; this is the companion service that does, and a release either has
 * a front cover or it does not. The `front-250` endpoint is a redirect straight to a 250px image,
 * which is one request rather than the two a JSON lookup followed by a download would cost.
 */
object CoverArt {

    fun url(releaseGroupId: String): String =
        "https://coverartarchive.org/release-group/$releaseGroupId/front-250"
}

/**
 * Cover images kept on disk, fetched at most once each.
 *
 * An album with no cover is remembered as well as one with: otherwise every visit would ask again
 * and get the same 404, which is the opposite of fetching as little as possible.
 */
class CoverArtCache(
    private val directory: File,
    private val fetch: (url: String) -> ByteArray?,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** The image bytes, or null when there is none or it could not be fetched. */
    fun load(releaseGroupId: String): ByteArray? {
        if (releaseGroupId.isBlank()) return null

        imageFor(releaseGroupId)?.let { return it }
        if (isKnownAbsent(releaseGroupId)) return null

        val bytes = fetch(CoverArt.url(releaseGroupId))

        if (bytes == null) {
            markAbsent(releaseGroupId)
            return null
        }

        write(releaseGroupId, bytes)
        return bytes
    }

    /**
     * The image, if one is held and still fresh.
     *
     * The fetch time is the first line with the image after it, for the same reason as
     * [MetadataStore]: a file's modification time is changed by copying or restoring a backup, which
     * would quietly make a stale image look fresh.
     */
    private fun imageFor(id: String): ByteArray? {
        val file = fileFor(id)
        if (!file.exists()) return null

        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null

        val newline = raw.indexOf('\n'.code.toByte())
        if (newline <= 0) return null

        val storedAt = raw.copyOfRange(0, newline).decodeToString().toLongOrNull() ?: return null
        if (now() - storedAt > TTL_MILLIS) return null

        return raw.copyOfRange(newline + 1, raw.size)
    }

    private fun write(id: String, bytes: ByteArray) {
        writeAtomically(fileFor(id), "${now()}\n".toByteArray() + bytes)
    }

    private fun markAbsent(id: String) {
        writeAtomically(absentMarkerFor(id), now().toString().toByteArray())
    }

    private fun isKnownAbsent(id: String): Boolean {
        val marker = absentMarkerFor(id)
        if (!marker.exists()) return false

        val markedAt = runCatching { marker.readText().trim().toLongOrNull() }.getOrNull() ?: return false

        return now() - markedAt <= TTL_MILLIS
    }

    /** Written beside the target and renamed, so a half-written file is never read as an image. */
    private fun writeAtomically(file: File, bytes: ByteArray) {
        directory.mkdirs()

        runCatching {
            val temp = File(directory, "${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    private fun fileFor(id: String): File = File(directory, "${hash(id)}.img")

    private fun absentMarkerFor(id: String): File = File(directory, "${hash(id)}.none")

    private fun hash(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private companion object {
        /** Long: cover art for a release does not change, and a missing cover even less so. */
        const val TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Fetches image bytes.
 *
 * Separate from [MusicBrainzTransport] because the response is binary and the service is
 * different — Cover Art Archive is archive.org, not MusicBrainz — so it is not subject to the
 * same one-request-per-second rule.
 */
class CoverArtTransport(
    private val onTrace: (String) -> Unit = {},
) : (String) -> ByteArray? {

    override fun invoke(url: String): ByteArray? {
        onTrace(url)

        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                // The endpoint redirects to the image; following it is the whole point.
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", MusicBrainz.USER_AGENT)
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }

            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}

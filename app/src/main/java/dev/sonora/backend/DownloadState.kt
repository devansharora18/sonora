package dev.sonora.backend

/** The most recent download, so the UI can show progress or report the outcome. */
sealed interface DownloadState {

    data object Idle : DownloadState

    data class Downloading(
        val filename: String,
        val peer: String,
        val bytes: Long,
        val totalBytes: Long,
        /** How many more are waiting behind this one, so a bulk download can report its depth. */
        val remaining: Int = 0,
    ) : DownloadState {
        /** Null when the size is unknown, so the UI can show an indeterminate bar. */
        val fraction: Float? get() = if (totalBytes > 0) bytes.toFloat() / totalBytes else null
    }

    data class Completed(val filename: String, val bytes: Long, val path: String) : DownloadState

    data class Failed(val filename: String, val reason: String) : DownloadState
}

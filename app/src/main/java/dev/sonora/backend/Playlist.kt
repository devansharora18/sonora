package dev.sonora.backend

import kotlinx.serialization.Serializable

/**
 * A user-made playlist.
 *
 * Tracks are held as absolute paths rather than positions or generated ids. The filesystem is the
 * library's source of truth (PRD D5), so a path that no longer resolves can be shown as a missing
 * track and dropped, instead of being a dangling index into a list that has since shifted.
 *
 * Order is the list order, so it carries meaning — a playlist is a sequence, not a set.
 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackPaths: List<String> = emptyList(),
)

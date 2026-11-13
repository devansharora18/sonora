package dev.sonora.backend.radio

import kotlinx.serialization.Serializable

/**
 * A radio the listener kept.
 *
 * Saved as its ingredients rather than as a queue: the artists, the variety and the familiar
 * promise are what a radio *is*, and the tracks are rebuilt from them every time it is played.
 * Storing the tracks would give the same mix twice and would go stale the moment the catalogue or
 * the listening history changed.
 */
@Serializable
data class SavedRadio(
    val id: String,
    val name: String,
    val seeds: List<RadioSeed>,
    val stop: Int = RadioSetup.DEFAULT_STOP,
    val familiarOnly: Boolean = false,
    val createdAt: Long = 0L,
) {
    val isReady: Boolean get() = seeds.isNotEmpty()

    /** The same radio as an editable draft. */
    fun toDraft(): RadioSetup = RadioSetup(seeds = seeds, stop = stop, familiarOnly = familiarOnly)
}

/**
 * The draft being edited and everything saved, as one document.
 *
 * [draft] survives leaving the screen and reopening the app, so the artists picked yesterday are
 * still there — but saving is a separate act, which is what makes a radio a thing on Home rather
 * than whatever the editor last had in it.
 */
@Serializable
data class RadioState(
    val draft: RadioSetup = RadioSetup(),
    val saved: List<SavedRadio> = emptyList(),
) {
    fun withRadio(radio: SavedRadio): RadioState = copy(
        saved = listOf(radio) + saved.filterNot { it.id == radio.id },
    )

    fun without(id: String): RadioState = copy(saved = saved.filterNot { it.id == id })
}

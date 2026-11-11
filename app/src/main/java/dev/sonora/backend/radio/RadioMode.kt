package dev.sonora.backend.radio

import kotlinx.serialization.Serializable

/**
 * How far a mix reaches past what the listener already knows.
 *
 * The three are one variable, not three features: the same picked artists, answered three ways.
 * That is what makes choosing between them a decision about a mood rather than about a menu, and it
 * is why they share a seed list and a scorer.
 *
 * [ordinal] is persisted inside [RadioSetup], so the order of these constants is part of the file
 * format. New modes go on the end.
 */
@Serializable
enum class RadioMode {
    /** The picked artists woven with what the listener already plays. */
    BLENDED,

    /** Music near the picks that the listener has not played. */
    DISCOVER,

    /** Only music the listener has played, liked or downloaded. */
    FAMILIAR;

    /** The name on the mix card. */
    val label: String
        get() = when (this) {
            BLENDED -> "Blended"
            DISCOVER -> "Discover"
            FAMILIAR -> "Familiar only"
        }

    /** The one line under the name. Says what the mix does, not what it is made of. */
    val blurb: String
        get() = when (this) {
            BLENDED -> "Your artists, woven with what you already play."
            DISCOVER -> "New music around the artists you picked."
            FAMILIAR -> "Only music you have played, liked or downloaded."
        }

    companion object {

        /** The stored value, tolerating a mode this build does not know. */
        fun fromName(name: String?): RadioMode =
            entries.firstOrNull { it.name == name } ?: BLENDED
    }
}

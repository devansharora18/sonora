package dev.sonora.backend

import kotlinx.serialization.Serializable

/**
 * User preferences.
 *
 * Defaults are the behaviour the app had before each setting existed, so a missing or unreadable
 * document behaves exactly like a fresh install.
 */
@Serializable
data class Settings(
    /**
     * Whether the Library lists music from the rest of the device as well as Sonora's own
     * downloads. Some users want the app to stay a download manager; others want a player.
     */
    val includeDeviceMusic: Boolean = true,
)

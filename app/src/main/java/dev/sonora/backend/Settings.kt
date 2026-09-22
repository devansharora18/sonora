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

    /**
     * A folder the user picked for downloads, as a persisted tree URI.
     *
     * Preferred over the default because files Sonora creates itself are deleted when the app is
     * uninstalled, whereas files the system's document provider creates are not.
     */
    val downloadTreeUri: String? = null,

    /**
     * Whether the user has already been asked where downloads should go.
     *
     * Without this the question would be asked again on every download after they chose the
     * default, which is nagging rather than helping.
     */
    val promptedForDownloadFolder: Boolean = false,
)

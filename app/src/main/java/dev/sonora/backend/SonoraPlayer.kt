package dev.sonora.backend

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Plays downloaded files.
 *
 * In-app only for now. Audio keeps playing while the app is backgrounded, but nothing stops
 * Android reclaiming the process, and there are no lock-screen or media-button controls yet —
 * that needs a `MediaSessionService`, which is the next step rather than something this can grow
 * into quietly.
 *
 * The player is created lazily on first playback: [play] is called from a click handler, so it
 * runs on the main thread, which is what ExoPlayer requires.
 */
object SonoraPlayer {

    private var player: ExoPlayer? = null

    private val _state = MutableStateFlow(PlaybackState())

    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun play(context: Context, track: LibraryTrack) {
        val exo = player ?: build(context)

        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(track.file)))
        exo.prepare()
        exo.play()

        _state.value = PlaybackState(track = track, isPlaying = true)
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    private fun build(context: Context): ExoPlayer =
        ExoPlayer.Builder(context.applicationContext).build().also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        _state.update { it.copy(isPlaying = false) }
                    }
                }
            })

            player = exo
        }
}

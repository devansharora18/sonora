package dev.sonora.backend

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.sonora.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Drives playback through the [PlaybackService].
 *
 * The player itself lives in that service, not here: it has to outlive the UI, and a session
 * service is what earns background playback and lock-screen controls. This holds a
 * [MediaController] — a connection to the service — and mirrors its state for the UI.
 */
object SonoraPlayer {

    private const val TAG = "SonoraPlayer"

    private val _state = MutableStateFlow(PlaybackState())

    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null

    private var connecting = false

    /** Set when playback is requested before the controller has finished connecting. */
    private var pendingQueue: List<LibraryTrack>? = null
    private var pendingIndex = 0

    /** Mirrors the local queue because the service exposes media items, not LibraryTrack values. */
    private var queue: List<LibraryTrack> = emptyList()

    /** Starts connecting to the playback service. Safe to call repeatedly. */
    fun connect(context: Context) {
        if (controller != null || connecting) return

        connecting = true
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()

        future.addListener(
            {
                connecting = false

                controller = runCatching { future.get() }
                    .onFailure { Log.w(TAG, "could not connect to playback service", it) }
                    .getOrNull()

                controller?.addListener(listener)

                // Shuffle survives on the service across a UI restart, so the mirrored state is
                // read back rather than assumed to start off.
                controller?.let { active ->
                    _state.update {
                        it.copy(
                            isShuffled = active.shuffleModeEnabled,
                            repeatMode = repeatModeOf(active.repeatMode),
                        )
                    }
                }

                pendingQueue?.let { tracks ->
                    pendingQueue = null
                    controller?.let { playNow(it, tracks, pendingIndex) }
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun play(context: Context, track: LibraryTrack) {
        play(context, listOf(track), 0)
    }

    fun play(context: Context, tracks: List<LibraryTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return

        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val active = controller
        if (active == null) {
            pendingQueue = tracks
            pendingIndex = safeIndex
            connect(context)
        } else {
            playNow(active, tracks, safeIndex)
        }
    }

    fun togglePlayPause() {
        val active = controller ?: return
        if (active.isPlaying) active.pause() else active.play()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun next() {
        val active = controller ?: return

        // With repeat-one the queue is effectively this one track, so advancing would mean the
        // mode only ever took effect at the end of the track. Restarting is what "loop this song"
        // implies when the forward control is pressed.
        if (active.repeatMode == Player.REPEAT_MODE_ONE) {
            active.seekTo(0L)
            return
        }

        active.seekToNextMediaItem()
    }

    /**
     * Shuffle is delegated to the player rather than reordering our copy of the queue: Media3
     * already shuffles traversal while keeping the current item, so reordering would duplicate
     * that and lose the place of the track playing.
     */
    fun toggleShuffle() {
        val active = controller ?: return
        active.shuffleModeEnabled = !active.shuffleModeEnabled
    }

    /**
     * Cycles off → loop queue → loop track.
     *
     * Repeat is a Media3 mode, so the player decides what happens at the end of the queue; this
     * only picks the next mode. See [next] for how the forward control behaves while looping one
     * track.
     */
    fun cycleRepeat() {
        val active = controller ?: return
        active.repeatMode = when (active.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Keeps the Compose progress bar in step with the service without moving playback ownership. */
    fun syncPosition() {
        controller?.let { active ->
            _state.update {
                it.copy(
                    positionMs = active.currentPosition.coerceAtLeast(0L),
                    durationMs = active.duration.takeIf { duration -> duration > 0L } ?: 0L,
                )
            }
        }
    }

    private fun playNow(active: MediaController, tracks: List<LibraryTrack>, startIndex: Int) {
        queue = tracks
        val mediaItems = tracks.map { MediaItem.fromUri(Uri.fromFile(it.file)) }
        active.setMediaItems(mediaItems, startIndex, 0L)
        active.prepare()
        active.play()
        updateTrack(active, startIndex)
    }

    private fun updateTrack(active: MediaController, index: Int = active.currentMediaItemIndex) {
        val track = queue.getOrNull(index) ?: return
        _state.value = PlaybackState(
            track = track,
            isPlaying = active.isPlaying,
            positionMs = active.currentPosition.coerceAtLeast(0L),
            durationMs = active.duration.takeIf { it > 0L } ?: 0L,
            isShuffled = active.shuffleModeEnabled,
            repeatMode = repeatModeOf(active.repeatMode),
        )
    }

    /** Media3 reports the repeat mode as an int; this keeps that detail out of the state. */
    private fun repeatModeOf(playerMode: Int): RepeatMode = when (playerMode) {
        Player.REPEAT_MODE_ONE -> RepeatMode.One
        Player.REPEAT_MODE_ALL -> RepeatMode.All
        else -> RepeatMode.Off
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Pause/play from the notification arrives here too, so the UI stays in step with
            // controls the app never saw.
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.let { updateTrack(it) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(isShuffled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = repeatModeOf(repeatMode)) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _state.update { it.copy(isPlaying = false) }
            }
        }
    }
}

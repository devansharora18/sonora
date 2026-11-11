package dev.sonora.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer
import dev.sonora.backend.UpNext
import dev.sonora.ui.theme.accentText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * What is playing and what comes next, styled to match the player screen.
 *
 * Sits as a translucent sheet over the blurred sleeve backdrop, with interactive pull-to-dismiss
 * gesture, drag reordering, and rounded typography and icons.
 */
@Composable
internal fun QueuePanel(
    upNext: UpNext,
    onPlayFrom: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var held by remember { mutableStateOf<Int?>(null) }
    var carried by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val animOffsetY = remember { Animatable(0f) }
    var isAnimating by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val settings by SonoraBackend.settings.collectAsState()
    val autoplayKeySet by SonoraPlayer.autoplayKeys.collectAsState()
    val radioLabel by SonoraBackend.activeRadioLabel.collectAsState()

    val currentOffset = if (isAnimating) animOffsetY.value else dragOffsetY

    val verticalDragState = rememberDraggableState { delta ->
        if (isDismissing) return@rememberDraggableState
        if (isAnimating) {
            dragOffsetY = animOffsetY.value
            isAnimating = false
        }
        dragOffsetY = (dragOffsetY + delta).coerceIn(0f, screenHeightPx)
    }

    val onVerticalDragStopped: suspend kotlinx.coroutines.CoroutineScope.(Float) -> Unit = { velocity ->
        if (!isDismissing) {
            val offset = dragOffsetY
            val shouldDismiss = (offset > dismissThresholdPx && velocity > -400f) || velocity > 800f
            if (shouldDismiss) {
                isDismissing = true
                isAnimating = true
                coroutineScope.launch {
                    try {
                        animOffsetY.snapTo(offset)
                        animOffsetY.animateTo(
                            targetValue = screenHeightPx,
                            initialVelocity = velocity.coerceAtLeast(0f),
                            animationSpec = tween(
                                durationMillis = 200,
                                easing = FastOutLinearInEasing,
                            ),
                        )
                    } catch (_: Exception) {
                    } finally {
                        withContext(NonCancellable) {
                            onClose()
                        }
                    }
                }
            } else {
                isAnimating = true
                coroutineScope.launch {
                    try {
                        animOffsetY.snapTo(offset)
                        animOffsetY.animateTo(
                            targetValue = 0f,
                            initialVelocity = velocity,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    } finally {
                        dragOffsetY = 0f
                        isAnimating = false
                    }
                }
            }
        }
    }

    val sheetShape = remember { RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) }
    val following = upNext.following

    Surface(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = currentOffset
                clip = true
                shape = sheetShape
            }
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = sheetShape,
            ),
        color = Color(0xFF262629).copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // Drag handle and top bar with vertical drag gesture
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = verticalDragState,
                        onDragStopped = onVerticalDragStopped,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 8.dp)
                        .size(width = 36.dp, height = 4.5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.32f)),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SubIconButton(
                        icon = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Close queue",
                        onClick = onClose,
                        size = 40.dp,
                        glyphSize = 24.dp,
                        idleTint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.CenterStart),
                    )

                    Text(
                        text = "UP NEXT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                        ),
                        color = Color.White.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center,
                    )

                    if (following.isNotEmpty()) {
                        Text(
                            text = if (following.size == 1) "1 track" else "${following.size} tracks",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp),
                        )
                    }
                }
            }

            // A chip rather than a line of text: it is a control, and a sentence in the middle of
            // a header reads as a caption. The fill is what says it is pressable, the colour is
            // what says whether it is on.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (settings.autoplay) 0.14f else 0.06f))
                        .border(
                            0.5.dp,
                            Color.White.copy(alpha = if (settings.autoplay) 0.28f else 0.14f),
                            CircleShape,
                        )
                        .clickable { SonoraBackend.setAutoplay(context, !settings.autoplay) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Autorenew,
                        contentDescription = null,
                        tint = if (settings.autoplay) {
                            MaterialTheme.colorScheme.accentText
                        } else {
                            Color.White.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (settings.autoplay) "Autoplay on" else "Autoplay off",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = if (settings.autoplay) 0.92f else 0.55f),
                    )
                }
            }

            radioLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
            ) {                val current = upNext.current
                if (current != null) {
                    item(key = "now-playing") {
                        QueueHeading("NOW PLAYING")
                        QueueRow(
                            track = current,
                            isCurrent = true,
                            isPlaying = true,
                            onClick = { onPlayFrom(upNext.index) },
                            onRemove = null,
                        )
                    }
                }

                if (following.isNotEmpty()) {
                    item(key = "next-heading") {
                        QueueHeading("NEXT IN QUEUE")
                    }
                }

                itemsIndexed(following, key = { _, t -> t.key }) { position, track ->
                    val absolute = upNext.index + 1 + position
                    val dragging = held == position

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, if (dragging) carried.roundToInt() else 0) }
                            .zIndex(if (dragging) 1f else 0f)
                            .pointerInput(position) {
                                var offsetY = 0f
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        held = position
                                        carried = 0f
                                        rowHeight = size.height.toFloat()
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        offsetY += amount.y
                                        carried = offsetY
                                    },
                                    onDragEnd = {
                                        val row = rowHeight.takeIf { it > 0f } ?: 1f
                                        val steps = (offsetY / row).toInt()
                                        if (steps != 0) {
                                            onMove(absolute, (absolute + steps).coerceIn(0, following.size))
                                        }
                                        held = null
                                        carried = 0f
                                    },
                                    onDragCancel = {
                                        held = null
                                        carried = 0f
                                    },
                                )
                            },
                    ) {
                        QueueRow(
                            track = track,
                            isCurrent = false,
                            isPlaying = false,
                            isAutoplay = track.key in autoplayKeySet,
                            onClick = { onPlayFrom(absolute) },
                            onRemove = { onRemove(absolute) },
                            onDragHandle = { delta ->
                                held = position
                                carried += delta
                            },
                            isHeld = dragging,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        ),
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * One entry in the queue with rounded corners, subtle glass highlighting and rounded icons.
 */
@Composable
private fun QueueRow(
    track: LibraryTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    onDragHandle: ((Float) -> Unit)? = null,
    isHeld: Boolean = false,
    isAutoplay: Boolean = false,
) {
    val rowShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(
                when {
                    isHeld -> Color.White.copy(alpha = 0.14f)
                    isCurrent -> Color.White.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artShape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(artShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), artShape),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = rememberTrackArtwork(track, px = ROW_ART_PX)
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!track.artist.isNullOrBlank()) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isAutoplay) {
                Text(
                    text = "AUTOPLAY",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1,
                )
            }
        }

        if (isCurrent) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                contentDescription = "Now playing",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
        }

        if (onDragHandle != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, amount ->
                                change.consume()
                                onDragHandle(amount.y)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = "Hold and slide to reorder",
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }

        if (onRemove != null) {
            SubIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Remove from queue",
                onClick = onRemove,
                size = 36.dp,
                glyphSize = 18.dp,
                idleTint = Color.White.copy(alpha = 0.5f),
            )
        }
    }
}

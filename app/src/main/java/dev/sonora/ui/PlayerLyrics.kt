package dev.sonora.ui

import android.os.Build
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonora.lyrics.CharGrowth
import dev.sonora.lyrics.GrowingWord
import dev.sonora.lyrics.LyricAlignment
import dev.sonora.lyrics.LyricLine
import dev.sonora.lyrics.Lyrics
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

/** How far back the part of the playing line that hasn't been sung yet is held. */
private const val UNSUNG_ALPHA = 0.45f

/**
 * The bloom behind the line being sung, at its very strongest.
 * Scaled by each letter's own bloom, so only a carried note sees the whole of it.
 */
private const val GLOW_ALPHA = 0.62f

/** How far the bloom spreads off a letter. */
private val GLOW_RADIUS = 6.dp

/** Room reserved inside each copy of a line for the halo to spread into. */
private val GLOW_ROOM = 10.dp

/** Answering vocal styling, hanging cleanly under the lead line. */
private val BACKING_FONT_SIZE = 20.sp
private val BACKING_LINE_HEIGHT = 26.sp
private const val BACKING_ALPHA = 0.72f

/** How far the sweep's leading edge fades out instead of ending on a hard cut. */
private val WIPE_FEATHER = 30.dp

/** How far the word being sung lifts off the line. */
private val WORD_RISE = 2.dp

/** Extra upward headroom allowed when a word is swelling letter-by-letter. */
private const val GROW_HEADROOM = 3f

/** The lane kept clear on the far side of a duet line. */
private val DUET_LANE = 40.dp

/** Falloff alpha and blur levels by distance from the focus line. */
private val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
private val LINE_FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)

private const val BROWSING_ALPHA = 0.8f
private const val INACTIVE_SCALE = 0.98f
private const val PRESSED_SCALE = 0.96f
private const val LYRIC_SETTLE_MS = 400

/** Instrumental break metrics matching BitChord */
private const val GAP_DOTS = 3
private val GAP_DOT_SIZE = 13.dp
private val GAP_DOT_GAP = 5.dp
private const val GAP_DOT_REST = 0.25f
private const val GAP_REST_SCALE = 0.76f
private val GAP_ROW_HEIGHT = 40.dp
private val GAP_ROW_SPACING = 16.dp

/** Staggered row arrival physics */
private const val STAGGER_STEPS = 3
private const val STAGGER_FRACTION = 0.06f

private class ScrollRun(val id: Int, val delta: Float, val durationMs: Int) {
    val spanMs: Float get() = durationMs * (1f + STAGGER_FRACTION * STAGGER_STEPS)
}

/** Top and bottom edge mask fade proportion. */
private const val FADE = 0.16f

private const val SCROLL_LEAD_MIN_MS = 350L
private const val SCROLL_LEAD_MAX_MS = 500L
private val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)

private fun scrollLead(lines: List<LyricLine>, positionMs: Long): Long {
    val current = lines.indexOfLast { it.timeMs <= positionMs }
    if (current < 0) return SCROLL_LEAD_MIN_MS
    val next = lines.getOrNull(current + 1) ?: return SCROLL_LEAD_MIN_MS
    val gap = next.timeMs - lines[current].endMs
    return gap.coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
}

/** Height of the spacer at the top of synced lyrics to keep the active line in slot 2. */
private val PREV_LYRIC_SPACER_HEIGHT = 60.dp

/**
 * Returns the LazyColumn item index to scroll to so that [focusLine] appears as the
 * second line displayed in normal mode, or the third line in fullscreen mode.
 */
internal fun targetLyricItemIndex(lines: List<LyricLine>, focusLine: Int, isFullscreen: Boolean = false): Int {
    if (focusLine <= 0 || focusLine !in lines.indices) return 0
    if (!isFullscreen) {
        var prev = focusLine - 1
        while (prev > 0 && lines[prev].isGap) {
            prev--
        }
        if (lines[prev].isGap) {
            return 0
        }
        return prev + 1
    } else {
        // In fullscreen mode, active lyric should be on the third line,
        // so we want 2 preceding non-gap lines visible above it.
        var count = 0
        var target = focusLine - 1
        var prev2 = -1
        while (target >= 0) {
            if (!lines[target].isGap) {
                count++
                if (count == 2) {
                    prev2 = target
                    break
                }
            }
            target--
        }
        if (prev2 >= 0) {
            return prev2 + 1
        }
        return 0
    }
}

internal fun previousVisibleItemIndex(lines: List<LyricLine>, focusLine: Int): Int =
    targetLyricItemIndex(lines, focusLine, isFullscreen = false)

/**
 * The lyrics pane displaying synced lines following the current playhead.
 *
 * Implements Apple Music-style layered rendering:
 * - Dim unsung base copy
 * - Blurred bloom halo layer on carried notes
 * - Feathered lit wipe moving smoothly across sung words and characters
 * - Upward word lift on active singing
 * - Backing vocal formatting and duet voice separation
 */
@Composable
internal fun LyricsPane(
    lyrics: Lyrics,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    isFullscreen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    val clock = rememberLyricClock(lyrics, positionMs, isPlaying)
    val now = clock.longValue

    val isSynced = remember(lyrics.lines) { lyrics.lines.any { it.timeMs > 0L } }
    val duet = remember(lyrics.lines) { lyrics.lines.any { it.alignment == LyricAlignment.End } }

    val activeRows by remember(lyrics.lines, isSynced) {
        derivedStateOf {
            if (!isSynced) emptyList() else activeLyricRows(lyrics.lines, clock.longValue)
        }
    }
    val scrollLine = activeRows.firstOrNull() ?: -1

    val leadTime = remember(lyrics.lines, now) { scrollLead(lyrics.lines, now) }
    val leadLine by remember(lyrics.lines, isSynced) {
        derivedStateOf {
            if (!isSynced) -1
            else activeLyricRows(lyrics.lines, clock.longValue + leadTime).firstOrNull() ?: -1
        }
    }
    val focusLine = if (leadLine >= 0) leadLine else scrollLine

    var browsing by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                browsing = true
            }
        }
    }

    val activeOnScreen by remember(listState, focusLine, isSynced) {
        derivedStateOf {
            val targetIdx = if (isSynced) focusLine + 1 else focusLine
            listState.layoutInfo.visibleItemsInfo.any { it.index == targetIdx }
        }
    }

    LaunchedEffect(browsing, activeOnScreen, listState.isScrollInProgress, isPlaying) {
        if (isPlaying && browsing && activeOnScreen && !listState.isScrollInProgress) {
            delay(600)
            browsing = false
        }
    }

    LaunchedEffect(browsing, listState.isScrollInProgress, isPlaying) {
        if (isPlaying && browsing && !listState.isScrollInProgress) {
            delay(5_000)
            browsing = false
        }
    }

    var run by remember(lyrics.lines) { mutableStateOf(ScrollRun(0, 0f, LYRIC_SETTLE_MS)) }
    val since = remember(lyrics.lines) { mutableFloatStateOf(0f) }
    LaunchedEffect(run.id) {
        if (run.id == 0) return@LaunchedEffect
        animate(
            initialValue = 0f,
            targetValue = run.spanMs,
            animationSpec = tween(run.spanMs.toInt(), easing = LinearEasing),
        ) { value, _ -> since.floatValue = value }
    }

    var placed by remember(lyrics) { mutableStateOf(false) }

    LaunchedEffect(focusLine, browsing, isFullscreen) {
        if (!browsing && focusLine >= 0 && focusLine in lyrics.lines.indices) {
            val targetIndex = if (isSynced) {
                targetLyricItemIndex(lyrics.lines, focusLine, isFullscreen)
            } else {
                focusLine
            }
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            when {
                !placed -> {
                    listState.scrollToItem(targetIndex, scrollOffset = 0)
                    placed = true
                }
                visible != null -> {
                    val span = scrollLead(lyrics.lines, clock.longValue).toInt()
                    run = ScrollRun(run.id + 1, visible.offset.toFloat(), span)
                    listState.animateScrollBy(
                        value = visible.offset.toFloat(),
                        animationSpec = tween(durationMillis = span, easing = LYRIC_EASING),
                    )
                }
                else -> listState.animateScrollToItem(targetIndex, scrollOffset = 0)
            }
        }
    }

    when {
        lyrics.loading -> LyricsSkeleton(Modifier.fillMaxSize())

        lyrics.lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = lyrics.reason ?: "No lyrics",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        else -> {
            val glowing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val viewportHeight by remember(listState) {
                derivedStateOf { listState.layoutInfo.viewportSize.height }
            }
            val viewportDp = with(LocalDensity.current) {
                if (viewportHeight > 0) viewportHeight.toDp() else 360.dp
            }

            Box(
                modifier = modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                FADE to Color.Black,
                                1f - FADE to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 40.dp - GLOW_ROOM,
                        bottom = viewportDp * 0.8f,
                        start = 20.dp - GLOW_ROOM,
                        end = 20.dp - GLOW_ROOM,
                    ),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    if (isSynced) {
                        item(key = "top_spacer") {
                            val nonGapBefore = remember(lyrics.lines, focusLine) {
                                var c = 0
                                for (i in 0 until focusLine) {
                                    if (i in lyrics.lines.indices && !lyrics.lines[i].isGap) c++
                                }
                                c
                            }
                            val targetSpacer = when {
                                !isFullscreen -> PREV_LYRIC_SPACER_HEIGHT
                                nonGapBefore == 0 -> 128.dp
                                nonGapBefore == 1 -> 64.dp
                                else -> PREV_LYRIC_SPACER_HEIGHT
                            }
                            val topSpacerHeight by animateDpAsState(
                                targetValue = targetSpacer,
                                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                                label = "topSpacerHeight",
                            )
                            Spacer(Modifier.height(topSpacerHeight))
                        }
                    }
                    itemsIndexed(lyrics.lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                        if (line.isGap) {
                            val until = lyrics.lines.getOrNull(index + 1)?.timeMs ?: line.endMs
                            val offset = if (scrollLine < 0) 0 else index - scrollLine
                            val distance = abs(offset)
                            val isActive = isSynced && index in activeRows
                            val step = distance.coerceAtMost(LINE_FALLOFF_ALPHA.lastIndex)
                            val blur by animateDpAsState(
                                targetValue = when {
                                    !isSynced || !glowing || browsing || isActive -> 0.dp
                                    else -> LINE_FALLOFF_BLUR[step]
                                },
                                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                                label = "lyricBlur",
                            )
                            val lineAlpha by animateFloatAsState(
                                targetValue = when {
                                    !isSynced -> 0.95f
                                    isActive -> 1f
                                    browsing -> BROWSING_ALPHA
                                    else -> LINE_FALLOFF_ALPHA[step]
                                },
                                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                                label = "lyricAlpha",
                            )
                            val swell by animateFloatAsState(
                                targetValue = if (isActive) 1f else 0f,
                                animationSpec = tween(
                                    durationMillis = if (isActive) 400 else 350,
                                    easing = LYRIC_EASING,
                                ),
                                label = "gapSwell",
                            )
                            val instrumental = "Instrumental"
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .height((GAP_ROW_HEIGHT + GAP_ROW_SPACING) * swell)
                                    .clipToBounds(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .blur(blur, BlurredEdgeTreatment.Unbounded)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(enabled = isSynced) {
                                            browsing = false
                                            onSeek(line.timeMs)
                                        }
                                        .padding(GLOW_ROOM)
                                        .size(
                                            width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2,
                                            height = GAP_DOT_SIZE,
                                        )
                                        .graphicsLayer {
                                            val grow = GAP_REST_SCALE + (1f - GAP_REST_SCALE) * swell
                                            scaleX = grow
                                            scaleY = grow
                                            transformOrigin = TransformOrigin(0f, 0.5f)
                                            alpha = lineAlpha * swell
                                        }
                                        .drawBehind {
                                            val span = (until - line.timeMs).coerceAtLeast(1L)
                                            val through = ((clock.longValue - line.timeMs).toFloat() / span)
                                                .coerceIn(0f, 1f)
                                            val radius = GAP_DOT_SIZE.toPx() / 2f
                                            val stride = (GAP_DOT_SIZE + GAP_DOT_GAP).toPx()
                                            repeat(GAP_DOTS) { dot ->
                                                val lit = (through * GAP_DOTS - dot).coerceIn(0f, 1f)
                                                drawCircle(
                                                    color = Color.White.copy(
                                                        alpha = GAP_DOT_REST + (1f - GAP_DOT_REST) * lit,
                                                    ),
                                                    radius = radius,
                                                    center = Offset(radius + dot * stride, size.height / 2f),
                                                )
                                            }
                                        }
                                        .semantics { contentDescription = instrumental },
                                )
                            }
                        } else {
                            val alignEnd = duet && line.alignment == LyricAlignment.End
                            val offset = if (scrollLine < 0) 0 else index - scrollLine
                            val distance = abs(offset)
                            val isActive = isSynced && index in activeRows
                            val sung = offset < 0

                            val step = distance.coerceAtMost(LINE_FALLOFF_ALPHA.lastIndex)
                            val blur by animateDpAsState(
                                targetValue = when {
                                    !isSynced || !glowing || browsing || isActive -> 0.dp
                                    else -> LINE_FALLOFF_BLUR[step]
                                },
                                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                                label = "lyricBlur",
                            )
                            val lineAlpha by animateFloatAsState(
                                targetValue = when {
                                    !isSynced -> 0.95f
                                    isActive -> 1f
                                    browsing -> BROWSING_ALPHA
                                    else -> LINE_FALLOFF_ALPHA[step]
                                },
                                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                                label = "lyricAlpha",
                            )

                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            val scale by animateFloatAsState(
                                targetValue = when {
                                    pressed -> PRESSED_SCALE
                                    isActive -> 1f
                                    else -> INACTIVE_SCALE
                                },
                                animationSpec = tween(
                                    durationMillis = if (pressed) 120 else LYRIC_SETTLE_MS,
                                    easing = LYRIC_EASING,
                                ),
                                label = "lyricScale",
                            )
                            val glow by animateFloatAsState(
                                targetValue = if (isActive && glowing) GLOW_ALPHA else 0f,
                                animationSpec = tween(durationMillis = 420),
                                label = "lyricGlow",
                            )

                            val style = if (isSynced) {
                                MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = 28.sp,
                                    lineHeight = 35.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                                )
                            } else {
                                MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 24.sp,
                                    lineHeight = 32.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                                )
                            }

                            val behind = if (run.delta >= 0f) index - focusLine else focusLine - index
                            val staggerDelay = behind.coerceIn(0, STAGGER_STEPS) *
                                STAGGER_FRACTION * run.durationMs

                            val shape = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = if (duet && alignEnd) DUET_LANE else 0.dp,
                                    end = if (duet && !alignEnd) DUET_LANE else 0.dp,
                                )
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                                    alpha = lineAlpha
                                    translationY = if (staggerDelay <= 0f) {
                                        0f
                                    } else {
                                        val elapsed = since.floatValue
                                        run.delta * (
                                            LYRIC_EASING.transform(
                                                (elapsed / run.durationMs).coerceIn(0f, 1f),
                                            ) - LYRIC_EASING.transform(
                                                ((elapsed - staggerDelay) / run.durationMs).coerceIn(0f, 1f),
                                            )
                                        )
                                    }
                                }
                                .blur(blur, BlurredEdgeTreatment.Unbounded)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    enabled = isSynced,
                                    interactionSource = interaction,
                                    indication = null,
                                ) {
                                    browsing = false
                                    onSeek(line.timeMs)
                                }

                            Column(modifier = shape) {
                                PanelVoice(
                                    line = line,
                                    clock = clock,
                                    style = style,
                                    isActive = isActive,
                                    sung = sung,
                                    synced = isSynced,
                                    browsing = browsing,
                                    glowAlpha = glow,
                                    room = GLOW_ROOM,
                                    alignEnd = alignEnd,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                line.background?.let { backing ->
                                    PanelVoice(
                                        line = backing.withoutBracketPunctuation(),
                                        clock = clock,
                                        style = style.copy(
                                            fontSize = BACKING_FONT_SIZE,
                                            lineHeight = BACKING_LINE_HEIGHT,
                                        ),
                                        isActive = isActive,
                                        sung = sung,
                                        synced = isSynced,
                                        browsing = browsing,
                                        glowAlpha = 0f,
                                        room = 0.dp,
                                        alignEnd = alignEnd,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                            .graphicsLayer { alpha = BACKING_ALPHA },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One vocal line in the panel, either lead or background.
 */
@Composable
private fun PanelVoice(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    sung: Boolean,
    synced: Boolean,
    browsing: Boolean,
    glowAlpha: Float,
    room: Dp,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    if (line.isWordSynced && !browsing) {
        val tail by animateFloatAsState(
            targetValue = if (sung) 1f else UNSUNG_ALPHA,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = line,
            clock = clock,
            style = style,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = glowAlpha,
            glowRoom = room,
            feather = isActive,
            alignEnd = alignEnd,
        )
    } else if (line.isWordSynced) {
        val tail by animateFloatAsState(
            targetValue = if (sung) 1f else UNSUNG_ALPHA,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = line,
            clock = clock,
            style = style,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = 0f,
            glowRoom = room,
            alignEnd = alignEnd,
        )
    } else {
        val lit by animateFloatAsState(
            targetValue = if (!synced || sung || isActive) 1f else UNSUNG_ALPHA,
            label = "lyricLit",
        )
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = lit),
            modifier = modifier.padding(room),
        )
    }
}

/**
 * Three-pass stacked text:
 * 1. Dim base copy
 * 2. Bloom copy (blurred, clipped to held letters)
 * 3. Lit white copy masked by character sweep
 */
@Composable
private fun SweptLyricLine(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    glowAlpha: Float = 0f,
    glowRadius: Dp = GLOW_RADIUS,
    glowRoom: Dp = 0.dp,
    feather: Boolean = false,
    rise: Boolean = true,
    alignEnd: Boolean = false,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val growth = remember { CharGrowth() }
    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

    val riseAgainst: (Modifier) -> Modifier = { inner ->
        if (!rise) {
            inner
        } else {
            Modifier
                .drawWithContent {
                    val measured = layout
                    if (measured == null || line.words.isEmpty()) {
                        drawContent()
                    } else {
                        riseWith(
                            layout = measured,
                            line = line,
                            positionMs = clock.longValue,
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    }
                }
                .then(inner)
        }
    }

    val sweep = Modifier.drawWithContent {
        val position = clock.longValue
        when {
            position >= line.endMs -> drawContent()
            position <= line.timeMs -> Unit
            else -> layout?.let { sweepTo(it, line.revealedChars(position), feather) }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
    ) {
        // Pass 1: Dim unsung base
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = riseAgainst(room),
        )

        // Pass 2: Apple bloom halo on held notes
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = Color.White,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .graphicsLayer { alpha = glowAlpha }
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .then(room)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        val measured = layout ?: return@drawWithContent
                        glowGrown(
                            layout = measured,
                            line = line,
                            positionMs = clock.longValue,
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    },
            )
        }

        // Pass 3: Feathered lit copy swept by time
        Text(
            text = line.text,
            style = style,
            color = Color.White,
            maxLines = maxLines,
            overflow = overflow,
            modifier = riseAgainst(
                Modifier
                    .graphicsLayer {
                        compositingStrategy = if (feather) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                    }
                    .then(room)
                    .then(sweep),
            ),
        )
    }
}

/**
 * Draws text clipped to letters of words being held, each at its own brightness.
 */
private fun ContentDrawScope.glowGrown(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isGrowing(positionMs)) return
    val em = layout.layoutInput.style.fontSize.toPx()
    val length = layout.layoutInput.text.length
    for (word in line.growingWords) {
        if (positionMs < word.startMs || positionMs > word.restsAtMs) continue
        val span = line.wordSpans[word.index]
        val fall = line.wordFall(word.index, positionMs)
        for (char in span.first..minOf(span.last, length - 1)) {
            word.sampleInto(char - span.first, positionMs, growth)
            if (growth.bloom <= 0.01f) continue
            val visualLine = layout.getLineForOffset(char)
            val from = layout.xOn(char, visualLine, inset)
            val to = layout.xOn(char + 1, visualLine, inset)
            if (to <= from) continue
            val dx = growth.shift * em
            val dy = -growth.rise * peak * fall
            val rowTop = layout.getLineTop(visualLine) + inset
            val bottom = layout.getLineBottom(visualLine) + inset
            val overhang = (to - from) * (growth.scale - 1f) / 2f
            clipRect(
                left = from - overhang + dx,
                top = rowTop - peak * GROW_HEADROOM,
                right = to + overhang + dx,
                bottom = bottom,
            ) {
                translate(left = dx, top = dy) {
                    scale(
                        growth.scale,
                        growth.scale,
                        Offset((from + to) / 2f, (rowTop + bottom) / 2f),
                    ) {
                        this@glowGrown.drawContent()
                    }
                }
                drawRect(
                    color = Color.White.copy(alpha = growth.bloom),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

/**
 * Redraws row with the word being sung lifted off the line.
 */
private fun ContentDrawScope.riseWith(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isLifted(positionMs)) {
        drawContent()
        return
    }
    val em = layout.layoutInput.style.fontSize.toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
        val top = layout.getLineTop(visualLine) + inset
        val bottom = layout.getLineBottom(visualLine) + inset
        var at = lineStart
        var edge = layout.getLineLeft(visualLine) + inset
        for (index in line.words.indices) {
            val span = line.wordSpans[index]
            val start = maxOf(span.first, lineStart)
            val end = minOf(span.last + 1, lineEnd)
            if (start >= end) continue
            val held = line.growingAt(index)?.takeIf { positionMs in it.startMs..it.restsAtMs }
            val lift = line.wordLift(index, positionMs)
            if (held == null && lift <= 0.01f) continue
            val from = layout.xOn(start, visualLine, inset)
            val to = layout.xOn(end, visualLine, inset)
            if (to <= from) continue
            if (start > at) sliceRisen(edge, top, from, bottom, 0f)
            if (held != null) {
                growEach(
                    layout, held, line, positionMs, visualLine,
                    start, end, top, bottom, inset, peak, em, growth,
                )
            } else {
                sliceRisen(from, top - peak, to, bottom, -lift * peak)
            }
            at = end
            edge = to
        }
        if (at < lineEnd) {
            sliceRisen(edge, top, layout.getLineRight(visualLine) + inset, bottom, 0f)
        }
    }
}

private fun ContentDrawScope.growEach(
    layout: TextLayoutResult,
    word: GrowingWord,
    line: LyricLine,
    positionMs: Long,
    visualLine: Int,
    start: Int,
    end: Int,
    top: Float,
    bottom: Float,
    inset: Float,
    peak: Float,
    em: Float,
    growth: CharGrowth,
) {
    val fall = line.wordFall(word.index, positionMs)
    val first = line.wordSpans[word.index].first
    val ceiling = top - peak * GROW_HEADROOM
    val middle = (top + bottom) / 2f
    for (char in start until end) {
        word.sampleInto(char - first, positionMs, growth)
        val from = layout.xOn(char, visualLine, inset)
        val to = layout.xOn(char + 1, visualLine, inset)
        if (to <= from) continue
        val dx = growth.shift * em
        val dy = -growth.rise * peak * fall
        val overhang = (to - from) * (growth.scale - 1f) / 2f
        clipRect(
            left = from - overhang + dx,
            top = ceiling,
            right = to + overhang + dx,
            bottom = bottom,
        ) {
            translate(left = dx, top = dy) {
                scale(growth.scale, growth.scale, Offset((from + to) / 2f, middle)) {
                    this@growEach.drawContent()
                }
            }
        }
    }
}

private fun TextLayoutResult.xOn(offset: Int, visualLine: Int, inset: Float): Float {
    val left = getLineLeft(visualLine) + inset
    val right = getLineRight(visualLine) + inset
    return when {
        offset <= getLineStart(visualLine) -> left
        offset >= getLineEnd(visualLine, visibleEnd = true) -> right
        else -> (getHorizontalPosition(offset, usePrimaryDirection = true) + inset)
            .coerceIn(left, right)
    }
}

private fun ContentDrawScope.sliceRisen(
    from: Float,
    top: Float,
    to: Float,
    bottom: Float,
    dy: Float,
) {
    if (to <= from) return
    clipRect(left = from, top = top, right = to, bottom = bottom) {
        translate(top = dy) { this@sliceRisen.drawContent() }
    }
}

private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    visualLine: Int,
): Float {
    val lineStart = layout.getLineStart(visualLine)
    val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
    val index = chars.toInt().coerceIn(lineStart, lineEnd)
    val here = layout.xOn(index, visualLine, 0f)
    val next = layout.xOn((index + 1).coerceAtMost(lineEnd), visualLine, 0f)
    return here + (next - here) * (chars - index)
}

/**
 * Draws text clipped to its first [revealedChars] characters with optional feathered edge.
 */
private fun ContentDrawScope.sweepTo(
    layout: TextLayoutResult,
    revealedChars: Float,
    feather: Boolean,
) {
    if (revealedChars <= 0f) return
    if (revealedChars >= layout.layoutInput.text.length) {
        drawContent()
        return
    }
    for (visualLine in 0 until layout.lineCount) {
        val start = layout.getLineStart(visualLine)
        if (revealedChars <= start) return
        val end = layout.getLineEnd(visualLine, visibleEnd = true)
        val cut = revealedChars < end
        val right = if (cut) {
            horizontalAt(layout, revealedChars, visualLine)
        } else {
            layout.getLineRight(visualLine)
        }
        val top = layout.getLineTop(visualLine)
        val bottom = layout.getLineBottom(visualLine)
        clipRect(
            left = layout.getLineLeft(visualLine),
            top = top,
            right = right,
            bottom = bottom,
        ) {
            this@sweepTo.drawContent()
        }
        if (!feather || !cut) continue
        clipRect(top = top, bottom = bottom) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White,
                    1f to Color.Transparent,
                    startX = (right - WIPE_FEATHER.toPx())
                        .coerceAtLeast(layout.getLineLeft(visualLine)),
                    endX = right,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

private fun LyricLine.withoutBracketPunctuation(): LyricLine = copy(
    text = text.stripParens(),
    words = words.mapNotNull { word ->
        word.text.stripParens().takeIf { it.isNotEmpty() }?.let { word.copy(text = it) }
    },
)

private fun String.stripParens(): String = replace("(", "").replace(")", "").trim()

/**
 * How far the running playhead may drift from the player's before the player's wins.
 */
private const val PLAYHEAD_TOLERANCE_MS = 500L

/**
 * Whether the position the player reports should replace the one being extrapolated.
 */
internal fun shouldAdoptReportedPosition(reportedMs: Long, runningMs: Long): Boolean =
    kotlin.math.abs(reportedMs - runningMs) > PLAYHEAD_TOLERANCE_MS

/**
 * The shimmer shown while lyrics are being fetched.
 */
@Composable
private fun LyricsSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lyricsSkeleton")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeletonPhase",
    )

    Column(
        modifier = modifier.padding(horizontal = 30.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        repeat(2) { Spacer(Modifier.height(34.dp)) }
        repeat(5) { index ->
            val lit = 0.4f + 0.35f * sin((phase * 2f * Math.PI + index * 0.7f).toFloat())
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (index % 2 == 0) 0.84f else 0.66f)
                    .height(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.05f + 0.05f * lit)),
            )
        }
    }
}

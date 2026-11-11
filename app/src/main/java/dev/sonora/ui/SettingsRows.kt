package dev.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How far the card is inset from the page's edges. */
internal val GROUP_INSET: Dp = 16.dp

/** How far a row's contents are inset from the card's own edges. */
internal val ROW_INSET: Dp = 16.dp

/** The glyph at a row's leading end. */
private val ICON_SIZE: Dp = 22.dp

/** The gap between a row's glyph and its text. */
private val ICON_GAP: Dp = 14.dp

/**
 * Where a divider inside a group starts.
 *
 * Level with the row's text rather than with its glyph. A hairline that runs from the card's own
 * edge cuts through the middle of the icons in the column, and a column of icons with lines drawn
 * across them reads as a rendering fault rather than as separators.
 */
private val DIVIDER_INSET: Dp = ROW_INSET + ICON_SIZE + ICON_GAP

/**
 * One inset card of rows, under an uppercase heading and over an optional note.
 *
 * The card is the point: rows on the page's own background have nothing to group them, so a page of
 * settings reads as one undifferentiated list where "the three that matter" and "the four that are
 * here for completeness" look equally important. The surface is what tells the eye where one
 * group ends.
 */
@Composable
internal fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    footerIsWarning: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = GROUP_INSET + 4.dp,
                    end = GROUP_INSET,
                    top = 26.dp,
                    bottom = 8.dp,
                ),
            )
        } else {
            Spacer(Modifier.height(26.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GROUP_INSET)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            content = content,
        )

        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = if (footerIsWarning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(
                    start = GROUP_INSET + 4.dp,
                    end = GROUP_INSET,
                    top = 8.dp,
                ),
            )
        }
    }
}

/** The hairline between two rows of one group. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = DIVIDER_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * The standard row: glyph, title, optional subtitle, and on the right either [trailing] or the
 * current [value] followed by a chevron.
 *
 * Clickable across its whole width even when the control is a switch, because a switch that is
 * only live on the switch is a target a thumb has to hit exactly — and the row's own text is the
 * thing people aim at.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onClick != null) Spacer(Modifier.width(4.dp))
            }
            // Only where there is somewhere to go. A read-only figure with an arrow beside it is a
            // row that looks tappable and is not, which is worse than no arrow at all.
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * A switch in a row's trailing slot, painted in the app's own colour.
 *
 * The default Material switch is two greys and a white knob, which on a page whose only colour is
 * the accent means the one control a row is about is the least colourful thing in it.
 */
@Composable
internal fun SettingSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * A row of actions rather than one setting: several buttons, evenly spread, inside the card.
 *
 * Separate from [SettingsRow] because a row of buttons has no single title to click and no single
 * control on the right, and giving it either would imply there is one.
 */
@Composable
internal fun SettingsActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A centred slot that holds a settings group's own control without disturbing the row's rhythm. */
@Composable
internal fun SettingsActionBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

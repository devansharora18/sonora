package dev.sonora.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.taste.TasteModel
import dev.sonora.backend.taste.TasteSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * What the taste engine has learned, and the controls over it.
 *
 * Reads [SonoraBackend.tasteState] rather than loading the file, so a reset or an import redraws
 * the numbers without a second read of the disk.
 */
@Composable
fun TasteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val model by SonoraBackend.tasteState.collectAsState()
    val settings by SonoraBackend.settings.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }

    // Export writes bytes the user chose the name and place for; import reads one back. Both go
    // through SAF so the document is theirs and needs no storage permission.
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { SonoraBackend.exportTaste(context, it) } }

    val import = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            SonoraBackend.importTaste(context, it)
        }
    }

    BackHandler { onBack() }

    // Computed off the main thread: the summary sorts the whole model, which is fine at a few
    // hundred tracks and not at twenty thousand. The first frame draws the counts from an empty
    // summary rather than blocking the composition on a sort.
    var summary by remember { mutableStateOf(TasteSummary.of(TasteModel(), limit = 12)) }
    LaunchedEffect(model) {
        summary = withContext(Dispatchers.Default) { TasteSummary.of(model, limit = 12) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = listBottomPadding(withMiniPlayer = false)),
    ) {
        Row(modifier = Modifier.padding(start = PAGE_GUTTER, top = 8.dp)) {
            PageBackButton(onClick = onBack)
        }

        Text(
            text = "Taste",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        )

        SettingsGroup(
            header = "Learned on this device",
            footer = "Never leaves the phone unless you export it. Nothing here is sent anywhere.",
        ) {
            SettingsRow(icon = Icons.Rounded.AutoAwesome, title = "Tracks learned", value = "${summary.trackCount}")
            RowDivider()
            SettingsRow(title = "Plays recorded", value = "${summary.totalPlays}")
            RowDivider()
            SettingsRow(title = "Likes folded in", value = "${summary.likedCount}")
            RowDivider()
            SettingsRow(title = "Last updated", value = summary.updatedAt.takeIf { it > 0L }?.let(::stamp) ?: "never")
        }

        SettingsGroup(header = "Autoplay") {
            SettingsRow(
                title = "Autoplay",
                subtitle = "Keep the queue going from what you listen to.",
                trailing = {
                    SettingSwitch(settings.autoplay) { SonoraBackend.setAutoplay(context, it) }
                },
                onClick = { SonoraBackend.setAutoplay(context, !settings.autoplay) },
            )
            RowDivider()
            SettingsRow(
                title = "Pause learning",
                subtitle = "Keep playing, but record nothing.",
                trailing = {
                    SettingSwitch(settings.pauseLearning) { SonoraBackend.setPauseLearning(context, it) }
                },
                onClick = { SonoraBackend.setPauseLearning(context, !settings.pauseLearning) },
            )
        }

        if (summary.artists.isNotEmpty()) {
            TasteList(header = "Top artists", rows = summary.artists)
        }
        if (summary.transitions.isNotEmpty()) {
            TasteList(header = "Strongest transitions", rows = summary.transitions)
        }
        if (summary.tags.isNotEmpty()) {
            TasteList(header = "Top tags", rows = summary.tags)
        }
        if (summary.skipped.isNotEmpty()) {
            TasteList(header = "Most skipped", rows = summary.skipped)
        }

        SettingsGroup(
            header = "Backup",
            footer = "Exports taste.json, including its schema version.",
        ) {
            SettingsActions {
                OutlinedButton(onClick = { export.launch("taste.json") }) { Text("Export") }
                TextButton(onClick = { import.launch(arrayOf("application/json")) }) { Text("Import") }
            }
        }

        SettingsGroup(header = "Reset") {
            SettingsActionBox {
                TextButton(onClick = { confirmReset = true }) {
                    Text("Reset taste data", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset taste data?") },
            text = { Text("This erases everything learned about what you listen to. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    SonoraBackend.resetTaste(context)
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TasteList(header: String, rows: List<dev.sonora.backend.taste.TasteRow>) {
    SettingsGroup(header = header) {
        rows.forEachIndexed { index, row ->
            if (index > 0) RowDivider()
            SettingsRow(title = row.name, value = row.value)
        }
    }
}

private fun stamp(at: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))

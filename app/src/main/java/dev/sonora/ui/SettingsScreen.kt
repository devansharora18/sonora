package dev.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sonora.backend.MusicDirectory
import dev.sonora.backend.SonoraBackend
import dev.sonora.ui.theme.accentText

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings by SonoraBackend.settings.collectAsState()

    // Resolved once per visit rather than tracked: the folder only changes when the storage
    // permission is granted, which takes a restart of this screen to reflect.
    val downloads = remember(settings.downloadTreeUri) {
        MusicDirectory.resolve(context, settings.downloadTreeUri)
    }

    val shared = remember(settings.shareTreeUri, settings.downloadTreeUri) {
        MusicDirectory.resolve(
            context,
            settings.shareTreeUri ?: settings.downloadTreeUri,
        )
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Persisted so the grant outlives the process; without it the folder is only usable
            // until the app is next started.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SonoraBackend.setDownloadTree(context, uri.toString())
        }
    }

    val pickShareFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SonoraBackend.setShareTree(context, uri.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
        )

        SectionTitle("Library")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {}
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Include this device's music", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Show music from the rest of the device, not only what Sonora " +
                        "downloaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.includeDeviceMusic,
                onCheckedChange = { SonoraBackend.setIncludeDeviceMusic(context, it) },
            )
        }

        SectionTitle("Storage")

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Download folder", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = downloads.directory.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (settings.downloadTreeUri != null) {
                    "Files here belong to you, so they stay if Sonora is uninstalled."
                } else {
                    "Files Sonora creates here are deleted if Sonora is uninstalled. Choose a " +
                        "folder to keep them."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.downloadTreeUri != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }

                if (settings.downloadTreeUri != null) {
                    TextButton(
                        onClick = { SonoraBackend.setDownloadTree(context, null) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("Use default", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (!downloads.shared) {
                // Worth saying out loud: the files are invisible to other apps in this state, which
                // is the opposite of why a shared folder is the default.
                Text(
                    text = "Shared storage isn't writable, so downloads are being kept in " +
                        "Sonora's private storage instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SectionTitle("Sharing")

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Shared folder", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = shared.directory.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Other users can browse and download what is in here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { pickShareFolder.launch(null) }) { Text("Choose folder") }

                if (settings.shareTreeUri != null) {
                    TextButton(
                        onClick = { SonoraBackend.setShareTree(context, null) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("Use downloads", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        SectionTitle("Connection")

        TextButton(
            onClick = { SonoraBackend.disconnect(context) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.accentText,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

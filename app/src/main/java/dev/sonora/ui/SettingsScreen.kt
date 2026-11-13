package dev.sonora.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.MusicDirectory
import dev.sonora.backend.SonoraBackend

/** The installed version, read from the package rather than from a build config field. */
@Composable
private fun rememberVersionName(context: android.content.Context): String = remember {
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }
}

@Composable
fun SettingsScreen(onOpenTaste: () -> Unit = {}) {
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
            .verticalScroll(rememberScrollState())
            .padding(bottom = listBottomPadding(withMiniPlayer = false)),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        )

        SettingsGroup(
            header = "Library",
        ) {
            SettingsRow(
                icon = Icons.Filled.LibraryMusic,
                title = "Include this device's music",
                subtitle = "Show music from the rest of the device, not only what Sonora " +
                    "downloaded.",
                trailing = {
                    SettingSwitch(settings.includeDeviceMusic) {
                        SonoraBackend.setIncludeDeviceMusic(context, it)
                    }
                },
                onClick = {
                    SonoraBackend.setIncludeDeviceMusic(context, !settings.includeDeviceMusic)
                },
            )
        }

        SettingsGroup(
            header = "Taste",
            footer = "Learned on this device from what you play. Never sent anywhere.",
        ) {
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = "Taste profile",
                subtitle = "Your top artists, transitions and skips.",
                onClick = onOpenTaste,
            )

            RowDivider()

            SettingsRow(
                title = "Autoplay",
                subtitle = "Top the queue up from what you listen to.",
                trailing = {
                    SettingSwitch(settings.autoplay) { SonoraBackend.setAutoplay(context, it) }
                },
                onClick = { SonoraBackend.setAutoplay(context, !settings.autoplay) },
            )
        }

        SettingsGroup(
            header = "Storage",
            footer = if (settings.downloadTreeUri != null) {
                "Files here belong to you, so they stay if Sonora is uninstalled."
            } else {
                "Files Sonora creates here are deleted if Sonora is uninstalled. Choose a folder " +
                    "to keep them."
            },
            footerIsWarning = settings.downloadTreeUri == null,
        ) {
            SettingsRow(
                icon = Icons.Filled.Folder,
                title = "Download folder",
                subtitle = downloads.directory.absolutePath,
            )

            RowDivider()

            SettingsActions {
                OutlinedButton(onClick = { pickFolder.launch(null) }) {
                    Text("Choose folder")
                }

                if (settings.downloadTreeUri != null) {
                    TextButton(onClick = { SonoraBackend.setDownloadTree(context, null) }) {
                        Text("Use default")
                    }
                }
            }

            if (!downloads.shared) {
                // Worth saying out loud: the files are invisible to other apps in this state, which
                // is the opposite of why a shared folder is the default.
                Text(
                    text = "Shared storage isn't writable, so downloads are being kept in " +
                        "Sonora's private storage instead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 5,
                    modifier = Modifier.padding(
                        start = ROW_INSET,
                        end = ROW_INSET,
                        bottom = 12.dp,
                    ),
                )
            }
        }

        SettingsGroup(
            header = "Sharing",
            footer = "Other users can browse and download what is in here.",
        ) {
            SettingsRow(
                icon = Icons.Filled.Share,
                title = "Shared folder",
                subtitle = shared.directory.absolutePath,
            )

            RowDivider()

            SettingsActions {
                OutlinedButton(onClick = { pickShareFolder.launch(null) }) {
                    Text("Choose folder")
                }

                if (settings.shareTreeUri != null) {
                    TextButton(onClick = { SonoraBackend.setShareTree(context, null) }) {
                        Text("Use downloads")
                    }
                }
            }
        }

        SettingsGroup(
            header = "About",
            footer = "Sonora is free software under the GNU AGPL v3. It is a file-sharing client, " +
                "not a content host.",
        ) {
            SettingsRow(
                icon = Icons.Filled.Info,
                title = "Version",
                value = rememberVersionName(context),
            )

            RowDivider()

            SettingsRow(title = "Licence", value = "AGPL-3.0")
        }

        SettingsGroup(
            header = "Connection",
            footer = "Sonora keeps its own settings; unlinking only ends the session.",
        ) {
            SettingsActionBox {
                TextButton(onClick = { SonoraBackend.disconnect(context) }) {
                    Text(
                        text = "Disconnect",
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

package io.github.sanitised.st

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    autoCheckEnabled: Boolean,
    onAutoCheckChanged: (Boolean) -> Unit,
    autoOpenBrowserEnabled: Boolean,
    onAutoOpenBrowserChanged: (Boolean) -> Unit,
    channel: UpdateChannel,
    onChannelChanged: (UpdateChannel) -> Unit,
    onCheckNow: () -> Unit,
    isChecking: Boolean,
    showUpdatePrompt: Boolean,
    updateVersionLabel: String,
    updateDetails: String,
    isDownloadingUpdate: Boolean,
    downloadProgressPercent: Int?,
    isUpdateReadyToInstall: Boolean,
    onUpdatePrimary: () -> Unit,
    onUpdateDismiss: () -> Unit,
    onCancelUpdateDownload: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Text(
                    text = "Checks for new app releases on GitHub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showUpdatePrompt) {
                    Spacer(modifier = Modifier.height(16.dp))
                    UpdatePromptCard(
                        visible = true,
                        versionLabel = updateVersionLabel,
                        details = updateDetails,
                        isDownloading = isDownloadingUpdate,
                        downloadProgressPercent = downloadProgressPercent,
                        isReadyToInstall = isUpdateReadyToInstall,
                        onPrimary = onUpdatePrimary,
                        onDismiss = onUpdateDismiss,
                        onCancelDownload = onCancelUpdateDownload
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Automatically Check for Updates",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Checks on app startup, no more than once per day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = autoCheckEnabled,
                        onCheckedChange = onAutoCheckChanged
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Open Browser Automatically",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "When the server is ready, open SillyTavern in your browser for you.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = autoOpenBrowserEnabled,
                        onCheckedChange = onAutoOpenBrowserChanged
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Update Channel",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = channel == UpdateChannel.RELEASE,
                                onClick = { onChannelChanged(UpdateChannel.RELEASE) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = channel == UpdateChannel.RELEASE,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Release",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Stable releases only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = channel == UpdateChannel.PRERELEASE,
                                onClick = { onChannelChanged(UpdateChannel.PRERELEASE) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = channel == UpdateChannel.PRERELEASE,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Pre-release",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Includes beta/rc builds",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onCheckNow,
                    enabled = !isChecking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = if (isChecking) "Checking..." else "Check for Updates Now")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    SettingsScreen(
        onBack = {},
        autoCheckEnabled = false,
        onAutoCheckChanged = {},
        autoOpenBrowserEnabled = false,
        onAutoOpenBrowserChanged = {},
        channel = UpdateChannel.RELEASE,
        onChannelChanged = {},
        onCheckNow = {},
        isChecking = false,
        showUpdatePrompt = true,
        updateVersionLabel = "v0.3.0",
        updateDetails = "Tap Install to download and install.",
        isDownloadingUpdate = false,
        downloadProgressPercent = null,
        isUpdateReadyToInstall = false,
        onUpdatePrimary = {},
        onUpdateDismiss = {},
        onCancelUpdateDownload = {}
    )
}

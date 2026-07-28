package io.github.sanitised.st

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    isBatteryUnrestricted: Boolean,
    onOpenBatterySettings: () -> Unit,
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
            SecondaryTopAppBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_auto_open_title),
                    subtitle = stringResource(R.string.settings_auto_open_subtitle),
                    checked = autoOpenBrowserEnabled,
                    onCheckedChange = onAutoOpenBrowserChanged
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_theme_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                ThemeModeSelector(
                    selectedMode = themeMode,
                    onThemeModeChanged = onThemeModeChanged
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.settings_battery_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_battery_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val batteryStatusColor = if (isBatteryUnrestricted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                val batteryStatusSymbol = if (isBatteryUnrestricted) "\u2713" else "!"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = batteryStatusSymbol,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = batteryStatusColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBatteryUnrestricted) {
                                stringResource(R.string.settings_battery_status_unrestricted)
                            } else {
                                stringResource(R.string.settings_battery_status_optimized)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = batteryStatusColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isBatteryUnrestricted) {
                            stringResource(R.string.settings_battery_button_review)
                        } else {
                            stringResource(R.string.settings_battery_button_enable)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_updates_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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
                Spacer(modifier = Modifier.height(16.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_auto_check_title),
                    subtitle = stringResource(R.string.settings_auto_check_subtitle),
                    checked = autoCheckEnabled,
                    onCheckedChange = onAutoCheckChanged
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_update_channel_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                    SettingsRadioOptionRow(
                        selected = channel == UpdateChannel.RELEASE,
                        title = stringResource(R.string.settings_channel_release_label),
                        subtitle = stringResource(R.string.settings_channel_release_subtitle),
                        onClick = { onChannelChanged(UpdateChannel.RELEASE) }
                    )
                    SettingsRadioOptionRow(
                        selected = channel == UpdateChannel.PRERELEASE,
                        title = stringResource(R.string.settings_channel_prerelease_label),
                        subtitle = stringResource(R.string.settings_channel_prerelease_subtitle),
                        onClick = { onChannelChanged(UpdateChannel.PRERELEASE) }
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onCheckNow,
                    enabled = !isChecking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isChecking) {
                            stringResource(R.string.settings_checking)
                        } else {
                            stringResource(R.string.settings_check_now)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ThemeModeSelector(
    selectedMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .selectableGroup()
        ) {
            ThemeModeSegment(
                label = stringResource(R.string.settings_theme_auto_label),
                selected = selectedMode == ThemeMode.AUTO,
                onClick = { onThemeModeChanged(ThemeMode.AUTO) },
                modifier = Modifier.weight(1f)
            )
            ThemeModeSegment(
                label = stringResource(R.string.settings_theme_light_label),
                selected = selectedMode == ThemeMode.LIGHT,
                onClick = { onThemeModeChanged(ThemeMode.LIGHT) },
                modifier = Modifier.weight(1f)
            )
            ThemeModeSegment(
                label = stringResource(R.string.settings_theme_dark_label),
                selected = selectedMode == ThemeMode.DARK,
                onClick = { onThemeModeChanged(ThemeMode.DARK) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ThemeModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(2.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun SettingsRadioOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        themeMode = ThemeMode.AUTO,
        onThemeModeChanged = {},
        isBatteryUnrestricted = false,
        onOpenBatterySettings = {},
        channel = UpdateChannel.RELEASE,
        onChannelChanged = {},
        onCheckNow = {},
        isChecking = false,
        showUpdatePrompt = true,
        updateVersionLabel = "v0.5.0-beta",
        updateDetails = "Tap Install to download and install.",
        isDownloadingUpdate = false,
        downloadProgressPercent = null,
        isUpdateReadyToInstall = false,
        onUpdatePrimary = {},
        onUpdateDismiss = {},
        onCancelUpdateDownload = {}
    )
}

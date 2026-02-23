package io.github.sanitised.st

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

@Composable
fun STAndroidApp(
    status: NodeStatus,
    busyMessage: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onShowLogs: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onEditConfig: () -> Unit,
    showNotificationPrompt: Boolean,
    versionLabel: String,
    stLabel: String,
    nodeLabel: String,
    symlinkSupported: Boolean,
    onShowLegal: () -> Unit,
    showAutoCheckOptInPrompt: Boolean,
    onEnableAutoCheck: () -> Unit,
    onLaterAutoCheck: () -> Unit,
    showUpdatePrompt: Boolean,
    updateVersionLabel: String,
    updateDetails: String,
    isDownloadingUpdate: Boolean,
    downloadProgressPercent: Int?,
    isUpdateReadyToInstall: Boolean,
    onUpdatePrimary: () -> Unit,
    onUpdateDismiss: () -> Unit,
    onCancelUpdateDownload: () -> Unit,
    onShowSettings: () -> Unit,
    onShowManageSt: () -> Unit
) {
    MaterialTheme {
        val isBusy = busyMessage.isNotBlank()
        val readyState = remember { mutableStateOf(false) }
        val view = LocalView.current
        val darkTheme = isSystemInDarkTheme()
        val statusBarColor = MaterialTheme.colorScheme.background.toArgb()
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = statusBarColor
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !darkTheme
        }
        LaunchedEffect(status.state, status.port) {
            if (status.state != NodeState.RUNNING) {
                readyState.value = false
                return@LaunchedEffect
            }
            readyState.value = false
            val deadline = System.currentTimeMillis() + 60_000L
            while (status.state == NodeState.RUNNING && !readyState.value && System.currentTimeMillis() < deadline) {
                val ok = withContext(Dispatchers.IO) {
                    probeServer(status.port)
                }
                if (ok) {
                    readyState.value = true
                    break
                }
                delay(1000)
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
            ) {
                // Scrollable area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = "SillyTavern",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "for Android",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "v$versionLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable(onClick = onShowLegal)
                            .padding(vertical = 6.dp, horizontal = 12.dp)
                    ) {
                        Text(
                            text = stLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = nodeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Licenses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    if (!symlinkSupported) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Symlinks are not supported on this device. The app may not work correctly or may lose data on update.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onShowLogs,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Logs")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = onEditConfig,
                            enabled = !isBusy && (status.state == NodeState.STOPPED || status.state == NodeState.ERROR),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Edit Config")
                        }
                    }
                    if (showNotificationPrompt) {
                        Spacer(modifier = Modifier.height(16.dp))
                        NotificationPermissionCard(
                            visible = true,
                            onOpenSettings = onOpenNotificationSettings
                        )
                    }
                    if (showAutoCheckOptInPrompt) {
                        Spacer(modifier = Modifier.height(16.dp))
                        AutoCheckOptInCard(
                            visible = true,
                            onEnable = onEnableAutoCheck,
                            onLater = onLaterAutoCheck
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onShowSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Settings")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = onShowManageSt,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Manage ST")
                        }
                    }
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
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Fixed bottom controls
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = busyMessage.ifBlank { status.message },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onStart,
                        enabled = !isBusy && (status.state == NodeState.STOPPED || status.state == NodeState.ERROR),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "Start")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onStop,
                        enabled = status.state == NodeState.RUNNING || status.state == NodeState.STARTING,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "Stop")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val canOpen = status.state == NodeState.RUNNING && readyState.value
                Button(
                    onClick = onOpen,
                    enabled = canOpen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (status.state == NodeState.RUNNING && !readyState.value) {
                            "Waiting for server..."
                        } else {
                            "Open in Browser"
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

private fun probeServer(port: Int): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
            true
        }
    } catch (_: Exception) {
        false
    }
}

@Preview(showBackground = true)
@Composable
private fun STAndroidAppPreview() {
    STAndroidApp(
        status = NodeStatus(NodeState.STOPPED, "Idle"),
        busyMessage = "",
        onStart = {},
        onStop = {},
        onOpen = {},
        onShowLogs = {},
        onOpenNotificationSettings = {},
        onEditConfig = {},
        showNotificationPrompt = false,
        versionLabel = "0.3.0-dev",
        stLabel = "SillyTavern 1.12.3",
        nodeLabel = "Node v24.13.0",
        symlinkSupported = true,
        onShowLegal = {},
        showAutoCheckOptInPrompt = false,
        onEnableAutoCheck = {},
        onLaterAutoCheck = {},
        showUpdatePrompt = false,
        updateVersionLabel = "",
        updateDetails = "",
        isDownloadingUpdate = false,
        downloadProgressPercent = null,
        isUpdateReadyToInstall = false,
        onUpdatePrimary = {},
        onUpdateDismiss = {},
        onCancelUpdateDownload = {},
        onShowSettings = {},
        onShowManageSt = {}
    )
}

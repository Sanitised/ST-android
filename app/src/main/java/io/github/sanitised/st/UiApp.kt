package io.github.sanitised.st

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
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
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onShowLogs: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onEditConfig: () -> Unit,
    showNotificationPrompt: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    backupStatus: String,
    versionLabel: String,
    stLabel: String,
    nodeLabel: String,
    symlinkSupported: Boolean,
    onShowLegal: () -> Unit
) {
    MaterialTheme {
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
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "SillyTavern for Android")
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable(onClick = onShowLegal)
                        ) {
                            Text(text = "Build $versionLabel")
                            Text(text = stLabel)
                            Text(text = nodeLabel)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Licenses",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        if (!symlinkSupported) {
                            Text(
                                text = "Symlinks are not supported on this device, app might not work correctly or lose your data on update.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Row {
                            Button(onClick = onShowLogs) {
                                Text(text = "Logs")
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = onEditConfig,
                                enabled = status.state == NodeState.STOPPED || status.state == NodeState.ERROR
                            ) {
                                Text(text = "Edit Config")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            Button(
                                onClick = onExport,
                                enabled = status.state == NodeState.STOPPED || status.state == NodeState.ERROR
                            ) {
                                Text(text = "Export Data")
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = onImport,
                                enabled = status.state == NodeState.STOPPED || status.state == NodeState.ERROR
                            ) {
                                Text(text = "Import Data")
                            }
                        }
                        if (backupStatus.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = backupStatus, style = MaterialTheme.typography.bodySmall)
                        }
                        if (showNotificationPrompt) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Notification permission is required to keep the server running in the background. " +
                                    "We only use it for the foreground service and never send notifications outside of it.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onOpenNotificationSettings) {
                                Text(text = "Notification Settings")
                            }
                        }
                    }
                    Text(
                        text = status.message,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onStart,
                            enabled = status.state == NodeState.STOPPED || status.state == NodeState.ERROR,
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
                    Spacer(modifier = Modifier.height(12.dp))
                    val canOpen = status.state == NodeState.RUNNING && readyState.value
                    Button(
                        onClick = onOpen,
                        enabled = canOpen,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val label = if (status.state == NodeState.RUNNING && !readyState.value) {
                            "Waiting for server..."
                        } else {
                            "Open in Browser"
                        }
                        Text(text = label)
                    }
                }
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
        onStart = {},
        onStop = {},
        onOpen = {},
        onShowLogs = {},
        onOpenNotificationSettings = {},
        onEditConfig = {},
        showNotificationPrompt = true,
        onExport = {},
        onImport = {},
        backupStatus = "",
        versionLabel = "0.0.0 (0)",
        stLabel = "SillyTavern 1.0.0",
        nodeLabel = "Node v24.13.0",
        symlinkSupported = true,
        onShowLegal = {}
    )
}

package io.github.sanitised.st

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class BusyOperation { EXPORTING, IMPORTING, INSTALLING, RESETTING, REMOVING_DATA }

class MainActivity : ComponentActivity() {
    private val nodeServiceState = mutableStateOf<NodeService?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NodeService.LocalBinder
            nodeServiceState.value = binder?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nodeServiceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        val versionLabel = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.versionName ?: "?"
        }.getOrElse { "unknown" }
        val bundledInfo = NodePayload(this).readManifestInfo()
        val stLabel = bundledInfo?.let {
            when {
                !it.stVersion.isNullOrBlank() -> "SillyTavern ${it.stVersion}"
                !it.stCommit.isNullOrBlank() -> "SillyTavern ${it.stCommit}"
                else -> "SillyTavern unknown"
            }
        } ?: "SillyTavern unknown"
        val nodeLabel = bundledInfo?.let {
            val nodeValue = when {
                !it.nodeTag.isNullOrBlank() -> it.nodeTag
                !it.nodeVersion.isNullOrBlank() -> it.nodeVersion
                !it.nodeCommit.isNullOrBlank() -> it.nodeCommit
                else -> null
            }
            if (nodeValue.isNullOrBlank()) "Node unknown" else "Node $nodeValue"
        } ?: "Node unknown"
        val symlinkSupported = isSymlinkSupported()
        setContent {
            val statusState = remember { mutableStateOf(NodeStatus(NodeState.STOPPED, "Idle")) }
            val showLogsState = remember { mutableStateOf(false) }
            val showConfigState = remember { mutableStateOf(false) }
            val showLegalState = remember { mutableStateOf(false) }
            val showLicenseState = remember { mutableStateOf<LegalDoc?>(null) }
            val showAdvancedState = remember { mutableStateOf(false) }
            val stdoutState = remember { mutableStateOf("") }
            val stderrState = remember { mutableStateOf("") }
            val serviceState = remember { mutableStateOf("") }
            val backupStatusState = remember { mutableStateOf("") }
            val pendingImportUri = remember { mutableStateOf<Uri?>(null) }
            val showImportConfirm = remember { mutableStateOf(false) }
            val isCustomInstalledState = remember { mutableStateOf(NodePayload(this@MainActivity).isCustomInstalled()) }
            val busyOperationState = remember { mutableStateOf<BusyOperation?>(null) }
            val customStatusState = remember { mutableStateOf("") }
            val showResetConfirm = remember { mutableStateOf(false) }
            val showRemoveDataConfirm = remember { mutableStateOf(false) }
            val removeDataStatusState = remember { mutableStateOf("") }
            val notificationGrantedState = remember { mutableStateOf(isNotificationPermissionGranted()) }
            val lifecycleOwner = LocalLifecycleOwner.current
            val scope = rememberCoroutineScope()
            val busyMessage = when (busyOperationState.value) {
                BusyOperation.EXPORTING -> "Exporting data…"
                BusyOperation.IMPORTING -> "Importing data…"
                BusyOperation.INSTALLING -> "Installing custom ST…"
                BusyOperation.RESETTING -> "Resetting to default…"
                BusyOperation.REMOVING_DATA -> "Removing user data…"
                null -> ""
            }
            val listener = remember {
                object : NodeStatusListener {
                    override fun onStatus(status: NodeStatus) {
                        scope.launch(Dispatchers.Main) {
                            statusState.value = status
                        }
                    }
                }
            }

            val service = nodeServiceState.value
            DisposableEffect(service) {
                if (service != null) {
                    service.registerListener(listener)
                }
                onDispose {
                    service?.unregisterListener(listener)
                }
            }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        notificationGrantedState.value = isNotificationPermissionGranted()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(showLogsState.value) {
                if (!showLogsState.value) return@LaunchedEffect
                val paths = AppPaths(this@MainActivity)
                while (showLogsState.value) {
                    val logsDir = paths.logsDir
                    val stdoutFile = File(logsDir, "node_stdout.log")
                    val stderrFile = File(logsDir, "node_stderr.log")
                    val serviceFile = File(logsDir, "service.log")
                    stdoutState.value = readLogTail(stdoutFile, 16 * 1024)
                    stderrState.value = readLogTail(stderrFile, 16 * 1024)
                    serviceState.value = readLogTail(serviceFile, 16 * 1024)
                    delay(1000)
                }
            }

            val legalDocs = remember {
                listOf(
                    LegalDoc(
                        title = "App license (AGPL-3.0)",
                        assetPath = "legal/sillytavern_AGPL-3.0.txt",
                        description = "Applies to this app and SillyTavern."
                    ),
                    LegalDoc(
                        title = "Node.js license (MIT)",
                        assetPath = "legal/node_MIT.txt",
                        description = "Includes Termux-derived Node patches."
                    ),
                    LegalDoc(
                        title = "AndroidX / Compose / Material / Kotlin license (Apache-2.0)",
                        assetPath = "legal/apache-2.0.txt",
                    ),
                    LegalDoc(
                        title = "SillyTavern Dependencies and licenses (package-lock.json)",
                        assetPath = "legal/sillytavern_package-lock.json"
                    )
                )
            }

            // Launchers must live at the top level, outside any conditional branches
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/gzip")
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                backupStatusState.value = "Exporting..."
                busyOperationState.value = BusyOperation.EXPORTING
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        NodeBackup.exportToUri(this@MainActivity, uri)
                    }
                    val msg = result.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
                    backupStatusState.value = msg
                    busyOperationState.value = null
                    appendServiceLog(msg)
                }
            }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                pendingImportUri.value = uri
                showImportConfirm.value = true
            }
            val customZipLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                busyOperationState.value = BusyOperation.INSTALLING
                customStatusState.value = "Starting…"
                scope.launch {
                    val payload = NodePayload(this@MainActivity)
                    val result = withContext(Dispatchers.IO) {
                        payload.installCustomFromZip(uri) { msg ->
                            scope.launch(Dispatchers.Main) {
                                customStatusState.value = msg
                            }
                        }
                    }
                    isCustomInstalledState.value = payload.isCustomInstalled()
                    busyOperationState.value = null
                    val msg = result.fold(
                        onSuccess = { "Custom ST installed successfully." },
                        onFailure = { "Installation failed: ${it.message ?: "unknown error"}" }
                    )
                    customStatusState.value = msg
                    appendServiceLog(msg)
                }
            }

            // Screen routing
            when {
                showLogsState.value -> {
                    BackHandler { showLogsState.value = false }
                    LogsScreen(
                        onBack = { showLogsState.value = false },
                        stdoutLog = stdoutState.value,
                        stderrLog = stderrState.value,
                        serviceLog = serviceState.value
                    )
                }
                showLicenseState.value != null -> {
                    val doc = showLicenseState.value!!
                    BackHandler { showLicenseState.value = null }
                    LicenseTextScreen(
                        onBack = { showLicenseState.value = null },
                        doc = doc
                    )
                }
                showLegalState.value -> {
                    BackHandler { showLegalState.value = false }
                    LegalScreen(
                        onBack = { showLegalState.value = false },
                        onOpenUrl = { url -> openUrl(url) },
                        legalDocs = legalDocs,
                        onOpenLicense = { doc -> showLicenseState.value = doc }
                    )
                }
                showConfigState.value -> {
                    BackHandler { showConfigState.value = false }
                    ConfigScreen(
                        onBack = { showConfigState.value = false },
                        onOpenDocs = { openConfigDocs() },
                        canEdit = statusState.value.state == NodeState.STOPPED || statusState.value.state == NodeState.ERROR,
                        configFile = AppPaths(this).configFile
                    )
                }
                showAdvancedState.value -> {
                    BackHandler { showAdvancedState.value = false }
                    AdvancedScreen(
                        onBack = { showAdvancedState.value = false },
                        isCustomInstalled = isCustomInstalledState.value,
                        customStatus = customStatusState.value,
                        serverRunning = statusState.value.state == NodeState.RUNNING ||
                            statusState.value.state == NodeState.STARTING,
                        busyMessage = busyMessage,
                        onLoadCustomZip = {
                            customZipLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream"
                                )
                            )
                        },
                        onResetToDefault = { showResetConfirm.value = true },
                        onRemoveUserData = { showRemoveDataConfirm.value = true },
                        removeDataStatus = removeDataStatusState.value
                    )
                }
                else -> {
                    STAndroidApp(
                        status = statusState.value,
                        busyMessage = busyMessage,
                        onStart = { startNode() },
                        onStop = { stopNode() },
                        onOpen = { openNodeUi(statusState.value.port) },
                        onShowLogs = { showLogsState.value = true },
                        onOpenNotificationSettings = { openNotificationSettings() },
                        onEditConfig = { showConfigState.value = true },
                        showNotificationPrompt = !notificationGrantedState.value,
                        onExport = {
                            val stamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            exportLauncher.launch("sillytavern-backup-$stamp.tar.gz")
                        },
                        onImport = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/gzip",
                                    "application/x-gzip",
                                    "application/octet-stream",
                                    "application/x-tar"
                                )
                            )
                        },
                        backupStatus = backupStatusState.value,
                        versionLabel = versionLabel,
                        stLabel = if (isCustomInstalledState.value) "SillyTavern (custom version)" else stLabel,
                        nodeLabel = nodeLabel,
                        symlinkSupported = symlinkSupported,
                        onShowLegal = { showLegalState.value = true },
                        onShowAdvanced = { showAdvancedState.value = true }
                    )
                }
            }

            // Dialogs overlay whichever screen is active
            if (showResetConfirm.value) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showResetConfirm.value = false },
                    title = { Text(text = "Reset to default?") },
                    text = {
                        Text(
                            text = "This will reinstall the SillyTavern version bundled " +
                                "with the app. Your data (chats, characters, settings) " +
                                "will not be affected."
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            showResetConfirm.value = false
                            busyOperationState.value = BusyOperation.RESETTING
                            customStatusState.value = "Resetting…"
                            scope.launch {
                                val payload = NodePayload(this@MainActivity)
                                val result = withContext(Dispatchers.IO) {
                                    payload.resetToDefault()
                                }
                                isCustomInstalledState.value = payload.isCustomInstalled()
                                busyOperationState.value = null
                                val msg = result.fold(
                                    onSuccess = { "Reset to default complete." },
                                    onFailure = { "Reset failed: ${it.message ?: "unknown error"}" }
                                )
                                customStatusState.value = msg
                                appendServiceLog(msg)
                            }
                        }) {
                            Text(text = "Reset")
                        }
                    },
                    dismissButton = {
                        Button(onClick = { showResetConfirm.value = false }) {
                            Text(text = "Cancel")
                        }
                    }
                )
            }
            if (showRemoveDataConfirm.value) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showRemoveDataConfirm.value = false },
                    title = { Text(text = "Remove all user data?") },
                    text = {
                        Text(
                            text = "This will permanently delete ALL your chats, characters, " +
                                "presets, worlds, settings, and any other data stored by the app. " +
                                "This cannot be undone. Export a backup first if you want to keep anything."
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            showRemoveDataConfirm.value = false
                            removeDataStatusState.value = "Removing data…"
                            busyOperationState.value = BusyOperation.REMOVING_DATA
                            scope.launch {
                                val paths = AppPaths(this@MainActivity)
                                withContext(Dispatchers.IO) {
                                    paths.configDir.deleteRecursively()
                                    paths.dataDir.deleteRecursively()
                                }
                                val msg = "User data removed."
                                removeDataStatusState.value = msg
                                busyOperationState.value = null
                                appendServiceLog(msg)
                            }
                        }) {
                            Text(text = "Remove")
                        }
                    },
                    dismissButton = {
                        Button(onClick = { showRemoveDataConfirm.value = false }) {
                            Text(text = "Cancel")
                        }
                    }
                )
            }
            if (showImportConfirm.value) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showImportConfirm.value = false },
                    title = { Text(text = "Import backup?") },
                    text = {
                        Text(
                            text = "YOUR EXISTING DATA WILL BE OVERWRITTEN. " +
                                "Make sure the server is stopped before importing."
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val uri = pendingImportUri.value
                            showImportConfirm.value = false
                            pendingImportUri.value = null
                            if (uri == null) return@Button
                            backupStatusState.value = "Importing..."
                            busyOperationState.value = BusyOperation.IMPORTING
                            scope.launch {
                                val importResult = withContext(Dispatchers.IO) {
                                    NodeBackup.importFromUri(this@MainActivity, uri)
                                }
                                val postInstallResult = if (importResult.isSuccess) {
                                    val serviceResult = nodeServiceState.value?.runPostInstallNow()
                                    serviceResult ?: Result.failure(IllegalStateException("Service not available"))
                                } else {
                                    Result.success(Unit)
                                }
                                val msg = when {
                                    importResult.isFailure ->
                                        "Import failed: ${importResult.exceptionOrNull()?.message ?: "unknown error"}"
                                    postInstallResult.isFailure ->
                                        "Import complete, post-install failed: ${postInstallResult.exceptionOrNull()?.message ?: "unknown error"}"
                                    else -> "Import complete"
                                }
                                backupStatusState.value = msg
                                busyOperationState.value = null
                                appendServiceLog(msg)
                            }
                        }) {
                            Text(text = "Import")
                        }
                    },
                    dismissButton = {
                        Button(onClick = {
                            showImportConfirm.value = false
                            pendingImportUri.value = null
                        }) {
                            Text(text = "Cancel")
                        }
                    }
                )
            }
        }
    }

    private suspend fun appendServiceLog(message: String) {
        withContext(Dispatchers.IO) {
            val logsDir = AppPaths(this@MainActivity).logsDir
            if (!logsDir.exists()) logsDir.mkdirs()
            val logFile = File(logsDir, "service.log")
            logFile.appendText("${System.currentTimeMillis()}: $message\n", Charsets.UTF_8)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, NodeService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
    }

    private fun startNode() {
        maybeRequestNotificationPermission()
        val port = readConfiguredPort()
        val intent = Intent(this, NodeService::class.java).apply {
            action = NodeService.ACTION_START
            putExtra(NodeService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopNode() {
        val intent = Intent(this, NodeService::class.java).apply { action = NodeService.ACTION_STOP }
        startService(intent)
    }

    private fun openNodeUi(port: Int) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port/"))
        startActivity(intent)
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    private fun openConfigDocs() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.sillytavern.app/administration/config-yaml/"))
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun readConfiguredPort(): Int {
        val configFile = AppPaths(this).configFile
        if (!configFile.exists()) return DEFAULT_PORT
        return try {
            val port = configFile.useLines { lines ->
                val regex = Regex("^port\\s*:\\s*(\\d+)\\s*$")
                lines.map { it.substringBefore("#") }
                    .mapNotNull { regex.find(it)?.groupValues?.getOrNull(1) }
                    .firstOrNull()
                    ?.toIntOrNull()
            }
            if (port != null && port in 1..65535) port else DEFAULT_PORT
        } catch (_: Exception) {
            DEFAULT_PORT
        }
    }

    private fun isNotificationPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val permission = Manifest.permission.POST_NOTIFICATIONS
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(permission)
        }
    }

    private fun isSymlinkSupported(): Boolean {
        val dir = File(cacheDir, "symlink_check")
        val target = File(dir, "target")
        val link = File(dir, "link")
        return try {
            dir.mkdirs()
            if (target.exists()) target.delete()
            if (link.exists()) link.delete()
            target.writeText("x")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            Files.isSymbolicLink(link.toPath())
        } catch (_: Exception) {
            false
        } finally {
            try { link.delete() } catch (_: Exception) { }
            try { target.delete() } catch (_: Exception) { }
            try { dir.delete() } catch (_: Exception) { }
        }
    }
}

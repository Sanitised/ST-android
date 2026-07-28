package io.github.sanitised.st

import android.Manifest
import android.graphics.Color
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.yaml.snakeyaml.Yaml

private sealed interface AppScreen {
    object Home : AppScreen
    object Logs : AppScreen
    object Config : AppScreen
    object Legal : AppScreen
    object Settings : AppScreen
    object ManageSt : AppScreen
    data class License(val doc: LegalDoc) : AppScreen
}

private sealed interface PendingDialog {
    object ResetToDefault : PendingDialog
    object RemoveUserData : PendingDialog
    data class ConfirmImport(val uri: Uri) : PendingDialog
}

private val LightAppColorScheme = lightColorScheme()
private val DarkAppColorScheme = darkColorScheme()

private fun ThemeMode.shouldUseDarkTheme(systemInDarkTheme: Boolean): Boolean {
    return when (this) {
        ThemeMode.AUTO -> systemInDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

private fun appScreenStateSaver(legalDocs: List<LegalDoc>): Saver<AppScreen, String> {
    return Saver(
        save = { screen ->
            when (screen) {
                AppScreen.Home -> "home"
                AppScreen.Logs -> "logs"
                AppScreen.Config -> "config"
                AppScreen.Legal -> "legal"
                AppScreen.Settings -> "settings"
                AppScreen.ManageSt -> "manage-st"
                is AppScreen.License -> "license:${screen.doc.assetPath}"
            }
        },
        restore = { key ->
            when {
                key == "home" -> AppScreen.Home
                key == "logs" -> AppScreen.Logs
                key == "config" -> AppScreen.Config
                key == "legal" -> AppScreen.Legal
                key == "settings" -> AppScreen.Settings
                key == "manage-st" -> AppScreen.ManageSt
                key.startsWith("license:") -> {
                    val assetPath = key.removePrefix("license:")
                    val doc = legalDocs.firstOrNull { it.assetPath == assetPath }
                    if (doc != null) AppScreen.License(doc) else AppScreen.Legal
                }
                else -> AppScreen.Home
            }
        }
    )
}

private fun pendingDialogStateSaver(): Saver<PendingDialog?, String> {
    return Saver(
        save = { dialog ->
            when (dialog) {
                null -> "none"
                PendingDialog.ResetToDefault -> "reset"
                PendingDialog.RemoveUserData -> "remove-data"
                is PendingDialog.ConfirmImport -> "import:${dialog.uri}"
            }
        },
        restore = { key ->
            when {
                key == "none" -> null
                key == "reset" -> PendingDialog.ResetToDefault
                key == "remove-data" -> PendingDialog.RemoveUserData
                key.startsWith("import:") -> PendingDialog.ConfirmImport(
                    Uri.parse(key.removePrefix("import:"))
                )
                else -> null
            }
        }
    )
}

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
        enableEdgeToEdge()
        val versionLabel = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.versionName ?: getString(R.string.unknown_short)
        }.getOrElse { getString(R.string.unknown) }
        val bundledInfo = NodePayload(this).readManifestInfo()
        val stLabel = bundledInfo?.let {
            when {
                !it.stVersion.isNullOrBlank() -> getString(R.string.sillytavern_label, it.stVersion)
                !it.stCommit.isNullOrBlank() -> getString(R.string.sillytavern_label, it.stCommit)
                else -> getString(R.string.sillytavern_unknown)
            }
        } ?: getString(R.string.sillytavern_unknown)
        val nodeLabel = bundledInfo?.let {
            val nodeValue = when {
                !it.nodeTag.isNullOrBlank() -> it.nodeTag
                !it.nodeVersion.isNullOrBlank() -> it.nodeVersion
                !it.nodeCommit.isNullOrBlank() -> it.nodeCommit
                else -> null
            }
            if (nodeValue.isNullOrBlank()) getString(R.string.node_unknown) else getString(R.string.node_label, nodeValue)
        } ?: getString(R.string.node_unknown)
        val symlinkSupported = isSymlinkSupported()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val legalDocs = remember {
                listOf(
                    LegalDoc(
                        title = getString(R.string.legal_doc_app_license_title),
                        assetPath = "legal/sillytavern_AGPL-3.0.txt",
                        description = getString(R.string.legal_doc_app_license_description)
                    ),
                    LegalDoc(
                        title = getString(R.string.legal_doc_node_license_title),
                        assetPath = "legal/node_MIT.txt",
                        description = getString(R.string.legal_doc_node_license_description)
                    ),
                    LegalDoc(
                        title = getString(R.string.legal_doc_android_license_title),
                        assetPath = "legal/apache-2.0.txt",
                    ),
                    LegalDoc(
                        title = getString(R.string.legal_doc_st_dependencies_title),
                        assetPath = "legal/sillytavern_package-lock.json"
                    )
                )
            }
            val statusState = remember { mutableStateOf(NodeStatus(NodeState.STOPPED, "Idle")) }
            val currentScreen = rememberSaveable(stateSaver = appScreenStateSaver(legalDocs)) {
                mutableStateOf<AppScreen>(AppScreen.Home)
            }
            val autoOpenBrowserTriggeredForCurrentRun = rememberSaveable { mutableStateOf(false) }
            val stdoutState = remember { mutableStateOf("") }
            val stderrState = remember { mutableStateOf("") }
            val serviceState = remember { mutableStateOf("") }
            val pendingDialogState = rememberSaveable(stateSaver = pendingDialogStateSaver()) {
                mutableStateOf<PendingDialog?>(null)
            }
            val notificationGrantedState = remember { mutableStateOf(isNotificationPermissionGranted()) }
            val notificationAutoPromptAttempted = rememberSaveable { mutableStateOf(false) }
            val batteryUnrestrictedState = remember { mutableStateOf(isBatteryUnrestricted()) }
            val lifecycleOwner = LocalLifecycleOwner.current
            val scope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }
            val listener = remember {
                object : NodeStatusListener {
                    override fun onStatus(status: NodeStatus) {
                        scope.launch(Dispatchers.Main) {
                            val previousState = statusState.value.state
                            if (previousState == NodeState.RUNNING && status.state != NodeState.RUNNING) {
                                autoOpenBrowserTriggeredForCurrentRun.value = false
                            }
                            statusState.value = status
                        }
                    }
                }
            }

            val service = nodeServiceState.value
            DisposableEffect(service) {
                viewModel.nodeService = service
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
                        batteryUnrestrictedState.value = isBatteryUnrestricted()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(currentScreen.value) {
                if (currentScreen.value !is AppScreen.Logs) return@LaunchedEffect
                val paths = AppPaths(this@MainActivity)
                while (currentScreen.value is AppScreen.Logs) {
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
            LaunchedEffect(Unit) {
                viewModel.maybeAutoCheckForUpdates()
            }
            LaunchedEffect(viewModel) {
                viewModel.snackbarMessages.collectLatest { message ->
                    snackbarHostState.showSnackbar(message)
                }
            }
            LaunchedEffect(notificationGrantedState.value, notificationAutoPromptAttempted.value) {
                if (!notificationGrantedState.value && !notificationAutoPromptAttempted.value) {
                    notificationAutoPromptAttempted.value = true
                    maybeRequestNotificationPermission()
                }
            }
            val showAutoCheckOptInPrompt = viewModel.shouldShowAutoCheckOptInPrompt()
            val showBatteryPrompt = viewModel.shouldShowBatteryPrompt(
                isBatteryUnrestricted = batteryUnrestrictedState.value
            )
            val showUpdatePrompt = viewModel.shouldShowUpdatePrompt()
            val isUpdateReadyToInstall = viewModel.isAvailableUpdateDownloaded()
            val systemInDarkTheme = isSystemInDarkTheme()
            val themeMode by viewModel.themeMode
            val useDarkTheme = themeMode.shouldUseDarkTheme(systemInDarkTheme)
            SideEffect {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !useDarkTheme
                    isAppearanceLightNavigationBars = !useDarkTheme
                }
            }

            // Launchers must live at the top level, outside any conditional branches
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/gzip")
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                viewModel.export(uri)
            }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                pendingDialogState.value = PendingDialog.ConfirmImport(uri)
            }
            val customZipLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                viewModel.installCustomZip(uri)
            }
            val triggerExport: () -> Unit = {
                val stamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                exportLauncher.launch("sillytavern-backup-$stamp.tar.gz")
            }
            val triggerImport: () -> Unit = {
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
            }

            MaterialTheme(
                colorScheme = if (useDarkTheme) {
                    DarkAppColorScheme
                } else {
                    LightAppColorScheme
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (val screen = currentScreen.value) {
                    AppScreen.Logs -> {
                        BackHandler { currentScreen.value = AppScreen.Home }
                        LogsScreen(
                            onBack = { currentScreen.value = AppScreen.Home },
                            stdoutLog = stdoutState.value,
                            stderrLog = stderrState.value,
                            serviceLog = serviceState.value
                        )
                    }

                    is AppScreen.License -> {
                        BackHandler { currentScreen.value = AppScreen.Legal }
                        LicenseTextScreen(
                            onBack = { currentScreen.value = AppScreen.Legal },
                            doc = screen.doc
                        )
                    }

                    AppScreen.Legal -> {
                        BackHandler { currentScreen.value = AppScreen.Home }
                        LegalScreen(
                            onBack = { currentScreen.value = AppScreen.Home },
                            onOpenUrl = { url -> openUrl(url) },
                            legalDocs = legalDocs,
                            onOpenLicense = { doc -> currentScreen.value = AppScreen.License(doc) }
                        )
                    }

                    AppScreen.Config -> {
                        ConfigScreen(
                            onBack = { currentScreen.value = AppScreen.Home },
                            onOpenDocs = { openConfigDocs() },
                            canEdit = statusState.value.state == NodeState.STOPPED || statusState.value.state == NodeState.ERROR,
                            configFile = AppPaths(this@MainActivity).configFile,
                            onShowMessage = { message -> viewModel.showTransientMessage(message) }
                        )
                    }

                    AppScreen.Settings -> {
                        BackHandler { currentScreen.value = AppScreen.Home }
                        SettingsScreen(
                            onBack = { currentScreen.value = AppScreen.Home },
                            autoCheckEnabled = viewModel.autoCheckForUpdates.value,
                            onAutoCheckChanged = { enabled -> viewModel.setAutoCheckForUpdates(enabled) },
                            autoOpenBrowserEnabled = viewModel.autoOpenBrowserWhenReady.value,
                            onAutoOpenBrowserChanged = { enabled -> viewModel.setAutoOpenBrowserWhenReady(enabled) },
                            themeMode = themeMode,
                            onThemeModeChanged = { mode -> viewModel.setThemeMode(mode) },
                            isBatteryUnrestricted = batteryUnrestrictedState.value,
                            onOpenBatterySettings = { openBatteryOptimizationSettings() },
                            channel = viewModel.updateChannel.value,
                            onChannelChanged = { channel -> viewModel.setUpdateChannel(channel) },
                            onCheckNow = { viewModel.checkForUpdates("manual") },
                            isChecking = viewModel.isCheckingForUpdates.value,
                            showUpdatePrompt = showUpdatePrompt,
                            updateVersionLabel = viewModel.availableUpdateVersionLabel(),
                            updateDetails = viewModel.updateBannerMessage.value,
                            isDownloadingUpdate = viewModel.isDownloadingUpdate.value,
                            downloadProgressPercent = viewModel.downloadProgressPercent.value,
                            isUpdateReadyToInstall = isUpdateReadyToInstall,
                            onUpdatePrimary = {
                                if (isUpdateReadyToInstall) {
                                    viewModel.installDownloadedUpdate(this@MainActivity)
                                } else {
                                    viewModel.startAvailableUpdateDownload()
                                }
                            },
                            onUpdateDismiss = { viewModel.dismissAvailableUpdatePrompt() },
                            onCancelUpdateDownload = { viewModel.cancelUpdateDownload() }
                        )
                    }

                    AppScreen.ManageSt -> {
                        BackHandler { currentScreen.value = AppScreen.Home }
                        ManageStScreen(
                            onBack = { currentScreen.value = AppScreen.Home },
                            isCustomInstalled = viewModel.isCustomInstalled.value,
                            customInstalledLabel = viewModel.customInstallLabel.value,
                            serverRunning = statusState.value.state == NodeState.RUNNING ||
                                    statusState.value.state == NodeState.STARTING,
                            busyMessage = viewModel.busyMessage,
                            onExport = triggerExport,
                            onImport = triggerImport,
                            customRepoInput = viewModel.customRepoInput.value,
                            onCustomRepoInputChanged = { viewModel.setCustomRepoInput(it) },
                            onLoadRepoRefs = { viewModel.loadCustomRepoRefs() },
                            isLoadingRepoRefs = viewModel.isLoadingCustomRefs.value,
                            customRepoValidationMessage = viewModel.customRepoValidationMessage.value,
                            featuredRefs = viewModel.customFeaturedRefs.value,
                            allRefs = viewModel.customAllRefs.value,
                            selectedRefKey = viewModel.selectedCustomRefKey.value,
                            onSelectRepoRef = { key -> viewModel.selectCustomRepoRef(key) },
                            onDownloadAndInstallRef = { viewModel.startCustomRepoInstall() },
                            customInstallValidationMessage = viewModel.customInstallValidationMessage.value,
                            showBackupOperationCard = viewModel.backupOperationCard.value.visible,
                            backupOperationTitle = viewModel.backupOperationCard.value.title,
                            backupOperationDetails = viewModel.backupOperationCard.value.details,
                            backupOperationProgressPercent = viewModel.backupOperationCard.value.progressPercent,
                            backupOperationAnchor = viewModel.backupOperationCardAnchor.value,
                            showCustomOperationCard = viewModel.customOperationCard.value.visible,
                            customOperationTitle = viewModel.customOperationCard.value.title,
                            customOperationDetails = viewModel.customOperationCard.value.details,
                            customOperationProgressPercent = viewModel.customOperationCard.value.progressPercent,
                            customOperationCancelable = viewModel.customOperationCard.value.cancelable,
                            customOperationAnchor = viewModel.customOperationCardAnchor.value,
                            onCancelCustomOperation = { viewModel.cancelCustomSourceDownload() },
                            onLoadCustomZip = {
                                customZipLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream"
                                    )
                                )
                            },
                            onResetToDefault = { pendingDialogState.value = PendingDialog.ResetToDefault },
                            externalFileAccessEnabled = viewModel.externalFileAccessEnabled.value,
                            onExternalFileAccessChanged = { enabled ->
                                viewModel.setExternalFileAccessEnabled(enabled)
                            },
                            onOpenExternalViewer = {
                                if (!openExternalFileViewer()) {
                                    viewModel.showTransientMessage(
                                        getString(R.string.external_file_access_viewer_failed)
                                    )
                                }
                            },
                            onRemoveUserData = { pendingDialogState.value = PendingDialog.RemoveUserData }
                        )
                    }

                    AppScreen.Home -> {
                        STAndroidApp(
                            status = statusState.value,
                            busyMessage = viewModel.busyMessage,
                            onStart = { startNode() },
                            onStop = { stopNode() },
                            onOpen = { openNodeUi(statusState.value.port) },
                            autoOpenBrowserWhenReady = viewModel.autoOpenBrowserWhenReady.value,
                            autoOpenBrowserTriggeredForCurrentRun = autoOpenBrowserTriggeredForCurrentRun.value,
                            onAutoOpenBrowserTriggered = { autoOpenBrowserTriggeredForCurrentRun.value = true },
                            onShowLogs = { currentScreen.value = AppScreen.Logs },
                            onOpenNotificationSettings = { openNotificationSettings() },
                            onOpenBatterySettings = { openBatteryOptimizationSettings() },
                            onEditConfig = { currentScreen.value = AppScreen.Config },
                            showNotificationPrompt = !notificationGrantedState.value,
                            showBatteryPrompt = showBatteryPrompt,
                            versionLabel = versionLabel,
                            stLabel = if (viewModel.isCustomInstalled.value) {
                                val customLabel = viewModel.customInstallLabel.value
                                if (customLabel.isNullOrBlank()) {
                                    getString(R.string.sillytavern_custom_version)
                                } else {
                                    getString(R.string.sillytavern_custom_with_label, customLabel)
                                }
                            } else stLabel,
                            nodeLabel = nodeLabel,
                            symlinkSupported = symlinkSupported,
                            onShowLegal = { currentScreen.value = AppScreen.Legal },
                            showAutoCheckOptInPrompt = showAutoCheckOptInPrompt,
                            onEnableAutoCheck = { viewModel.acceptAutoCheckOptInPrompt() },
                            onLaterAutoCheck = { viewModel.dismissAutoCheckOptInPrompt() },
                            onDismissBatteryPrompt = { viewModel.dismissBatteryPrompt() },
                            showUpdatePrompt = showUpdatePrompt,
                            updateVersionLabel = viewModel.availableUpdateVersionLabel(),
                            updateDetails = viewModel.updateBannerMessage.value,
                            isDownloadingUpdate = viewModel.isDownloadingUpdate.value,
                            downloadProgressPercent = viewModel.downloadProgressPercent.value,
                            isUpdateReadyToInstall = isUpdateReadyToInstall,
                            onUpdatePrimary = {
                                if (isUpdateReadyToInstall) {
                                    viewModel.installDownloadedUpdate(this@MainActivity)
                                } else {
                                    viewModel.startAvailableUpdateDownload()
                                }
                            },
                            onUpdateDismiss = { viewModel.dismissAvailableUpdatePrompt() },
                            onCancelUpdateDownload = { viewModel.cancelUpdateDownload() },
                            showBackupOperationCard = viewModel.backupOperationCard.value.visible,
                            backupOperationTitle = viewModel.backupOperationCard.value.title,
                            backupOperationDetails = viewModel.backupOperationCard.value.details,
                            backupOperationProgressPercent = viewModel.backupOperationCard.value.progressPercent,
                            showCustomOperationCard = viewModel.customOperationCard.value.visible,
                            customOperationTitle = viewModel.customOperationCard.value.title,
                            customOperationDetails = viewModel.customOperationCard.value.details,
                            customOperationProgressPercent = viewModel.customOperationCard.value.progressPercent,
                            customOperationCancelable = viewModel.customOperationCard.value.cancelable,
                            onCancelCustomOperation = { viewModel.cancelCustomSourceDownload() },
                            onShowSettings = { currentScreen.value = AppScreen.Settings },
                            onShowManageSt = { currentScreen.value = AppScreen.ManageSt }
                        )
                    }
                }

                    when (val dialog = pendingDialogState.value) {
                        PendingDialog.ResetToDefault -> {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { pendingDialogState.value = null },
                                title = { Text(text = stringResource(R.string.dialog_reset_title)) },
                                text = {
                                    Text(
                                        text = stringResource(R.string.dialog_reset_body)
                                    )
                                },
                                confirmButton = {
                                    Button(onClick = {
                                        pendingDialogState.value = null
                                        viewModel.resetToDefault()
                                    }) {
                                        Text(text = stringResource(R.string.reset))
                                    }
                                },
                                dismissButton = {
                                    Button(onClick = { pendingDialogState.value = null }) {
                                        Text(text = stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }

                        PendingDialog.RemoveUserData -> {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { pendingDialogState.value = null },
                                title = { Text(text = stringResource(R.string.dialog_remove_data_title)) },
                                text = {
                                    Text(
                                        text = stringResource(R.string.dialog_remove_data_body)
                                    )
                                },
                                confirmButton = {
                                    Button(onClick = {
                                        pendingDialogState.value = null
                                        viewModel.removeUserData()
                                    }) {
                                        Text(text = stringResource(R.string.remove))
                                    }
                                },
                                dismissButton = {
                                    Button(onClick = { pendingDialogState.value = null }) {
                                        Text(text = stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }

                        is PendingDialog.ConfirmImport -> {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { pendingDialogState.value = null },
                                title = { Text(text = stringResource(R.string.dialog_import_title)) },
                                text = {
                                    Text(
                                        text = stringResource(R.string.dialog_import_body)
                                    )
                                },
                                confirmButton = {
                                    Button(onClick = {
                                        val importUri = dialog.uri
                                        pendingDialogState.value = null
                                        viewModel.import(importUri)
                                    }) {
                                        Text(text = stringResource(R.string.import_action))
                                    }
                                },
                                dismissButton = {
                                    Button(onClick = { pendingDialogState.value = null }) {
                                        Text(text = stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }

                        null -> Unit
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
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

    private fun openExternalFileViewer(): Boolean {
        if (!ExternalFileAccess.isEnabled(this)) return false
        val rootUri = DocumentsContract.buildRootUri(
            ExternalFileAccess.authority(this),
            SillyTavernDocumentsProvider.ROOT_ID
        )
        val intent = Intent(Intent.ACTION_VIEW, rootUri).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    private fun openBatteryOptimizationSettings() {
        val packageUri = Uri.fromParts("package", packageName, null)
        val powerManager = getSystemService(PowerManager::class.java)
        val isIgnoringOptimizations = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        val intentCandidates = listOf(
            Intent("android.settings.APP_BATTERY_SETTINGS").apply {
                data = packageUri
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra("android.intent.extra.PACKAGE_NAME", packageName)
                putExtra("package_name", packageName)
            },
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
                .takeUnless { isIgnoringOptimizations },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        ).filterNotNull()

        val intent = intentCandidates.firstOrNull { candidate ->
            candidate.resolveActivity(packageManager) != null
        } ?: Intent(Settings.ACTION_SETTINGS)

        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
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
            val yamlRoot = configFile.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                Yaml().load<Any?>(reader)
            }
            val rawPort = (yamlRoot as? Map<*, *>)?.get("port")
            val parsedPort = when (rawPort) {
                is Number -> rawPort.toInt()
                is String -> rawPort.trim().toIntOrNull()
                else -> null
            }
            parsedPort?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
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

    private fun isBatteryUnrestricted(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
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
            try {
                link.delete()
            } catch (_: Exception) {
            }
            try {
                target.delete()
            } catch (_: Exception) {
            }
            try {
                dir.delete()
            } catch (_: Exception) {
            }
        }
    }
}

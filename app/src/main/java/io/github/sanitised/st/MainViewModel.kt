package io.github.sanitised.st

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    internal val busyOperation = mutableStateOf<BusyOperation?>(null)
    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarMessages = _snackbarMessages.asSharedFlow()

    // Updated by MainActivity when the service connection changes.
    var nodeService: NodeService? = null

    private val backupManager = BackupManager(
        application = application,
        scope = viewModelScope,
        setBusyOperation = { busyOperation.value = it },
        getNodeService = { nodeService },
        appendServiceLog = { message -> appendServiceLog(message) }
    )

    private val customInstallManager = CustomInstallManager(
        application = application,
        scope = viewModelScope,
        getBusyOperation = { busyOperation.value },
        setBusyOperation = { busyOperation.value = it },
        postUserMessage = { message -> postUserMessage(message) },
        appendServiceLog = { message -> appendServiceLog(message) }
    )

    private val updateManager = UpdateManager(
        application = application,
        scope = viewModelScope,
        postUserMessage = { message -> postUserMessage(message) },
        appendServiceLog = { message -> appendServiceLog(message) }
    )

    val isCustomInstalled: MutableState<Boolean> = customInstallManager.isCustomInstalled
    val customInstallLabel: MutableState<String?> = customInstallManager.customInstallLabel
    val customRepoInput: MutableState<String> = customInstallManager.customRepoInput
    val isLoadingCustomRefs: MutableState<Boolean> = customInstallManager.isLoadingCustomRefs
    val customRepoValidationMessage: MutableState<String> = customInstallManager.customRepoValidationMessage
    val customInstallValidationMessage: MutableState<String> = customInstallManager.customInstallValidationMessage
    val customFeaturedRefs: MutableState<List<CustomRepoRefOption>> = customInstallManager.customFeaturedRefs
    val customAllRefs: MutableState<List<CustomRepoRefOption>> = customInstallManager.customAllRefs
    val selectedCustomRefKey: MutableState<String?> = customInstallManager.selectedCustomRefKey
    val customOperationCard: MutableState<OperationCardState> = customInstallManager.customOperationCard
    val customOperationCardAnchor: MutableState<CustomOperationAnchor> = customInstallManager.customOperationCardAnchor
    val backupOperationCard: MutableState<OperationCardState> = backupManager.backupOperationCard
    val backupOperationCardAnchor: MutableState<BackupOperationAnchor> = backupManager.backupOperationCardAnchor

    val autoCheckForUpdates: MutableState<Boolean> = updateManager.autoCheckForUpdates
    val autoOpenBrowserWhenReady: MutableState<Boolean> = updateManager.autoOpenBrowserWhenReady
    val updateChannel: MutableState<UpdateChannel> = updateManager.updateChannel
    val isCheckingForUpdates: MutableState<Boolean> = updateManager.isCheckingForUpdates
    val isDownloadingUpdate: MutableState<Boolean> = updateManager.isDownloadingUpdate
    val downloadProgressPercent: MutableState<Int?> = updateManager.downloadProgressPercent
    val updateBannerMessage: MutableState<String> = updateManager.updateBannerMessage

    override fun onCleared() {
        updateManager.onCleared()
        customInstallManager.onCleared()
        super.onCleared()
    }

    val busyMessage: String
        get() = when (busyOperation.value) {
            BusyOperation.EXPORTING -> getApplication<Application>().getString(R.string.busy_exporting_data)
            BusyOperation.IMPORTING -> getApplication<Application>().getString(R.string.busy_importing_data)
            BusyOperation.INSTALLING -> getApplication<Application>().getString(R.string.busy_installing_custom_st)
            BusyOperation.RESETTING -> getApplication<Application>().getString(R.string.busy_resetting_default)
            BusyOperation.REMOVING_DATA -> getApplication<Application>().getString(R.string.busy_removing_data)
            BusyOperation.DOWNLOADING_CUSTOM_SOURCE -> getApplication<Application>().getString(R.string.busy_downloading_custom_source)
            null -> ""
        }

    fun export(uri: Uri) {
        backupManager.export(uri)
    }

    fun import(uri: Uri) {
        backupManager.import(uri)
    }

    fun installCustomZip(uri: Uri) {
        customInstallManager.installCustomZip(uri)
    }

    fun resetToDefault() {
        customInstallManager.resetToDefault()
    }

    fun setCustomRepoInput(value: String) {
        customInstallManager.setCustomRepoInput(value)
    }

    fun selectCustomRepoRef(key: String) {
        customInstallManager.selectCustomRepoRef(key)
    }

    fun loadCustomRepoRefs() {
        customInstallManager.loadCustomRepoRefs()
    }

    fun startCustomRepoInstall() {
        customInstallManager.startCustomRepoInstall()
    }

    fun cancelCustomSourceDownload() {
        customInstallManager.cancelCustomSourceDownload()
    }

    fun removeUserData() {
        customInstallManager.removeUserData()
    }

    fun setAutoCheckForUpdates(enabled: Boolean) {
        updateManager.setAutoCheckForUpdates(enabled)
    }

    fun setAutoOpenBrowserWhenReady(enabled: Boolean) {
        updateManager.setAutoOpenBrowserWhenReady(enabled)
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        updateManager.setUpdateChannel(channel)
    }

    fun maybeAutoCheckForUpdates() {
        updateManager.maybeAutoCheckForUpdates()
    }

    fun shouldShowAutoCheckOptInPrompt(): Boolean {
        return updateManager.shouldShowAutoCheckOptInPrompt()
    }

    fun acceptAutoCheckOptInPrompt() {
        updateManager.acceptAutoCheckOptInPrompt()
    }

    fun dismissAutoCheckOptInPrompt() {
        updateManager.dismissAutoCheckOptInPrompt()
    }

    fun shouldShowUpdatePrompt(): Boolean {
        return updateManager.shouldShowUpdatePrompt()
    }

    fun availableUpdateVersionLabel(): String {
        return updateManager.availableUpdateVersionLabel()
    }

    fun isAvailableUpdateDownloaded(): Boolean {
        return updateManager.isAvailableUpdateDownloaded()
    }

    fun dismissAvailableUpdatePrompt() {
        updateManager.dismissAvailableUpdatePrompt()
    }

    fun startAvailableUpdateDownload() {
        updateManager.startAvailableUpdateDownload()
    }

    fun cancelUpdateDownload() {
        updateManager.cancelUpdateDownload()
    }

    fun installDownloadedUpdate(context: Context) {
        updateManager.installDownloadedUpdate(context)
    }

    fun checkForUpdates(reason: String = "manual") {
        updateManager.checkForUpdates(reason)
    }

    fun showTransientMessage(message: String) {
        postUserMessage(message)
    }

    private fun postUserMessage(message: String) {
        if (message.isBlank()) return
        if (!_snackbarMessages.tryEmit(message)) {
            viewModelScope.launch {
                _snackbarMessages.emit(message)
            }
        }
    }

    private suspend fun appendServiceLog(message: String) {
        withContext(Dispatchers.IO) {
            val logsDir = AppPaths(getApplication<Application>()).logsDir
            if (!logsDir.exists()) logsDir.mkdirs()
            val logFile = File(logsDir, "service.log")
            logFile.appendText(formatServiceLogLine(message), Charsets.UTF_8)
        }
    }
}

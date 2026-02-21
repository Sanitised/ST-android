package io.github.sanitised.st

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class BusyOperation {
    EXPORTING, IMPORTING, INSTALLING, RESETTING, REMOVING_DATA
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    internal val busyOperation = mutableStateOf<BusyOperation?>(null)
    val backupStatus = mutableStateOf("")
    val customStatus = mutableStateOf("")
    val removeDataStatus = mutableStateOf("")
    val isCustomInstalled = mutableStateOf(
        NodePayload(application).isCustomInstalled()
    )

    // Updated by MainActivity when the service connection changes.
    var nodeService: NodeService? = null

    val busyMessage: String
        get() = when (busyOperation.value) {
            BusyOperation.EXPORTING -> "Exporting data…"
            BusyOperation.IMPORTING -> "Importing data…"
            BusyOperation.INSTALLING -> "Installing custom ST…"
            BusyOperation.RESETTING -> "Resetting to default…"
            BusyOperation.REMOVING_DATA -> "Removing user data…"
            null -> ""
        }

    fun export(uri: Uri) {
        busyOperation.value = BusyOperation.EXPORTING
        backupStatus.value = "Exporting..."
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                NodeBackup.exportToUri(getApplication(), uri)
            }
            val msg = result.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
            backupStatus.value = msg
            busyOperation.value = null
            appendServiceLog(msg)
        }
    }

    fun import(uri: Uri) {
        busyOperation.value = BusyOperation.IMPORTING
        backupStatus.value = "Importing..."
        val service = nodeService
        viewModelScope.launch {
            val importResult = withContext(Dispatchers.IO) {
                NodeBackup.importFromUri(getApplication(), uri)
            }
            val postInstallResult = if (importResult.isSuccess) {
                withContext(Dispatchers.IO) {
                    service?.runPostInstallNow()
                } ?: Result.failure(IllegalStateException("Service not available"))
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
            backupStatus.value = msg
            busyOperation.value = null
            appendServiceLog(msg)
        }
    }

    fun installCustomZip(uri: Uri) {
        busyOperation.value = BusyOperation.INSTALLING
        customStatus.value = "Starting…"
        viewModelScope.launch {
            val payload = NodePayload(getApplication())
            val result = withContext(Dispatchers.IO) {
                payload.installCustomFromZip(uri) { msg ->
                    viewModelScope.launch(Dispatchers.Main) {
                        customStatus.value = msg
                    }
                }
            }
            isCustomInstalled.value = payload.isCustomInstalled()
            busyOperation.value = null
            val msg = result.fold(
                onSuccess = { "Custom ST installed successfully." },
                onFailure = { "Installation failed: ${it.message ?: "unknown error"}" }
            )
            customStatus.value = msg
            appendServiceLog(msg)
        }
    }

    fun resetToDefault() {
        busyOperation.value = BusyOperation.RESETTING
        customStatus.value = "Resetting…"
        viewModelScope.launch {
            val payload = NodePayload(getApplication())
            val result = withContext(Dispatchers.IO) {
                payload.resetToDefault()
            }
            isCustomInstalled.value = payload.isCustomInstalled()
            busyOperation.value = null
            val msg = result.fold(
                onSuccess = { "Reset to default complete." },
                onFailure = { "Reset failed: ${it.message ?: "unknown error"}" }
            )
            customStatus.value = msg
            appendServiceLog(msg)
        }
    }

    fun removeUserData() {
        busyOperation.value = BusyOperation.REMOVING_DATA
        removeDataStatus.value = "Removing data…"
        viewModelScope.launch {
            val paths = AppPaths(getApplication())
            withContext(Dispatchers.IO) {
                paths.configDir.deleteRecursively()
                paths.dataDir.deleteRecursively()
            }
            val msg = "User data removed."
            removeDataStatus.value = msg
            busyOperation.value = null
            appendServiceLog(msg)
        }
    }

    private suspend fun appendServiceLog(message: String) {
        withContext(Dispatchers.IO) {
            val logsDir = AppPaths(getApplication()).logsDir
            if (!logsDir.exists()) logsDir.mkdirs()
            val logFile = File(logsDir, "service.log")
            logFile.appendText("${System.currentTimeMillis()}: $message\n", Charsets.UTF_8)
        }
    }
}

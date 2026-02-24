package io.github.sanitised.st

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BackupManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val setBusyOperation: (BusyOperation?) -> Unit,
    private val getNodeService: () -> NodeService?,
    private val appendServiceLog: suspend (String) -> Unit
) {
    private val operationCardController = OperationCardController(scope)
    val backupOperationCard: MutableState<OperationCardState> = operationCardController.state
    val backupOperationCardAnchor = mutableStateOf(BackupOperationAnchor.EXPORT)

    fun export(uri: Uri) {
        setBusyOperation(BusyOperation.EXPORTING)
        startBackupOperationCard(
            title = "Exporting Backup",
            details = "Preparing export…",
            progressPercent = null,
            anchor = BackupOperationAnchor.EXPORT
        )
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                NodeBackup.exportToUri(application, uri) { progress ->
                    scope.launch(Dispatchers.Main) {
                        updateBackupOperationCard(
                            details = progress.message,
                            progressPercent = progress.percent
                        )
                    }
                }
            }
            val msg = result.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
            setBusyOperation(null)
            finishBackupOperationCard(msg)
            appendServiceLog(msg)
        }
    }

    fun import(uri: Uri) {
        setBusyOperation(BusyOperation.IMPORTING)
        startBackupOperationCard(
            title = "Importing Backup",
            details = "Preparing import…",
            progressPercent = null,
            anchor = BackupOperationAnchor.IMPORT
        )
        scope.launch {
            val importResult = withContext(Dispatchers.IO) {
                NodeBackup.importFromUri(application, uri) { progress ->
                    scope.launch(Dispatchers.Main) {
                        updateBackupOperationCard(
                            details = progress.message,
                            progressPercent = progress.percent
                        )
                    }
                }
            }
            val postInstallResult = if (importResult.isSuccess) {
                withContext(Dispatchers.IO) {
                    getNodeService()?.runPostInstallNow()
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
            setBusyOperation(null)
            finishBackupOperationCard(msg)
            appendServiceLog(msg)
        }
    }

    private fun startBackupOperationCard(
        title: String,
        details: String,
        progressPercent: Int?,
        anchor: BackupOperationAnchor
    ) {
        backupOperationCardAnchor.value = anchor
        operationCardController.start(
            title = title,
            details = details,
            progressPercent = progressPercent,
            cancelable = false
        )
    }

    private fun updateBackupOperationCard(
        details: String? = null,
        progressPercent: Int? = backupOperationCard.value.progressPercent
    ) {
        operationCardController.update(
            details = details,
            progressPercent = progressPercent
        )
    }

    private fun finishBackupOperationCard(finalMessage: String) {
        operationCardController.finish(finalMessage)
    }
}

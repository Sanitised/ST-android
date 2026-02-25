package io.github.sanitised.st

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class BusyOperation {
    EXPORTING, IMPORTING, INSTALLING, RESETTING, REMOVING_DATA, DOWNLOADING_CUSTOM_SOURCE
}

enum class CustomOperationAnchor {
    GITHUB_INSTALL,
    ZIP_INSTALL,
    RESET_TO_BUNDLED
}

enum class BackupOperationAnchor {
    EXPORT,
    IMPORT
}

enum class UpdateChannel(val storageValue: String) {
    RELEASE("release"),
    PRERELEASE("prerelease");

    companion object {
        fun fromStorage(value: String?): UpdateChannel {
            return entries.firstOrNull { it.storageValue == value } ?: RELEASE
        }
    }
}

data class CustomRepoRefOption(
    val key: String,
    val label: String,
    val refType: String,
    val refName: String,
    val commitSha: String?
)

data class OperationCardState(
    val visible: Boolean = false,
    val title: String = "",
    val details: String = "",
    val progressPercent: Int? = null,
    val cancelable: Boolean = false
)

internal class OperationCardController(
    private val scope: CoroutineScope
) {
    val state = mutableStateOf(OperationCardState())
    private var token = 0L

    fun start(
        title: String,
        details: String,
        progressPercent: Int?,
        cancelable: Boolean
    ) {
        token += 1
        state.value = OperationCardState(
            visible = true,
            title = title,
            details = details,
            progressPercent = progressPercent,
            cancelable = cancelable
        )
    }

    fun update(
        details: String? = null,
        progressPercent: Int? = state.value.progressPercent,
        cancelable: Boolean? = null
    ) {
        val current = state.value
        state.value = current.copy(
            details = details ?: current.details,
            progressPercent = progressPercent,
            cancelable = cancelable ?: current.cancelable
        )
    }

    fun finish(finalMessage: String) {
        val finishToken = token
        state.value = state.value.copy(
            details = finalMessage,
            progressPercent = null,
            cancelable = false
        )
        scope.launch {
            delay(1500)
            if (finishToken == token) {
                state.value = OperationCardState()
            }
        }
    }
}

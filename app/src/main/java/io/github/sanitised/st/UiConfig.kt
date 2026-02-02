package io.github.sanitised.st

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ConfigScreen(
    onBack: () -> Unit,
    onOpenDocs: () -> Unit,
    canEdit: Boolean,
    configFile: File
) {
    val textState = rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val originalState = remember { mutableStateOf("") }
    val statusState = remember { mutableStateOf("") }
    val showDiscardDialog = remember { mutableStateOf(false) }
    val missingState = remember { mutableStateOf(false) }
    val loadedState = remember { mutableStateOf(false) }
    val hasUserEdits = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val content = withContext(Dispatchers.IO) {
            if (configFile.exists()) {
                configFile.readText(Charsets.UTF_8)
            } else {
                ""
            }
        }
        if (!hasUserEdits.value) {
            textState.value = TextFieldValue(content, selection = androidx.compose.ui.text.TextRange(0))
        }
        originalState.value = content
        missingState.value = !configFile.exists()
        loadedState.value = true
    }
    LaunchedEffect(loadedState.value) {
        if (loadedState.value) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(text = "Config")
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(onClick = {
                    val canEditEffective = canEdit && !missingState.value
                    if (canEditEffective && textState.value.text != originalState.value) {
                        showDiscardDialog.value = true
                    } else {
                        onBack()
                    }
                }) { Text(text = "Back") }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = onOpenDocs) { Text(text = "Open Docs") }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (missingState.value) {
                Text(
                    text = "Config is missing. Start SillyTavern once to generate a default config.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else if (!canEdit) {
                Text(text = "Stop the server to edit the config.", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
            }
            val canEditEffective = canEdit && !missingState.value
            if (loadedState.value) {
                OutlinedTextField(
                    value = textState.value,
                    onValueChange = {
                        if (canEditEffective) {
                            textState.value = it
                            hasUserEdits.value = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .focusRequester(focusRequester),
                    enabled = canEditEffective,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text(text = "config.yaml") },
                    maxLines = Int.MAX_VALUE
                )
            } else {
                Text(text = "Loading config...", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    statusState.value = "Saving..."
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching {
                            configFile.parentFile?.mkdirs()
                            configFile.writeText(textState.value.text, Charsets.UTF_8)
                        }
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) {
                                originalState.value = textState.value.text
                                statusState.value = "Saved"
                            } else {
                                statusState.value = "Save failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                            }
                        }
                    }
                },
                enabled = canEditEffective
            ) {
                Text(text = "Save")
            }
            if (statusState.value.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = statusState.value, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showDiscardDialog.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiscardDialog.value = false },
            title = { Text(text = "Discard changes?") },
            text = { Text(text = "You have unsaved changes.") },
            confirmButton = {
                Button(onClick = {
                    showDiscardDialog.value = false
                    onBack()
                }) {
                    Text(text = "Discard")
                }
            },
            dismissButton = {
                Button(onClick = { showDiscardDialog.value = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

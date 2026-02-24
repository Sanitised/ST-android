package io.github.sanitised.st

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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
    val requestBack = {
        val canEditEffective = canEdit && !missingState.value
        if (canEditEffective && textState.value.text != originalState.value) {
            showDiscardDialog.value = true
        } else {
            onBack()
        }
    }

    LaunchedEffect(Unit) {
        val content = withContext(Dispatchers.IO) {
            if (configFile.exists()) configFile.readText(Charsets.UTF_8) else ""
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
    BackHandler(onBack = requestBack)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = requestBack) {
                    Text(text = "← Back")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Edit Config",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenDocs) {
                    Text(text = "Docs")
                }
            }
            HorizontalDivider()

            val canEditEffective = canEdit && !missingState.value
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (missingState.value) {
                    Text(
                        text = "Config is missing. Start SillyTavern once to generate a default config.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else if (!canEdit) {
                    Text(
                        text = "Stop the server to edit the config.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                ) {
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
                                .fillMaxSize()
                                .focusRequester(focusRequester),
                            enabled = canEditEffective,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            label = { Text(text = "config.yaml") },
                            maxLines = Int.MAX_VALUE
                        )
                    } else {
                        Text(
                            text = "Loading...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
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
                                    statusState.value =
                                        "Save failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                                }
                            }
                        }
                    },
                    enabled = canEditEffective,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Save")
                }
                if (statusState.value.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusState.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showDiscardDialog.value) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog.value = false },
            title = { Text(text = "Discard changes?") },
            text = { Text(text = "You have unsaved changes. If you go back, they will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog.value = false
                    onBack()
                }) {
                    Text(text = "Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog.value = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

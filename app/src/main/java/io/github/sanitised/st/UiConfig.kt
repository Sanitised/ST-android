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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ConfigScreen(
    onBack: () -> Unit,
    onOpenDocs: () -> Unit,
    canEdit: Boolean,
    configFile: File,
    onShowMessage: (String) -> Unit
) {
    val textState = rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val originalState = remember { mutableStateOf("") }
    val isSavingState = remember { mutableStateOf(false) }
    val showDiscardDialog = remember { mutableStateOf(false) }
    val missingState = remember { mutableStateOf(false) }
    val loadedState = remember { mutableStateOf(false) }
    val hasUserEdits = remember { mutableStateOf(false) }
    val editorScrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
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
            textState.value = TextFieldValue(content, selection = TextRange(0))
        }
        originalState.value = content
        missingState.value = !configFile.exists()
        loadedState.value = true
    }
    LaunchedEffect(loadedState.value) {
        if (loadedState.value) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.hide()
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
            SecondaryTopAppBar(
                title = stringResource(R.string.config_title),
                onBack = requestBack,
                actions = {
                    TextButton(onClick = onOpenDocs) {
                        Text(text = stringResource(R.string.docs))
                    }
                }
            )

            val canEditEffective = canEdit && !missingState.value
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (missingState.value) {
                    Text(
                        text = stringResource(R.string.config_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else if (!canEdit) {
                    Text(
                        text = stringResource(R.string.config_stop_server),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.config_file_name),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                ) {
                    if (loadedState.value) {
                        val scrollbarColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Box(modifier = Modifier.fillMaxSize().verticalScrollbar(editorScrollState, scrollbarColor)) {
                                BasicTextField(
                                value = textState.value,
                                onValueChange = {
                                    if (canEditEffective) {
                                        textState.value = it
                                        hasUserEdits.value = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(editorScrollState)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .focusRequester(focusRequester),
                                enabled = canEditEffective,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrect = false,
                                    imeAction = ImeAction.Default
                                )
                            )
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        isSavingState.value = true
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching {
                                configFile.parentFile?.mkdirs()
                                configFile.writeText(textState.value.text, Charsets.UTF_8)
                            }
                            withContext(Dispatchers.Main) {
                                isSavingState.value = false
                                if (result.isSuccess) {
                                    originalState.value = textState.value.text
                                    onShowMessage(context.getString(R.string.config_saved))
                                } else {
                                    onShowMessage(
                                        context.getString(
                                            R.string.config_save_failed,
                                            result.exceptionOrNull()?.message ?: context.getString(R.string.unknown_error)
                                        )
                                    )
                                }
                            }
                        }
                    },
                    enabled = canEditEffective && !isSavingState.value,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = if (isSavingState.value) stringResource(R.string.saving) else stringResource(R.string.save))
                }
            }
        }
    }

    if (showDiscardDialog.value) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog.value = false },
            title = { Text(text = stringResource(R.string.config_discard_title)) },
            text = { Text(text = stringResource(R.string.config_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog.value = false
                    onBack()
                }) {
                    Text(text = stringResource(R.string.discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog.value = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

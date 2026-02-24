package io.github.sanitised.st

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun ManageStScreen(
    onBack: () -> Unit,
    isCustomInstalled: Boolean,
    customInstalledLabel: String?,
    serverRunning: Boolean,
    busyMessage: String,
    onExport: () -> Unit,
    onImport: () -> Unit,
    customRepoInput: String,
    onCustomRepoInputChanged: (String) -> Unit,
    onLoadRepoRefs: () -> Unit,
    isLoadingRepoRefs: Boolean,
    customRepoValidationMessage: String,
    featuredRefs: List<CustomRepoRefOption>,
    allRefs: List<CustomRepoRefOption>,
    selectedRefKey: String?,
    onSelectRepoRef: (String) -> Unit,
    onDownloadAndInstallRef: () -> Unit,
    customInstallValidationMessage: String,
    showBackupOperationCard: Boolean,
    backupOperationTitle: String,
    backupOperationDetails: String,
    backupOperationProgressPercent: Int?,
    backupOperationAnchor: BackupOperationAnchor,
    showCustomOperationCard: Boolean,
    customOperationTitle: String,
    customOperationDetails: String,
    customOperationProgressPercent: Int?,
    customOperationCancelable: Boolean,
    customOperationAnchor: CustomOperationAnchor,
    onCancelCustomOperation: () -> Unit,
    onLoadCustomZip: () -> Unit,
    onResetToDefault: () -> Unit,
    onRemoveUserData: () -> Unit
) {
    val allRefsByKey = remember(allRefs) { allRefs.associateBy { it.key } }
    val selectedRef = remember(selectedRefKey, allRefsByKey, featuredRefs) {
        selectedRefKey?.let { key -> allRefsByKey[key] }
            ?: featuredRefs.firstOrNull { it.key == selectedRefKey }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Manage ST",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            HorizontalDivider()
            val scrollState = rememberScrollState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            Box(modifier = Modifier.fillMaxSize().verticalScrollbar(scrollState, scrollbarColor)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    val buttonsEnabled = !serverRunning && busyMessage.isBlank()
                    Text(
                        text = "Data Backup",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Export your current data or import a backup archive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onExport,
                            enabled = buttonsEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Export Data")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = onImport,
                            enabled = buttonsEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Import Data")
                        }
                    }
                    if (showBackupOperationCard &&
                        (backupOperationAnchor == BackupOperationAnchor.EXPORT ||
                            backupOperationAnchor == BackupOperationAnchor.IMPORT)
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CustomSourceDownloadCard(
                            visible = true,
                            title = backupOperationTitle,
                            details = backupOperationDetails,
                            downloadProgressPercent = backupOperationProgressPercent,
                            showCancel = false,
                            onCancelDownload = {}
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "User Data",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Permanently delete all chats, characters, presets, worlds, " +
                                "settings, and other user data stored by the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onRemoveUserData,
                        enabled = buttonsEnabled,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Remove All User Data")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Custom SillyTavern Version",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Replace the bundled SillyTavern with a version you provide.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                text = "⚠  Use at your own risk",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Custom installs replace bundled code. Only install from repos you trust.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            WarningBullet(
                                text = "Back up your data first — use Export Data above.",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            WarningBullet(
                                text = "Untrusted code can steal keys, chats, and characters, as well as attack other devices on your network",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            WarningBullet(
                                text = "Downgrades/forks can break data.",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    val installedStatus = if (isCustomInstalled) {
                        val label = customInstalledLabel?.takeIf { it.isNotBlank() } ?: "custom version"
                        "Currently installed: custom ($label)"
                    } else {
                        "Currently installed: default (bundled)"
                    }
                    Text(
                        text = installedStatus,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isCustomInstalled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onResetToDefault,
                            enabled = buttonsEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Reset to Bundled Version")
                        }
                    }
                    if (showCustomOperationCard && customOperationAnchor == CustomOperationAnchor.RESET_TO_BUNDLED) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CustomSourceDownloadCard(
                            visible = true,
                            title = customOperationTitle,
                            details = customOperationDetails,
                            downloadProgressPercent = customOperationProgressPercent,
                            showCancel = customOperationCancelable,
                            onCancelDownload = onCancelCustomOperation
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Install from GitHub",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customRepoInput,
                        onValueChange = onCustomRepoInputChanged,
                        singleLine = true,
                        enabled = buttonsEnabled,
                        label = { Text("Repository (owner/repo)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onLoadRepoRefs,
                        enabled = buttonsEnabled && !isLoadingRepoRefs,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = if (isLoadingRepoRefs) "Loading refs..." else "Load Branches and Tags")
                    }
                    if (customRepoValidationMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = customRepoValidationMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (featuredRefs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Quick picks",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        for (option in featuredRefs) {
                            OutlinedButton(
                                onClick = { onSelectRepoRef(option.key) },
                                enabled = buttonsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (option.key == selectedRefKey) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    }
                                )
                            ) {
                                Text(text = option.label)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    val showMoreRefsMenu = remember { mutableStateOf(false) }
                    if (allRefs.isNotEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showMoreRefsMenu.value = true },
                                enabled = buttonsEnabled,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = "More options (${allRefs.size})")
                            }
                            DropdownMenu(
                                expanded = showMoreRefsMenu.value,
                                onDismissRequest = { showMoreRefsMenu.value = false }
                            ) {
                                for (option in allRefs) {
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            onSelectRepoRef(option.key)
                                            showMoreRefsMenu.value = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onDownloadAndInstallRef,
                        enabled = buttonsEnabled && selectedRef != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val label = if (selectedRef == null) {
                            "Install Selected Version"
                        } else {
                            "Install Selected Version (${selectedRef.refType}: ${selectedRef.refName})"
                        }
                        Text(text = label)
                    }
                    if (customInstallValidationMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = customInstallValidationMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (showCustomOperationCard && customOperationAnchor == CustomOperationAnchor.GITHUB_INSTALL) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CustomSourceDownloadCard(
                            visible = true,
                            title = customOperationTitle,
                            details = customOperationDetails,
                            downloadProgressPercent = customOperationProgressPercent,
                            showCancel = customOperationCancelable,
                            onCancelDownload = onCancelCustomOperation
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Or install from ZIP",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    InstructionStep(
                        "1",
                        "Open the repository page on GitHub",
                        url = "https://github.com/SillyTavern/SillyTavern"
                    )
                    InstructionStep("2", "Choose the branch/tag/commit")
                    InstructionStep("3", "Click Code → Download ZIP")
                    InstructionStep("4", "Tap Install ST from ZIP below")
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onLoadCustomZip,
                        enabled = buttonsEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Install ST from ZIP")
                    }
                    if (showCustomOperationCard && customOperationAnchor == CustomOperationAnchor.ZIP_INSTALL) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CustomSourceDownloadCard(
                            visible = true,
                            title = customOperationTitle,
                            details = customOperationDetails,
                            downloadProgressPercent = customOperationProgressPercent,
                            showCancel = customOperationCancelable,
                            onCancelDownload = onCancelCustomOperation
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun WarningBullet(text: String, color: Color) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = "•  ", style = MaterialTheme.typography.bodyMedium, color = color)
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun InstructionStep(number: String, text: String, url: String? = null) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyStyle = MaterialTheme.typography.bodyMedium
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$number.  ",
            style = bodyStyle,
            fontWeight = FontWeight.SemiBold,
            color = linkColor
        )
        if (url != null) {
            val annotated = buildAnnotatedString {
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(text)
                }
                pop()
            }
            ClickableText(
                text = annotated,
                style = bodyStyle,
                onClick = { offset ->
                    annotated.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()?.let { uriHandler.openUri(it.item) }
                }
            )
        } else {
            Text(text = text, style = bodyStyle)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ManageStScreenPreview() {
    ManageStScreen(
        onBack = {},
        isCustomInstalled = true,
        customInstalledLabel = "branch/staging-1234567",
        serverRunning = false,
        busyMessage = "",
        onExport = {},
        onImport = {},
        customRepoInput = "SillyTavern/SillyTavern",
        onCustomRepoInputChanged = {},
        onLoadRepoRefs = {},
        isLoadingRepoRefs = false,
        customRepoValidationMessage = "",
        featuredRefs = listOf(
            CustomRepoRefOption("branch:staging", "branch: staging (abc1234)", "branch", "staging", "abc1234"),
            CustomRepoRefOption("branch:release", "branch: release (def5678)", "branch", "release", "def5678")
        ),
        allRefs = listOf(
            CustomRepoRefOption("branch:staging", "branch: staging (abc1234)", "branch", "staging", "abc1234"),
            CustomRepoRefOption("branch:release", "branch: release (def5678)", "branch", "release", "def5678"),
            CustomRepoRefOption("tag:v1.13.2", "tag: v1.13.2 (ff00112)", "tag", "v1.13.2", "ff00112")
        ),
        selectedRefKey = "branch:staging",
        onSelectRepoRef = {},
        onDownloadAndInstallRef = {},
        customInstallValidationMessage = "",
        showBackupOperationCard = false,
        backupOperationTitle = "Exporting Backup",
        backupOperationDetails = "Exporting backup…",
        backupOperationProgressPercent = 42,
        backupOperationAnchor = BackupOperationAnchor.EXPORT,
        showCustomOperationCard = false,
        customOperationTitle = "Installing Custom ST",
        customOperationDetails = "Installing dependencies (npm install)…",
        customOperationProgressPercent = null,
        customOperationCancelable = false,
        customOperationAnchor = CustomOperationAnchor.GITHUB_INSTALL,
        onCancelCustomOperation = {},
        onLoadCustomZip = {},
        onResetToDefault = {},
        onRemoveUserData = {}
    )
}

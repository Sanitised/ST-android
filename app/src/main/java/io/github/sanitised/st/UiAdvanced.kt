package io.github.sanitised.st

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun AdvancedScreen(
    onBack: () -> Unit,
    isCustomInstalled: Boolean,
    customStatus: String,
    serverRunning: Boolean,
    busyMessage: String,
    onLoadCustomZip: () -> Unit,
    onResetToDefault: () -> Unit,
    onRemoveUserData: () -> Unit,
    removeDataStatus: String
) {
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
                TextButton(onClick = onBack) {
                    Text(text = "← Back")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Advanced",
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
                if (removeDataStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = removeDataStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    text = "Replace the bundled SillyTavern with a version you provide " +
                            "by loading a source ZIP from GitHub.",
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
                            text = "Loading a custom SillyTavern version replaces the bundled " +
                                    "installation with code you provide. You are fully " +
                                    "responsible for what you run.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        WarningBullet(
                            text = "Back up your data first — use Export Data on the main screen.",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        WarningBullet(
                            text = "Only load archives from sources you trust. Untrusted code can steal your keys, chats or characters.",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        WarningBullet(
                            text = "This can break your data, especially when downgrading or using forks.",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "How to get a SillyTavern ZIP",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))
                InstructionStep("1", "Go to github.com/SillyTavern/SillyTavern", url = "https://github.com/SillyTavern/SillyTavern")
                InstructionStep("2", "Select the commit or release tag you want to install")
                InstructionStep("3", "Click Code → Download ZIP")
                InstructionStep("4", "Come back here and tap Load ZIP")
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isCustomInstalled) "Currently installed: custom version" else "Currently installed: default (bundled)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (serverRunning) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Stop the server before making changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (busyMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$busyMessage — please wait.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onLoadCustomZip,
                    enabled = buttonsEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = if (isCustomInstalled) "Load New ZIP" else "Load ZIP")
                }
                if (isCustomInstalled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onResetToDefault,
                        enabled = buttonsEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Reset to Bundled Version")
                    }
                }
                if (customStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = customStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        }
    }
}

private fun Modifier.verticalScrollbar(state: ScrollState, color: Color): Modifier =
    drawWithContent {
        drawContent()
        if (state.maxValue > 0 && state.maxValue < Int.MAX_VALUE) {
            val viewport = size.height
            val content = viewport + state.maxValue
            val thumbH = (viewport * viewport / content).coerceAtLeast(48f)
            val thumbY = (state.value.toFloat() / state.maxValue) * (viewport - thumbH)
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width - 8f, thumbY + 2f),
                size = Size(6f, thumbH - 4f),
                cornerRadius = CornerRadius(3f)
            )
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
private fun AdvancedScreenPreview() {
    AdvancedScreen(
        onBack = {},
        isCustomInstalled = true,
        customStatus = "Custom ST installed successfully.",
        serverRunning = false,
        busyMessage = "",
        onLoadCustomZip = {},
        onResetToDefault = {},
        onRemoveUserData = {},
        removeDataStatus = ""
    )
}

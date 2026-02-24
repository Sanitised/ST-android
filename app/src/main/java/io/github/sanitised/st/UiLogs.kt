package io.github.sanitised.st

import androidx.compose.foundation.ScrollState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LogsScreen(
    onBack: () -> Unit,
    stdoutLog: String,
    stderrLog: String,
    serviceLog: String
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
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Logs",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                LogSection(label = "stdout", text = stdoutLog, modifier = Modifier.weight(0.45f))
                Spacer(modifier = Modifier.height(8.dp))
                LogSection(label = "stderr", text = stderrLog, modifier = Modifier.weight(0.35f))
                Spacer(modifier = Modifier.height(8.dp))
                LogSection(label = "service", text = serviceLog, modifier = Modifier.weight(0.20f))
            }
        }
    }
}

@Composable
private fun LogSection(
    label: String,
    text: String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val scrollbarColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            CopyButton(text = text)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(modifier = Modifier.fillMaxSize().verticalScrollbar(scrollState, scrollbarColor)) {
                Text(
                    text = if (text.isNotEmpty()) text else "(empty)",
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (text.isNotEmpty())
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    TextButton(
        onClick = { clipboard.setText(AnnotatedString(text)) },
        enabled = text.isNotEmpty()
    ) {
        Text(text = "Copy", style = MaterialTheme.typography.labelMedium)
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

suspend fun readLogTail(file: File, maxBytes: Int): String {
    return withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext ""
        val length = file.length()
        if (length <= 0) return@withContext ""
        val toRead = if (length > maxBytes) maxBytes.toLong() else length
        val bytes = ByteArray(toRead.toInt())
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - toRead)
            raf.readFully(bytes)
        }
        bytes.toString(Charsets.UTF_8)
    }
}

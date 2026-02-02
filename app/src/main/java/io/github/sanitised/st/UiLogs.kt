package io.github.sanitised.st

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Logs")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onBack) { Text(text = "Back") }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LogSection(
                    label = "stdout",
                    text = stdoutLog,
                    modifier = Modifier.weight(0.45f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LogSection(
                    label = "stderr",
                    text = stderrLog,
                    modifier = Modifier.weight(0.35f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LogSection(
                    label = "service",
                    text = serviceLog,
                    modifier = Modifier.weight(0.20f)
                )
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
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        LogPanel(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        CopyButton(label = "Copy $label", text = text)
    }
}

@Composable
private fun LogPanel(
    text: String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Text(
        text = if (text.isNotEmpty()) text else "(empty)",
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(8.dp),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun CopyButton(label: String, text: String) {
    val clipboard = LocalClipboardManager.current
    Button(onClick = { clipboard.setText(AnnotatedString(text)) }, enabled = text.isNotEmpty()) {
        Text(text = label)
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

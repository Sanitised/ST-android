package io.github.sanitised.st

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LegalScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    legalDocs: List<LegalDoc>,
    onOpenLicense: (LegalDoc) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(text = "Legal & Licenses", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "This app bundles SillyTavern and Node.js.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Links", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { onOpenUrl("https://github.com/Sanitised/ST-android") }) {
                Text(text = "ST-android Repository")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { onOpenUrl("https://github.com/SillyTavern/SillyTavern") }) {
                Text(text = "SillyTavern Repository")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { onOpenUrl("https://github.com/nodejs/node") }) {
                Text(text = "Node.js Repository")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Licenses", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            for (doc in legalDocs) {
                Button(onClick = { onOpenLicense(doc) }) {
                    Text(text = doc.title)
                }
                if (!doc.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = doc.description, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack) {
                Text(text = "Back")
            }
        }
    }
}

@Composable
fun LicenseTextScreen(
    onBack: () -> Unit,
    doc: LegalDoc
) {
    val context = LocalContext.current
    val textState = remember { mutableStateOf("Loading...") }
    LaunchedEffect(doc.assetPath) {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(doc.assetPath).bufferedReader().use { it.readText() }
            }.getOrElse { "Failed to load license: ${it.message ?: "unknown error"}" }
        }
        textState.value = text
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Text(text = doc.title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onBack) { Text(text = "Back") }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = textState.value,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

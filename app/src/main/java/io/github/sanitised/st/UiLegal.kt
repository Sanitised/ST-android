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
import androidx.compose.ui.res.stringResource
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
        ) {
            SecondaryTopAppBar(
                title = stringResource(R.string.legal_title),
                onBack = onBack
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.legal_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stringResource(R.string.legal_links_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onOpenUrl("https://github.com/Sanitised/ST-android") }) {
                    Text(text = stringResource(R.string.legal_link_st_android))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onOpenUrl("https://github.com/SillyTavern/SillyTavern") }) {
                    Text(text = stringResource(R.string.legal_link_sillytavern))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onOpenUrl("https://github.com/nodejs/node") }) {
                    Text(text = stringResource(R.string.legal_link_node))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stringResource(R.string.legal_licenses_title), style = MaterialTheme.typography.titleMedium)
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
    val textState = remember { mutableStateOf(context.getString(R.string.loading)) }
    LaunchedEffect(doc.assetPath) {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(doc.assetPath).bufferedReader().use { it.readText() }
            }.getOrElse {
                context.getString(
                    R.string.legal_license_load_failed,
                    it.message ?: context.getString(R.string.unknown_error)
                )
            }
        }
        textState.value = text
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            SecondaryTopAppBar(
                title = doc.title,
                onBack = onBack
            )
            Text(
                text = textState.value,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

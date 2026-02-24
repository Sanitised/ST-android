package io.github.sanitised.st

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

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
            return values().firstOrNull { it.storageValue == value } ?: RELEASE
        }
    }
}

private data class ParsedVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String>
)

private data class GithubReleaseInfo(
    val tagName: String,
    val releaseName: String?,
    val prerelease: Boolean,
    val htmlUrl: String?,
    val apkAssetName: String?,
    val apkAssetUrl: String?
)

private data class UpdateCheckOutcome(
    val channel: UpdateChannel,
    val currentVersionName: String,
    val latest: GithubReleaseInfo?,
    val updateNeeded: Boolean
)

private enum class GithubRefType(val storageValue: String, val archivePrefix: String) {
    BRANCH("branch", "heads"),
    TAG("tag", "tags")
}

private data class GithubRepoRef(
    val type: GithubRefType,
    val name: String,
    val commitSha: String?
) {
    val key: String
        get() = "${type.storageValue}:$name"
}

data class CustomRepoRefOption(
    val key: String,
    val label: String,
    val refType: String,
    val refName: String,
    val commitSha: String?
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "UpdateCheck"
        private const val UPDATE_PREFS_NAME = "updates"
        private const val PREF_AUTO_CHECK = "auto_check"
        private const val PREF_AUTO_OPEN_BROWSER = "auto_open_browser"
        private const val PREF_CHANNEL = "channel"
        private const val PREF_FIRST_LAUNCH_MS = "first_launch_ms"
        private const val PREF_AUTO_OPTIN_PROMPT_SHOWN = "auto_optin_prompt_shown"
        private const val PREF_LAST_AUTO_CHECK_MS = "last_auto_check_ms"
        private const val PREF_UPDATE_DISMISSED_UNTIL_MS = "update_dismissed_until_ms"
        private const val GITHUB_OWNER = "Sanitised"
        private const val GITHUB_REPO = "ST-android"
        private const val DEFAULT_CUSTOM_ST_REPO = "SillyTavern/SillyTavern"
        private const val MAX_GITHUB_PAGES = 5
        private const val MAX_FEATURED_REFS = 6
        private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
        private const val THREE_DAYS_MS = 72L * 60L * 60L * 1000L
    }

    private val updatePrefs = application.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
    private val payload = NodePayload(application)

    internal val busyOperation = mutableStateOf<BusyOperation?>(null)
    val isCustomInstalled = mutableStateOf(
        payload.isCustomInstalled()
    )
    val customInstallLabel = mutableStateOf(payload.getCustomInstallLabel())
    val customRepoInput = mutableStateOf(DEFAULT_CUSTOM_ST_REPO)
    val isLoadingCustomRefs = mutableStateOf(false)
    val customRepoValidationMessage = mutableStateOf("")
    val customInstallValidationMessage = mutableStateOf("")
    val customFeaturedRefs = mutableStateOf<List<CustomRepoRefOption>>(emptyList())
    val customAllRefs = mutableStateOf<List<CustomRepoRefOption>>(emptyList())
    val selectedCustomRefKey = mutableStateOf<String?>(null)
    val isDownloadingCustomSource = mutableStateOf(false)
    val customOperationCardVisible = mutableStateOf(false)
    val customOperationCardTitle = mutableStateOf("")
    val customOperationCardDetails = mutableStateOf("")
    val customOperationCardProgressPercent = mutableStateOf<Int?>(null)
    val customOperationCardCancelable = mutableStateOf(false)
    val customOperationCardAnchor = mutableStateOf(CustomOperationAnchor.GITHUB_INSTALL)
    val backupOperationCardVisible = mutableStateOf(false)
    val backupOperationCardTitle = mutableStateOf("")
    val backupOperationCardDetails = mutableStateOf("")
    val backupOperationCardProgressPercent = mutableStateOf<Int?>(null)
    val backupOperationCardAnchor = mutableStateOf(BackupOperationAnchor.EXPORT)
    val autoCheckForUpdates = mutableStateOf(
        updatePrefs.getBoolean(PREF_AUTO_CHECK, false)
    )
    val autoOpenBrowserWhenReady = mutableStateOf(
        updatePrefs.getBoolean(PREF_AUTO_OPEN_BROWSER, true)
    )
    val updateChannel = mutableStateOf(
        resolveInitialUpdateChannel()
    )
    private val firstLaunchMs = ensureFirstLaunchTimestamp()
    private val autoOptInPromptShown = mutableStateOf(
        updatePrefs.getBoolean(PREF_AUTO_OPTIN_PROMPT_SHOWN, false)
    )
    private val lastAutoCheckMs = mutableStateOf(
        updatePrefs.getLong(PREF_LAST_AUTO_CHECK_MS, 0L)
    )
    private val updateDismissedUntilMs = mutableStateOf(
        updatePrefs.getLong(PREF_UPDATE_DISMISSED_UNTIL_MS, 0L)
    )
    val isCheckingForUpdates = mutableStateOf(false)
    private val availableUpdate = mutableStateOf<GithubReleaseInfo?>(null)
    val isDownloadingUpdate = mutableStateOf(false)
    val downloadProgressPercent = mutableStateOf<Int?>(null)
    val updateBannerMessage = mutableStateOf("")
    val downloadedUpdateTag = mutableStateOf<String?>(null)
    val downloadedApkPath = mutableStateOf<String?>(null)
    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarMessages = _snackbarMessages.asSharedFlow()

    private var autoCheckAttempted = false
    private var updateDownloadJob: Job? = null
    private var customSourceDownloadJob: Job? = null
    private var customOperationCardToken = 0L
    private var backupOperationCardToken = 0L

    // Updated by MainActivity when the service connection changes.
    var nodeService: NodeService? = null

    override fun onCleared() {
        updateDownloadJob?.cancel()
        customSourceDownloadJob?.cancel()
        super.onCleared()
    }

    val busyMessage: String
        get() = when (busyOperation.value) {
            BusyOperation.EXPORTING -> "Exporting data…"
            BusyOperation.IMPORTING -> "Importing data…"
            BusyOperation.INSTALLING -> "Installing custom ST…"
            BusyOperation.RESETTING -> "Resetting to default…"
            BusyOperation.REMOVING_DATA -> "Removing user data…"
            BusyOperation.DOWNLOADING_CUSTOM_SOURCE -> "Downloading custom ST source…"
            null -> ""
        }

    fun export(uri: Uri) {
        busyOperation.value = BusyOperation.EXPORTING
        startBackupOperationCard(
            title = "Exporting Backup",
            details = "Preparing export…",
            progressPercent = null,
            anchor = BackupOperationAnchor.EXPORT
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                NodeBackup.exportToUri(getApplication(), uri) { progress ->
                    viewModelScope.launch(Dispatchers.Main) {
                        updateBackupOperationCard(
                            details = progress.message,
                            progressPercent = progress.percent
                        )
                    }
                }
            }
            val msg = result.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
            busyOperation.value = null
            finishBackupOperationCard(msg)
            appendServiceLog(msg)
        }
    }

    fun import(uri: Uri) {
        busyOperation.value = BusyOperation.IMPORTING
        startBackupOperationCard(
            title = "Importing Backup",
            details = "Preparing import…",
            progressPercent = null,
            anchor = BackupOperationAnchor.IMPORT
        )
        val service = nodeService
        viewModelScope.launch {
            val importResult = withContext(Dispatchers.IO) {
                NodeBackup.importFromUri(getApplication(), uri) { progress ->
                    viewModelScope.launch(Dispatchers.Main) {
                        updateBackupOperationCard(
                            details = progress.message,
                            progressPercent = progress.percent
                        )
                    }
                }
            }
            val postInstallResult = if (importResult.isSuccess) {
                withContext(Dispatchers.IO) {
                    service?.runPostInstallNow()
                } ?: Result.failure(IllegalStateException("Service not available"))
            } else {
                Result.success(Unit)
            }
            val msg = when {
                importResult.isFailure ->
                    "Import failed: ${importResult.exceptionOrNull()?.message ?: "unknown error"}"
                postInstallResult.isFailure ->
                    "Import complete, post-install failed: ${postInstallResult.exceptionOrNull()?.message ?: "unknown error"}"
                else -> "Import complete"
            }
            busyOperation.value = null
            finishBackupOperationCard(msg)
            appendServiceLog(msg)
        }
    }

    fun installCustomZip(uri: Uri) {
        if (busyOperation.value != null) {
            postUserMessage("Wait for the current operation to finish.")
            return
        }
        busyOperation.value = BusyOperation.INSTALLING
        startCustomOperationCard(
            title = "Installing Custom ST",
            details = "Preparing archive…",
            progressPercent = null,
            cancelable = false,
            anchor = CustomOperationAnchor.ZIP_INSTALL
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                payload.installCustomFromZip(uri) { msg ->
                    viewModelScope.launch(Dispatchers.Main) {
                        updateCustomOperationCard(details = msg)
                    }
                }
            }
            refreshCustomInstallState()
            busyOperation.value = null
            val msg = result.fold(
                onSuccess = { "Custom ST installed successfully." },
                onFailure = { "Installation failed: ${it.message ?: "unknown error"}" }
            )
            finishCustomOperationCard(msg)
            appendServiceLog(msg)
        }
    }

    fun resetToDefault() {
        if (busyOperation.value != null) {
            postUserMessage("Wait for the current operation to finish.")
            return
        }
        busyOperation.value = BusyOperation.RESETTING
        startCustomOperationCard(
            title = "Resetting to Bundled Version",
            details = "Preparing reset…",
            progressPercent = null,
            cancelable = false,
            anchor = CustomOperationAnchor.RESET_TO_BUNDLED
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                payload.resetToDefault { msg ->
                    viewModelScope.launch(Dispatchers.Main) {
                        updateCustomOperationCard(details = msg)
                    }
                }
            }
            refreshCustomInstallState()
            busyOperation.value = null
            val msg = result.fold(
                onSuccess = { "Reset to default complete." },
                onFailure = { "Reset failed: ${it.message ?: "unknown error"}" }
            )
            finishCustomOperationCard(msg)
            appendServiceLog(msg)
        }
    }

    fun setCustomRepoInput(value: String) {
        customRepoInput.value = value
        customRepoValidationMessage.value = ""
    }

    fun selectCustomRepoRef(key: String) {
        selectedCustomRefKey.value = key
        customInstallValidationMessage.value = ""
    }

    fun loadCustomRepoRefs() {
        if (isLoadingCustomRefs.value || isDownloadingCustomSource.value) return
        if (busyOperation.value != null && busyOperation.value != BusyOperation.DOWNLOADING_CUSTOM_SOURCE) {
            postUserMessage("Wait for the current operation to finish.")
            return
        }
        val parsedRepo = parseGithubRepoInput(customRepoInput.value)
        if (parsedRepo == null) {
            customRepoValidationMessage.value = "Enter repo as owner/repo."
            customFeaturedRefs.value = emptyList()
            customAllRefs.value = emptyList()
            selectedCustomRefKey.value = null
            return
        }
        customRepoValidationMessage.value = ""
        isLoadingCustomRefs.value = true
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    fetchCustomRepoRefs(parsedRepo.first, parsedRepo.second)
                }
            }.onSuccess { refsResult ->
                val featured = refsResult.first.map { it.toUiOption() }
                val all = refsResult.second.map { it.toUiOption() }
                customFeaturedRefs.value = featured
                customAllRefs.value = all
                val selected = selectedCustomRefKey.value
                selectedCustomRefKey.value = when {
                    selected != null && all.any { it.key == selected } -> selected
                    featured.isNotEmpty() -> featured.first().key
                    all.isNotEmpty() -> all.first().key
                    else -> null
                }
                val resultMessage = when {
                    all.isEmpty() -> "No branches or tags found."
                    else -> "Loaded ${all.size} refs from ${parsedRepo.first}/${parsedRepo.second}."
                }
                postUserMessage(resultMessage)
            }.onFailure { error ->
                customFeaturedRefs.value = emptyList()
                customAllRefs.value = emptyList()
                selectedCustomRefKey.value = null
                postUserMessage("Failed to load refs: ${error.message ?: "unknown error"}")
                viewModelScope.launch {
                    appendServiceLog("custom-refs: failed: ${error.message ?: "unknown error"}")
                }
            }
            isLoadingCustomRefs.value = false
        }
    }

    fun startCustomRepoInstall() {
        if (isDownloadingCustomSource.value) return
        customInstallValidationMessage.value = ""
        if (busyOperation.value != null) {
            postUserMessage("Wait for the current operation to finish.")
            return
        }
        val parsedRepo = parseGithubRepoInput(customRepoInput.value)
        if (parsedRepo == null) {
            customRepoValidationMessage.value = "Enter repo as owner/repo."
            return
        }
        customRepoValidationMessage.value = ""
        val selectedRef = selectedCustomRef()
        if (selectedRef == null) {
            customInstallValidationMessage.value = "Select a branch or tag first."
            return
        }

        customSourceDownloadJob?.cancel()
        busyOperation.value = BusyOperation.DOWNLOADING_CUSTOM_SOURCE
        isDownloadingCustomSource.value = true
        startCustomOperationCard(
            title = "Installing Custom ST",
            details = "Downloading ${selectedRef.refType} ${selectedRef.refName}...",
            progressPercent = 0,
            cancelable = true,
            anchor = CustomOperationAnchor.GITHUB_INSTALL
        )

        customSourceDownloadJob = viewModelScope.launch {
            val (owner, repo) = parsedRepo
            val safeRepo = "${owner}_${repo}".replace(Regex("[^A-Za-z0-9._-]"), "_")
            val safeRef = selectedRef.refName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tempDir = AppPaths(getApplication()).updatesDir.also { it.mkdirs() }
            val zipFile = File(tempDir, "st-src-$safeRepo-$safeRef.zip")
            val archiveRefName = Uri.encode(selectedRef.refName)
            val downloadUrl = "https://codeload.github.com/$owner/$repo/zip/refs/${selectedRef.refTypePath}/$archiveRefName"

            try {
                withContext(Dispatchers.IO) {
                    downloadCustomSourceZip(
                        downloadUrl = downloadUrl,
                        outputFile = zipFile
                    ) { downloadedBytes, totalBytes ->
                        withContext(Dispatchers.Main) {
                            val progress = if (totalBytes != null && totalBytes > 0L) {
                                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                null
                            }
                            val downloadedLabel = formatByteCount(downloadedBytes)
                            val totalLabel = totalBytes?.let { formatByteCount(it) }
                            val details = if (totalLabel == null) {
                                "Downloading ${selectedRef.refType} ${selectedRef.refName}... $downloadedLabel"
                            } else {
                                "Downloading ${selectedRef.refType} ${selectedRef.refName}... $downloadedLabel / $totalLabel"
                            }
                            updateCustomOperationCard(
                                details = details,
                                progressPercent = progress,
                                cancelable = true
                            )
                        }
                    }
                }

                isDownloadingCustomSource.value = false
                busyOperation.value = BusyOperation.INSTALLING
                updateCustomOperationCard(
                    details = "Download complete. Installing...",
                    progressPercent = null,
                    cancelable = false
                )

                val result = withContext(Dispatchers.IO) {
                    payload.installCustomFromZipFile(
                        zipFile = zipFile,
                        onProgress = { msg ->
                            viewModelScope.launch(Dispatchers.Main) {
                                updateCustomOperationCard(details = msg)
                            }
                        },
                        sourceInfo = NodePayload.CustomInstallInfo(
                            repo = "$owner/$repo",
                            refType = selectedRef.refType,
                            refName = selectedRef.refName,
                            commitSha = selectedRef.commitSha
                        )
                    )
                }
                refreshCustomInstallState()
                val finalMessage = result.fold(
                    onSuccess = {
                        "Custom ST installed from ${selectedRef.refType}/${selectedRef.refName}."
                    },
                    onFailure = {
                        "Installation failed: ${it.message ?: "unknown error"}"
                    }
                )
                finishCustomOperationCard(finalMessage)
                appendServiceLog(finalMessage)
            } catch (_: CancellationException) {
                finishCustomOperationCard("Custom source download canceled.")
            } catch (error: Exception) {
                val detail = "custom-source-download: failed: ${error.message ?: "unknown error"}"
                Log.w(TAG, detail)
                appendServiceLog(detail)
                finishCustomOperationCard("Installation failed: ${error.message ?: "unknown error"}")
            } finally {
                if (zipFile.exists()) {
                    zipFile.delete()
                }
                isDownloadingCustomSource.value = false
                busyOperation.value = null
            }
        }
    }

    fun cancelCustomSourceDownload() {
        customSourceDownloadJob?.cancel()
    }

    fun removeUserData() {
        busyOperation.value = BusyOperation.REMOVING_DATA
        viewModelScope.launch {
            val paths = AppPaths(getApplication())
            withContext(Dispatchers.IO) {
                paths.configDir.deleteRecursively()
                paths.dataDir.deleteRecursively()
            }
            val msg = "User data removed."
            busyOperation.value = null
            postUserMessage(msg)
            appendServiceLog(msg)
        }
    }

    fun setAutoCheckForUpdates(enabled: Boolean) {
        autoCheckForUpdates.value = enabled
        updatePrefs.edit().putBoolean(PREF_AUTO_CHECK, enabled).apply()
    }

    fun setAutoOpenBrowserWhenReady(enabled: Boolean) {
        autoOpenBrowserWhenReady.value = enabled
        updatePrefs.edit().putBoolean(PREF_AUTO_OPEN_BROWSER, enabled).apply()
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        updateChannel.value = channel
        updatePrefs.edit().putString(PREF_CHANNEL, channel.storageValue).apply()
    }

    fun maybeAutoCheckForUpdates() {
        if (autoCheckAttempted) return
        autoCheckAttempted = true
        if (!autoCheckForUpdates.value) return
        if (!shouldRunAutoCheckNow()) return
        val now = System.currentTimeMillis()
        lastAutoCheckMs.value = now
        updatePrefs.edit().putLong(PREF_LAST_AUTO_CHECK_MS, now).apply()
        checkForUpdates("auto")
    }

    fun shouldShowAutoCheckOptInPrompt(): Boolean {
        if (autoCheckForUpdates.value) return false
        if (autoOptInPromptShown.value) return false
        val now = System.currentTimeMillis()
        return now >= firstLaunchMs + THREE_DAYS_MS
    }

    fun acceptAutoCheckOptInPrompt() {
        setAutoCheckForUpdates(true)
        autoOptInPromptShown.value = true
        updatePrefs.edit().putBoolean(PREF_AUTO_OPTIN_PROMPT_SHOWN, true).apply()
        postUserMessage("Automatic checks enabled.")
        checkForUpdates("manual")
    }

    fun dismissAutoCheckOptInPrompt() {
        autoOptInPromptShown.value = true
        updatePrefs.edit().putBoolean(PREF_AUTO_OPTIN_PROMPT_SHOWN, true).apply()
    }

    fun shouldShowUpdatePrompt(): Boolean {
        if (availableUpdate.value == null) return false
        val now = System.currentTimeMillis()
        return now >= updateDismissedUntilMs.value
    }

    fun availableUpdateVersionLabel(): String {
        return availableUpdate.value?.tagName ?: ""
    }

    fun isAvailableUpdateDownloaded(): Boolean {
        val candidate = availableUpdate.value ?: return false
        val downloadedTag = downloadedUpdateTag.value
        val downloadedPath = downloadedApkPath.value
        if (downloadedTag != candidate.tagName || downloadedPath.isNullOrBlank()) return false
        return File(downloadedPath).exists()
    }

    fun dismissAvailableUpdatePrompt() {
        val update = availableUpdate.value ?: return
        val now = System.currentTimeMillis()
        val until = now + THREE_DAYS_MS
        updateDismissedUntilMs.value = until
        updatePrefs.edit().putLong(PREF_UPDATE_DISMISSED_UNTIL_MS, until).apply()
        postUserMessage("Update ${update.tagName} dismissed for 72 hours.")
    }

    fun startAvailableUpdateDownload() {
        val update = availableUpdate.value ?: return
        val downloadUrl = update.apkAssetUrl
        if (downloadUrl.isNullOrBlank()) {
            postUserMessage("Update found, but this release does not include an Android install file.")
            return
        }
        if (isDownloadingUpdate.value) return

        updateDownloadJob?.cancel()
        isDownloadingUpdate.value = true
        downloadProgressPercent.value = 0
        updateBannerMessage.value = ""
        updateDownloadJob = viewModelScope.launch {
            try {
                val downloadedFile = withContext(Dispatchers.IO) {
                    downloadApk(
                        downloadUrl = downloadUrl,
                        tagName = update.tagName
                    ) { progress ->
                        withContext(Dispatchers.Main) {
                            downloadProgressPercent.value = progress
                        }
                    }
                }
                downloadedUpdateTag.value = update.tagName
                downloadedApkPath.value = downloadedFile.absolutePath
                updateBannerMessage.value = "Update ${update.tagName} downloaded. Ready to install."
                updateDismissedUntilMs.value = 0L
                updatePrefs.edit().putLong(PREF_UPDATE_DISMISSED_UNTIL_MS, 0L).apply()
            } catch (_: CancellationException) {
                postUserMessage("Update download canceled.")
                updateBannerMessage.value = "Tap Install to download and install ${update.tagName}."
            } catch (e: Exception) {
                val detail = "update-download: failed for ${update.tagName}: ${e.message ?: "unknown error"}"
                Log.w(TAG, detail)
                appendServiceLog(detail)
                postUserMessage("Update download failed. Please try again.")
                updateBannerMessage.value = "Tap Install to download and install ${update.tagName}."
            } finally {
                isDownloadingUpdate.value = false
                downloadProgressPercent.value = null
            }
        }
    }

    fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
    }

    fun installDownloadedUpdate(context: Context) {
        val candidate = availableUpdate.value
        if (candidate == null) {
            postUserMessage("No update is selected.")
            return
        }
        val apkPath = downloadedApkPath.value
        if (apkPath.isNullOrBlank()) {
            postUserMessage("Download the update first.")
            return
        }
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            postUserMessage("Downloaded APK was not found.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            updateBannerMessage.value = "Allow installs from this source, then tap Install now again."
            return
        }
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(installIntent)
        }.onSuccess {
            postUserMessage("Installer opened. Confirm installation to update.")
        }.onFailure { error ->
            postUserMessage("Couldn't open installer: ${error.message ?: "unknown error"}")
        }
    }

    fun checkForUpdates(reason: String = "manual") {
        if (isCheckingForUpdates.value) return
        isCheckingForUpdates.value = true
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        performUpdateCheck(updateChannel.value)
                    }
                }

                val detailMessage = outcome.fold(
                    onSuccess = { result ->
                        if (result.latest == null) {
                            "update-check[$reason]: channel=${result.channel.storageValue}, no matching GitHub release found"
                        } else {
                            val releaseName = result.latest.releaseName?.takeIf { it.isNotBlank() } ?: "-"
                            val apkName = result.latest.apkAssetName ?: "-"
                            val apkUrl = result.latest.apkAssetUrl ?: "-"
                            val url = result.latest.htmlUrl ?: "-"
                            "update-check[$reason]: channel=${result.channel.storageValue}, current=${result.currentVersionName}, " +
                                "latest=${result.latest.tagName}, prerelease=${result.latest.prerelease}, " +
                                "releaseName=$releaseName, apk=$apkName, apkUrl=$apkUrl, " +
                                "updateNeeded=${result.updateNeeded}, url=$url"
                        }
                    },
                    onFailure = { error ->
                        "update-check[$reason]: failed: ${error.message ?: "unknown error"}"
                    }
                )

                outcome.onSuccess { result ->
                    val latest = result.latest
                    when {
                        latest == null -> {
                            availableUpdate.value = null
                            updateBannerMessage.value = ""
                            if (reason == "manual") {
                                postUserMessage("No published release was found.")
                            }
                        }
                        !result.updateNeeded -> {
                            availableUpdate.value = null
                            updateBannerMessage.value = ""
                            if (reason == "manual") {
                                postUserMessage("You're already on the latest version.")
                            }
                        }
                        latest.apkAssetUrl.isNullOrBlank() -> {
                            availableUpdate.value = null
                            updateBannerMessage.value = ""
                            if (reason == "manual") {
                                postUserMessage("Update found, but this release does not include an Android install file.")
                            }
                        }
                        else -> {
                            availableUpdate.value = latest
                            updateDismissedUntilMs.value = 0L
                            updatePrefs.edit().putLong(PREF_UPDATE_DISMISSED_UNTIL_MS, 0L).apply()
                            if (reason == "manual") {
                                postUserMessage("New update ${latest.tagName} is available.")
                            }
                            if (isAvailableUpdateDownloaded()) {
                                updateBannerMessage.value = "Update ${latest.tagName} is ready to install."
                            } else {
                                updateBannerMessage.value = "Tap Install to download and install ${latest.tagName}."
                            }
                        }
                    }
                }.onFailure {
                    if (reason == "manual") {
                        postUserMessage("Update check failed. Please try again.")
                    }
                }

                Log.i(TAG, detailMessage)
                appendServiceLog(detailMessage)
            } finally {
                isCheckingForUpdates.value = false
            }
        }
    }

    fun showTransientMessage(message: String) {
        postUserMessage(message)
    }

    private fun ensureFirstLaunchTimestamp(): Long {
        val existing = updatePrefs.getLong(PREF_FIRST_LAUNCH_MS, 0L)
        if (existing > 0L) return existing
        val now = System.currentTimeMillis()
        updatePrefs.edit().putLong(PREF_FIRST_LAUNCH_MS, now).apply()
        return now
    }

    private fun resolveInitialUpdateChannel(): UpdateChannel {
        val storedValue = updatePrefs.getString(PREF_CHANNEL, null)
        if (storedValue != null) {
            return UpdateChannel.fromStorage(storedValue)
        }

        val inferred = inferChannelFromCurrentVersion(getCurrentVersionName())
        updatePrefs.edit().putString(PREF_CHANNEL, inferred.storageValue).apply()
        return inferred
    }

    private fun inferChannelFromCurrentVersion(versionName: String): UpdateChannel {
        val parsed = parseVersion(versionName)
        if (parsed != null) {
            return if (parsed.preRelease.isEmpty()) {
                UpdateChannel.RELEASE
            } else {
                UpdateChannel.PRERELEASE
            }
        }

        val normalized = normalizeVersion(versionName).substringBefore('+')
        return if (normalized.contains('-')) {
            UpdateChannel.PRERELEASE
        } else {
            UpdateChannel.RELEASE
        }
    }

    private fun refreshCustomInstallState() {
        isCustomInstalled.value = payload.isCustomInstalled()
        customInstallLabel.value = payload.getCustomInstallLabel()
    }

    private data class SelectedCustomRef(
        val refType: String,
        val refTypePath: String,
        val refName: String,
        val commitSha: String?
    )

    private fun selectedCustomRef(): SelectedCustomRef? {
        val selectedKey = selectedCustomRefKey.value ?: return null
        val option = customAllRefs.value.firstOrNull { it.key == selectedKey }
            ?: customFeaturedRefs.value.firstOrNull { it.key == selectedKey }
            ?: return null
        val type = when (option.refType) {
            GithubRefType.BRANCH.storageValue -> GithubRefType.BRANCH
            GithubRefType.TAG.storageValue -> GithubRefType.TAG
            else -> return null
        }
        return SelectedCustomRef(
            refType = option.refType,
            refTypePath = type.archivePrefix,
            refName = option.refName,
            commitSha = option.commitSha
        )
    }

    private fun parseGithubRepoInput(raw: String): Pair<String, String>? {
        if (raw.isBlank()) return null
        var normalized = raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
        if (normalized.startsWith("github.com/", ignoreCase = true)) {
            normalized = normalized.substringAfter('/')
        }
        normalized = normalized.removeSuffix("/").removeSuffix(".git")
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0].trim()
        val repo = parts[1].trim()
        val segmentRegex = Regex("^[A-Za-z0-9._-]+$")
        if (!segmentRegex.matches(owner) || !segmentRegex.matches(repo)) return null
        return owner to repo
    }

    private fun postUserMessage(message: String) {
        if (message.isBlank()) return
        if (!_snackbarMessages.tryEmit(message)) {
            viewModelScope.launch {
                _snackbarMessages.emit(message)
            }
        }
    }

    private fun startCustomOperationCard(
        title: String,
        details: String,
        progressPercent: Int?,
        cancelable: Boolean,
        anchor: CustomOperationAnchor
    ) {
        customOperationCardToken += 1
        customOperationCardVisible.value = true
        customOperationCardTitle.value = title
        customOperationCardDetails.value = details
        customOperationCardProgressPercent.value = progressPercent
        customOperationCardCancelable.value = cancelable
        customOperationCardAnchor.value = anchor
    }

    private fun updateCustomOperationCard(
        details: String? = null,
        progressPercent: Int? = customOperationCardProgressPercent.value,
        cancelable: Boolean? = null
    ) {
        if (details != null) {
            customOperationCardDetails.value = details
        }
        customOperationCardProgressPercent.value = progressPercent
        if (cancelable != null) {
            customOperationCardCancelable.value = cancelable
        }
    }

    private fun finishCustomOperationCard(finalMessage: String) {
        val token = customOperationCardToken
        customOperationCardDetails.value = finalMessage
        customOperationCardProgressPercent.value = null
        customOperationCardCancelable.value = false
        viewModelScope.launch {
            delay(1500)
            if (token == customOperationCardToken) {
                customOperationCardVisible.value = false
                customOperationCardTitle.value = ""
                customOperationCardDetails.value = ""
            }
        }
    }

    private fun startBackupOperationCard(
        title: String,
        details: String,
        progressPercent: Int?,
        anchor: BackupOperationAnchor
    ) {
        backupOperationCardToken += 1
        backupOperationCardVisible.value = true
        backupOperationCardTitle.value = title
        backupOperationCardDetails.value = details
        backupOperationCardProgressPercent.value = progressPercent
        backupOperationCardAnchor.value = anchor
    }

    private fun updateBackupOperationCard(
        details: String? = null,
        progressPercent: Int? = backupOperationCardProgressPercent.value
    ) {
        if (details != null) {
            backupOperationCardDetails.value = details
        }
        backupOperationCardProgressPercent.value = progressPercent
    }

    private fun finishBackupOperationCard(finalMessage: String) {
        val token = backupOperationCardToken
        backupOperationCardDetails.value = finalMessage
        backupOperationCardProgressPercent.value = null
        viewModelScope.launch {
            delay(1500)
            if (token == backupOperationCardToken) {
                backupOperationCardVisible.value = false
                backupOperationCardTitle.value = ""
                backupOperationCardDetails.value = ""
            }
        }
    }

    private fun fetchCustomRepoRefs(
        owner: String,
        repo: String
    ): Pair<List<GithubRepoRef>, List<GithubRepoRef>> {
        val defaultBranch = fetchRepoDefaultBranch(owner, repo)
        val branches = fetchRepoBranches(owner, repo)
        val tags = fetchRepoTags(owner, repo)
        val releaseTags = fetchReleaseTagNames(owner, repo)

        val sortedBranches = branches.sortedWith(
            compareBy<GithubRepoRef> { branchSortRank(it.name, defaultBranch) }
                .thenBy { it.name.lowercase() }
        )
        val sortedTags = tags.sortedWith { left, right ->
            compareTagNamesForDisplay(left.name, right.name)
        }
        val all = buildList {
            addAll(sortedBranches)
            addAll(sortedTags)
        }
        val featured = pickFeaturedRefs(
            defaultBranch = defaultBranch,
            releaseTags = releaseTags,
            allRefs = all
        )
        return featured to all
    }

    private fun fetchRepoDefaultBranch(owner: String, repo: String): String? {
        val body = githubApiGet(
            "https://api.github.com/repos/$owner/$repo"
        )
        return JSONObject(body).optString("default_branch", "").ifBlank { null }
    }

    private fun fetchRepoBranches(owner: String, repo: String): List<GithubRepoRef> {
        val results = mutableListOf<GithubRepoRef>()
        for (page in 1..MAX_GITHUB_PAGES) {
            val body = githubApiGet(
                "https://api.github.com/repos/$owner/$repo/branches?per_page=100&page=$page"
            )
            val entries = JSONArray(body)
            if (entries.length() == 0) break
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val name = item.optString("name", "").trim()
                if (name.isBlank()) continue
                val sha = item.optJSONObject("commit")?.optString("sha", "")?.trim()?.ifBlank { null }
                results += GithubRepoRef(
                    type = GithubRefType.BRANCH,
                    name = name,
                    commitSha = sha
                )
            }
            if (entries.length() < 100) break
        }
        return results
    }

    private fun fetchRepoTags(owner: String, repo: String): List<GithubRepoRef> {
        val results = mutableListOf<GithubRepoRef>()
        for (page in 1..MAX_GITHUB_PAGES) {
            val body = githubApiGet(
                "https://api.github.com/repos/$owner/$repo/tags?per_page=100&page=$page"
            )
            val entries = JSONArray(body)
            if (entries.length() == 0) break
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val name = item.optString("name", "").trim()
                if (name.isBlank()) continue
                val sha = item.optJSONObject("commit")?.optString("sha", "")?.trim()?.ifBlank { null }
                results += GithubRepoRef(
                    type = GithubRefType.TAG,
                    name = name,
                    commitSha = sha
                )
            }
            if (entries.length() < 100) break
        }
        return results
    }

    private fun fetchReleaseTagNames(owner: String, repo: String): List<String> {
        val body = githubApiGet(
            "https://api.github.com/repos/$owner/$repo/releases?per_page=10"
        )
        val entries = JSONArray(body)
        val tags = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optBoolean("draft", false)) continue
            val tag = item.optString("tag_name", "").trim()
            if (tag.isBlank()) continue
            tags += tag
        }
        return tags
    }

    private fun pickFeaturedRefs(
        defaultBranch: String?,
        releaseTags: List<String>,
        allRefs: List<GithubRepoRef>
    ): List<GithubRepoRef> {
        if (allRefs.isEmpty()) return emptyList()
        val byKey = allRefs.associateBy { it.key }
        val featured = mutableListOf<GithubRepoRef>()
        fun add(type: GithubRefType, name: String?) {
            val normalized = name?.trim().orEmpty()
            if (normalized.isBlank()) return
            val key = "${type.storageValue}:$normalized"
            val found = byKey[key] ?: return
            if (featured.none { it.key == found.key }) {
                featured += found
            }
        }

        add(GithubRefType.BRANCH, "staging")
        add(GithubRefType.BRANCH, "release")
        add(GithubRefType.BRANCH, defaultBranch)
        for (tag in releaseTags.take(3)) {
            add(GithubRefType.TAG, tag)
        }
        if (featured.size < MAX_FEATURED_REFS) {
            add(GithubRefType.BRANCH, "main")
            add(GithubRefType.BRANCH, "master")
            add(GithubRefType.BRANCH, "develop")
        }
        if (featured.size < MAX_FEATURED_REFS) {
            for (ref in allRefs) {
                if (featured.none { it.key == ref.key }) {
                    featured += ref
                    if (featured.size >= MAX_FEATURED_REFS) break
                }
            }
        }
        return featured.take(MAX_FEATURED_REFS)
    }

    private fun branchSortRank(name: String, defaultBranch: String?): Int {
        if (!defaultBranch.isNullOrBlank() && name == defaultBranch) return 2
        return when (name) {
            "staging" -> 0
            "release" -> 1
            "main" -> 3
            "master" -> 4
            "develop" -> 5
            else -> 10
        }
    }

    private fun GithubRepoRef.toUiOption(): CustomRepoRefOption {
        val shortSha = commitSha?.take(7)
        val shaLabel = if (shortSha.isNullOrBlank()) "" else " ($shortSha)"
        return CustomRepoRefOption(
            key = key,
            label = "${type.storageValue}: $name$shaLabel",
            refType = type.storageValue,
            refName = name,
            commitSha = commitSha
        )
    }

    private fun compareTagNamesForDisplay(left: String, right: String): Int {
        val leftParsed = parseVersion(left)
        val rightParsed = parseVersion(right)
        if (leftParsed != null && rightParsed != null) {
            val semverCompare = compareVersions(rightParsed, leftParsed)
            if (semverCompare != 0) return semverCompare
        } else if (leftParsed != null) {
            return -1
        } else if (rightParsed != null) {
            return 1
        }
        return naturalCompareAscending(right.lowercase(), left.lowercase())
    }

    private fun naturalCompareAscending(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val lc = left[i]
            val rc = right[j]
            if (lc.isDigit() && rc.isDigit()) {
                val iStart = i
                val jStart = j
                while (i < left.length && left[i].isDigit()) i++
                while (j < right.length && right[j].isDigit()) j++
                val leftNum = left.substring(iStart, i)
                val rightNum = right.substring(jStart, j)
                val cmp = compareNumericStrings(leftNum, rightNum)
                if (cmp != 0) return cmp
                continue
            }
            if (lc != rc) return lc.compareTo(rc)
            i++
            j++
        }
        return left.length.compareTo(right.length)
    }

    private fun shouldRunAutoCheckNow(): Boolean {
        val now = System.currentTimeMillis()
        if (now < updateDismissedUntilMs.value) {
            return false
        }
        val elapsed = now - lastAutoCheckMs.value
        return elapsed >= ONE_DAY_MS
    }

    private suspend fun downloadCustomSourceZip(
        downloadUrl: String,
        outputFile: File,
        onProgress: suspend (Long, Long?) -> Unit
    ) {
        val tempFile = File(outputFile.parentFile, "${outputFile.name}.part")
        if (tempFile.exists()) tempFile.delete()

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "st-android-custom-st")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val shortBody = body.replace('\n', ' ').take(240)
                throw IllegalStateException("Download HTTP $status: $shortBody")
            }

            val totalBytes = sequenceOf(
                connection.contentLengthLong,
                connection.getHeaderFieldLong("X-Linked-Size", -1L),
                connection.getHeaderFieldLong("x-goog-stored-content-length", -1L),
                connection.getHeaderFieldLong("Content-Length", -1L)
            ).firstOrNull { it > 0L }
            var downloadedBytes = 0L
            var lastProgress = -1
            var lastReportedBytesBucket = -1L

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes != null) {
                            val progress = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(downloadedBytes, totalBytes)
                            }
                        } else {
                            val bucket = downloadedBytes / (512L * 1024L)
                            if (bucket != lastReportedBytesBucket) {
                                lastReportedBytesBucket = bucket
                                onProgress(downloadedBytes, null)
                            }
                        }
                    }
                }
            }
            if (totalBytes != null && lastProgress < 100) {
                onProgress(totalBytes, totalBytes)
            }

            if (outputFile.exists()) outputFile.delete()
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
            connection.disconnect()
        }
    }

    private suspend fun downloadApk(
        downloadUrl: String,
        tagName: String,
        onProgress: suspend (Int?) -> Unit
    ): File {
        val paths = AppPaths(getApplication())
        val updatesDir = paths.updatesDir
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }
        val safeTag = tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outputFile = File(updatesDir, "st-android-$safeTag.apk")
        val tempFile = File(updatesDir, "st-android-$safeTag.apk.part")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "st-android-updater")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val shortBody = body.replace('\n', ' ').take(240)
                throw IllegalStateException("Download HTTP $status: $shortBody")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            var downloadedBytes = 0L
            var lastProgress = -1

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes != null) {
                            val progress = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        } else {
                            onProgress(null)
                        }
                    }
                }
            }

            if (outputFile.exists()) {
                outputFile.delete()
            }
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }
            return outputFile
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            connection.disconnect()
        }
    }

    private fun performUpdateCheck(channel: UpdateChannel): UpdateCheckOutcome {
        val currentVersion = getCurrentVersionName()
        val release = fetchLatestRelease(channel)
        val updateNeeded = if (release == null) {
            false
        } else {
            isRemoteVersionNewer(currentVersion, release.tagName)
        }
        return UpdateCheckOutcome(
            channel = channel,
            currentVersionName = currentVersion,
            latest = release,
            updateNeeded = updateNeeded
        )
    }

    private fun getCurrentVersionName(): String {
        val context = getApplication<Application>()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName ?: "unknown"
    }

    private fun fetchLatestRelease(channel: UpdateChannel): GithubReleaseInfo? {
        return when (channel) {
            UpdateChannel.RELEASE -> {
                val body = githubApiGet(
                    "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
                )
                parseReleaseObject(JSONObject(body))
            }

            UpdateChannel.PRERELEASE -> {
                val body = githubApiGet(
                    "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=20"
                )
                val releases = JSONArray(body)
                var chosen: GithubReleaseInfo? = null
                for (i in 0 until releases.length()) {
                    val item = releases.optJSONObject(i) ?: continue
                    val draft = item.optBoolean("draft", false)
                    val prerelease = item.optBoolean("prerelease", false)
                    if (draft || !prerelease) continue
                    chosen = parseReleaseObject(item)
                    break
                }
                chosen
            }
        }
    }

    private fun parseReleaseObject(json: JSONObject): GithubReleaseInfo? {
        if (json.optBoolean("draft", false)) return null
        val tagName = json.optString("tag_name", "").trim()
        if (tagName.isBlank()) return null

        val assets = json.optJSONArray("assets")
        var apkAssetName: String? = null
        var apkAssetUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkAssetName = name
                    apkAssetUrl = asset.optString("browser_download_url", "").ifBlank { null }
                    break
                }
            }
        }

        return GithubReleaseInfo(
            tagName = tagName,
            releaseName = json.optString("name", "").ifBlank { null },
            prerelease = json.optBoolean("prerelease", false),
            htmlUrl = json.optString("html_url", "").ifBlank { null },
            apkAssetName = apkAssetName,
            apkAssetUrl = apkAssetUrl
        )
    }

    private fun githubApiGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "st-android-update-check")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val shortenedBody = body.replace('\n', ' ').take(240)
                throw IllegalStateException("GitHub API HTTP $status: $shortenedBody")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun isRemoteVersionNewer(currentVersion: String, remoteTag: String): Boolean {
        val current = parseVersion(currentVersion)
        val remote = parseVersion(remoteTag)
        if (current == null || remote == null) {
            return normalizeVersion(remoteTag) != normalizeVersion(currentVersion)
        }
        return compareVersions(remote, current) > 0
    }

    private fun parseVersion(raw: String): ParsedVersion? {
        val normalized = normalizeVersion(raw)
        val preBuild = normalized.substringBefore('+')
        val core = preBuild.substringBefore('-')
        val preReleaseRaw = preBuild.substringAfter('-', "")
        val preRelease = if (preReleaseRaw.isBlank()) {
            emptyList()
        } else {
            preReleaseRaw.split('.').filter { it.isNotBlank() }
        }
        val segments = core.split('.')
        if (segments.size != 3) return null

        val major = segments.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = segments.getOrNull(1)?.toIntOrNull() ?: return null
        val patch = segments.getOrNull(2)?.toIntOrNull() ?: return null
        return ParsedVersion(major = major, minor = minor, patch = patch, preRelease = preRelease)
    }

    private fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    private fun compareVersions(left: ParsedVersion, right: ParsedVersion): Int {
        if (left.major != right.major) return left.major.compareTo(right.major)
        if (left.minor != right.minor) return left.minor.compareTo(right.minor)
        if (left.patch != right.patch) return left.patch.compareTo(right.patch)

        if (left.preRelease.isEmpty() && right.preRelease.isEmpty()) return 0
        if (left.preRelease.isEmpty()) return 1
        if (right.preRelease.isEmpty()) return -1

        val shared = minOf(left.preRelease.size, right.preRelease.size)
        for (i in 0 until shared) {
            val cmp = comparePreReleaseIdentifier(left.preRelease[i], right.preRelease[i])
            if (cmp != 0) return cmp
        }

        return left.preRelease.size.compareTo(right.preRelease.size)
    }

    private fun comparePreReleaseIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all { it.isDigit() }
        val rightNumeric = right.all { it.isDigit() }

        return when {
            leftNumeric && rightNumeric -> compareNumericStrings(left, right)
            leftNumeric && !rightNumeric -> -1
            !leftNumeric && rightNumeric -> 1
            else -> left.compareTo(right)
        }
    }

    private fun compareNumericStrings(left: String, right: String): Int {
        val leftNorm = left.trimStart('0').ifEmpty { "0" }
        val rightNorm = right.trimStart('0').ifEmpty { "0" }
        if (leftNorm.length != rightNorm.length) {
            return leftNorm.length.compareTo(rightNorm.length)
        }
        return leftNorm.compareTo(rightNorm)
    }

    private fun formatByteCount(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    private suspend fun appendServiceLog(message: String) {
        withContext(Dispatchers.IO) {
            val logsDir = AppPaths(getApplication()).logsDir
            if (!logsDir.exists()) logsDir.mkdirs()
            val logFile = File(logsDir, "service.log")
            logFile.appendText(formatServiceLogLine(message), Charsets.UTF_8)
        }
    }
}

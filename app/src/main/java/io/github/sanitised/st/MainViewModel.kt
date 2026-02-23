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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class BusyOperation {
    EXPORTING, IMPORTING, INSTALLING, RESETTING, REMOVING_DATA
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "UpdateCheck"
        private const val UPDATE_PREFS_NAME = "updates"
        private const val PREF_AUTO_CHECK = "auto_check"
        private const val PREF_CHANNEL = "channel"
        private const val PREF_FIRST_LAUNCH_MS = "first_launch_ms"
        private const val PREF_AUTO_OPTIN_PROMPT_SHOWN = "auto_optin_prompt_shown"
        private const val PREF_LAST_AUTO_CHECK_MS = "last_auto_check_ms"
        private const val PREF_UPDATE_DISMISSED_UNTIL_MS = "update_dismissed_until_ms"
        private const val GITHUB_OWNER = "Sanitised"
        private const val GITHUB_REPO = "ST-android"
        private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
        private const val THREE_DAYS_MS = 72L * 60L * 60L * 1000L
    }

    private val updatePrefs = application.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)

    internal val busyOperation = mutableStateOf<BusyOperation?>(null)
    val backupStatus = mutableStateOf("")
    val customStatus = mutableStateOf("")
    val removeDataStatus = mutableStateOf("")
    val isCustomInstalled = mutableStateOf(
        NodePayload(application).isCustomInstalled()
    )
    val autoCheckForUpdates = mutableStateOf(
        updatePrefs.getBoolean(PREF_AUTO_CHECK, false)
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
    val updateCheckStatus = mutableStateOf("")
    private val availableUpdate = mutableStateOf<GithubReleaseInfo?>(null)
    val isDownloadingUpdate = mutableStateOf(false)
    val downloadProgressPercent = mutableStateOf<Int?>(null)
    val updateBannerMessage = mutableStateOf("")
    val downloadedUpdateTag = mutableStateOf<String?>(null)
    val downloadedApkPath = mutableStateOf<String?>(null)

    private var autoCheckAttempted = false
    private var updateDownloadJob: Job? = null

    // Updated by MainActivity when the service connection changes.
    var nodeService: NodeService? = null

    override fun onCleared() {
        updateDownloadJob?.cancel()
        super.onCleared()
    }

    val busyMessage: String
        get() = when (busyOperation.value) {
            BusyOperation.EXPORTING -> "Exporting data…"
            BusyOperation.IMPORTING -> "Importing data…"
            BusyOperation.INSTALLING -> "Installing custom ST…"
            BusyOperation.RESETTING -> "Resetting to default…"
            BusyOperation.REMOVING_DATA -> "Removing user data…"
            null -> ""
        }

    fun export(uri: Uri) {
        busyOperation.value = BusyOperation.EXPORTING
        backupStatus.value = "Exporting..."
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                NodeBackup.exportToUri(getApplication(), uri)
            }
            val msg = result.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
            backupStatus.value = msg
            busyOperation.value = null
            appendServiceLog(msg)
        }
    }

    fun import(uri: Uri) {
        busyOperation.value = BusyOperation.IMPORTING
        backupStatus.value = "Importing..."
        val service = nodeService
        viewModelScope.launch {
            val importResult = withContext(Dispatchers.IO) {
                NodeBackup.importFromUri(getApplication(), uri)
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
            backupStatus.value = msg
            busyOperation.value = null
            appendServiceLog(msg)
        }
    }

    fun installCustomZip(uri: Uri) {
        busyOperation.value = BusyOperation.INSTALLING
        customStatus.value = "Starting…"
        viewModelScope.launch {
            val payload = NodePayload(getApplication())
            val result = withContext(Dispatchers.IO) {
                payload.installCustomFromZip(uri) { msg ->
                    viewModelScope.launch(Dispatchers.Main) {
                        customStatus.value = msg
                    }
                }
            }
            isCustomInstalled.value = payload.isCustomInstalled()
            busyOperation.value = null
            val msg = result.fold(
                onSuccess = { "Custom ST installed successfully." },
                onFailure = { "Installation failed: ${it.message ?: "unknown error"}" }
            )
            customStatus.value = msg
            appendServiceLog(msg)
        }
    }

    fun resetToDefault() {
        busyOperation.value = BusyOperation.RESETTING
        customStatus.value = "Resetting…"
        viewModelScope.launch {
            val payload = NodePayload(getApplication())
            val result = withContext(Dispatchers.IO) {
                payload.resetToDefault()
            }
            isCustomInstalled.value = payload.isCustomInstalled()
            busyOperation.value = null
            val msg = result.fold(
                onSuccess = { "Reset to default complete." },
                onFailure = { "Reset failed: ${it.message ?: "unknown error"}" }
            )
            customStatus.value = msg
            appendServiceLog(msg)
        }
    }

    fun removeUserData() {
        busyOperation.value = BusyOperation.REMOVING_DATA
        removeDataStatus.value = "Removing data…"
        viewModelScope.launch {
            val paths = AppPaths(getApplication())
            withContext(Dispatchers.IO) {
                paths.configDir.deleteRecursively()
                paths.dataDir.deleteRecursively()
            }
            val msg = "User data removed."
            removeDataStatus.value = msg
            busyOperation.value = null
            appendServiceLog(msg)
        }
    }

    fun setAutoCheckForUpdates(enabled: Boolean) {
        autoCheckForUpdates.value = enabled
        updatePrefs.edit().putBoolean(PREF_AUTO_CHECK, enabled).apply()
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
        updateCheckStatus.value = "Automatic checks enabled."
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
        updateBannerMessage.value = "Update ${update.tagName} dismissed for 72 hours."
    }

    fun startAvailableUpdateDownload() {
        val update = availableUpdate.value ?: return
        val downloadUrl = update.apkAssetUrl
        if (downloadUrl.isNullOrBlank()) {
            updateBannerMessage.value = "Update found, but APK asset is missing."
            return
        }
        if (isDownloadingUpdate.value) return

        updateDownloadJob?.cancel()
        isDownloadingUpdate.value = true
        downloadProgressPercent.value = 0
        updateBannerMessage.value = "Downloading update ${update.tagName}..."
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
                updateBannerMessage.value = "Update download canceled."
            } catch (e: Exception) {
                val detail = "update-download: failed for ${update.tagName}: ${e.message ?: "unknown error"}"
                Log.w(TAG, detail)
                appendServiceLog(detail)
                updateBannerMessage.value = "Update download failed. Please try again."
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
            updateBannerMessage.value = "No update is selected."
            return
        }
        val apkPath = downloadedApkPath.value
        if (apkPath.isNullOrBlank()) {
            updateBannerMessage.value = "Download the update first."
            return
        }
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            updateBannerMessage.value = "Downloaded APK was not found."
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
            updateBannerMessage.value = "Allow install permission, then tap Install now again."
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
            updateBannerMessage.value = "Installer opened. Confirm installation to update."
        }.onFailure { error ->
            updateBannerMessage.value = "Couldn't open installer: ${error.message ?: "unknown error"}"
        }
    }

    fun checkForUpdates(reason: String = "manual") {
        if (isCheckingForUpdates.value) return
        isCheckingForUpdates.value = true
        updateCheckStatus.value = "Checking for updates..."
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
                            updateCheckStatus.value = "No published release was found."
                        }
                        !result.updateNeeded -> {
                            availableUpdate.value = null
                            updateCheckStatus.value = "You're already on the latest version."
                        }
                        latest.apkAssetUrl.isNullOrBlank() -> {
                            availableUpdate.value = null
                            updateCheckStatus.value = "Update found, but no APK file is attached."
                        }
                        else -> {
                            availableUpdate.value = latest
                            updateDismissedUntilMs.value = 0L
                            updatePrefs.edit().putLong(PREF_UPDATE_DISMISSED_UNTIL_MS, 0L).apply()
                            updateCheckStatus.value = "New update ${latest.tagName} is available."
                            if (isAvailableUpdateDownloaded()) {
                                updateBannerMessage.value = "Update ${latest.tagName} is ready to install."
                            } else {
                                updateBannerMessage.value = "Tap Install to download and install ${latest.tagName}."
                            }
                        }
                    }
                }.onFailure {
                    updateCheckStatus.value = "Update check failed. Please try again."
                }

                Log.i(TAG, detailMessage)
                appendServiceLog(detailMessage)
            } finally {
                isCheckingForUpdates.value = false
            }
        }
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

    private fun shouldRunAutoCheckNow(): Boolean {
        val now = System.currentTimeMillis()
        if (now < updateDismissedUntilMs.value) {
            return false
        }
        val elapsed = now - lastAutoCheckMs.value
        return elapsed >= ONE_DAY_MS
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

    private suspend fun appendServiceLog(message: String) {
        withContext(Dispatchers.IO) {
            val logsDir = AppPaths(getApplication()).logsDir
            if (!logsDir.exists()) logsDir.mkdirs()
            val logFile = File(logsDir, "service.log")
            logFile.appendText("${System.currentTimeMillis()}: $message\n", Charsets.UTF_8)
        }
    }
}

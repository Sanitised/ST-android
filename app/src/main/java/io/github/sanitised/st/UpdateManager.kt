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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

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

internal class UpdateManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val postUserMessage: (String) -> Unit,
    private val appendServiceLog: suspend (String) -> Unit
) {
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
        private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024
        private const val UNKNOWN_LENGTH_PROGRESS_STEP_BYTES = 512L * 1024L
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
        private const val THREE_DAYS_MS = 72L * 60L * 60L * 1000L
    }

    private val updatePrefs = application.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
    private val firstLaunchMs = ensureFirstLaunchTimestamp()
    private var autoCheckAttempted = false
    private var updateDownloadJob: Job? = null

    val autoCheckForUpdates = mutableStateOf(
        updatePrefs.getBoolean(PREF_AUTO_CHECK, false)
    )
    val autoOpenBrowserWhenReady = mutableStateOf(
        updatePrefs.getBoolean(PREF_AUTO_OPEN_BROWSER, true)
    )
    val updateChannel = mutableStateOf(
        resolveInitialUpdateChannel()
    )
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

    fun onCleared() {
        updateDownloadJob?.cancel()
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
        updateDownloadJob = scope.launch {
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
        scope.launch {
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
        val parsed = Versioning.parseVersion(versionName)
        if (parsed != null) {
            return if (parsed.preRelease.isEmpty()) {
                UpdateChannel.RELEASE
            } else {
                UpdateChannel.PRERELEASE
            }
        }

        val normalized = Versioning.normalizeVersion(versionName).substringBefore('+')
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
        val paths = AppPaths(application)
        val updatesDir = paths.updatesDir
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }
        val safeTag = tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outputFile = File(updatesDir, "st-android-$safeTag.apk")
        HttpDownloader.downloadToFile(
            downloadUrl = downloadUrl,
            outputFile = outputFile,
            userAgent = "st-android-updater",
            bufferSize = DOWNLOAD_BUFFER_SIZE,
            unknownLengthProgressStepBytes = UNKNOWN_LENGTH_PROGRESS_STEP_BYTES
        ) { downloadedBytes, totalBytes ->
            if (totalBytes != null && totalBytes > 0L) {
                val progress = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                onProgress(progress)
            } else {
                onProgress(null)
            }
        }
        return outputFile
    }

    private fun performUpdateCheck(channel: UpdateChannel): UpdateCheckOutcome {
        val currentVersion = getCurrentVersionName()
        val release = fetchLatestRelease(channel)
        val updateNeeded = if (release == null) {
            false
        } else {
            Versioning.isRemoteVersionNewer(currentVersion, release.tagName)
        }
        return UpdateCheckOutcome(
            channel = channel,
            currentVersionName = currentVersion,
            latest = release,
            updateNeeded = updateNeeded
        )
    }

    private fun getCurrentVersionName(): String {
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
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
}

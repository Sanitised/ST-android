package io.github.sanitised.st

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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

internal class CustomInstallManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val getBusyOperation: () -> BusyOperation?,
    private val setBusyOperation: (BusyOperation?) -> Unit,
    private val postUserMessage: (String) -> Unit,
    private val appendServiceLog: suspend (String) -> Unit
) {
    companion object {
        private const val TAG = "CustomInstall"
        private const val DEFAULT_CUSTOM_ST_REPO = "SillyTavern/SillyTavern"
        private const val MAX_GITHUB_PAGES = 5
        private const val MAX_FEATURED_REFS = 6
        private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024
        private const val UNKNOWN_LENGTH_PROGRESS_STEP_BYTES = 512L * 1024L
    }

    private data class SelectedCustomRef(
        val refType: String,
        val refTypePath: String,
        val refName: String,
        val commitSha: String?
    )

    private val payload = NodePayload(application)
    private val operationCardController = OperationCardController(scope)
    private var customSourceDownloadJob: Job? = null

    val isCustomInstalled = mutableStateOf(payload.isCustomInstalled())
    val customInstallLabel = mutableStateOf(payload.getCustomInstallLabel())
    val customRepoInput = mutableStateOf(DEFAULT_CUSTOM_ST_REPO)
    val isLoadingCustomRefs = mutableStateOf(false)
    val customRepoValidationMessage = mutableStateOf("")
    val customInstallValidationMessage = mutableStateOf("")
    val customFeaturedRefs = mutableStateOf<List<CustomRepoRefOption>>(emptyList())
    val customAllRefs = mutableStateOf<List<CustomRepoRefOption>>(emptyList())
    val selectedCustomRefKey = mutableStateOf<String?>(null)
    val isDownloadingCustomSource = mutableStateOf(false)
    val customOperationCard: MutableState<OperationCardState> = operationCardController.state
    val customOperationCardAnchor = mutableStateOf(CustomOperationAnchor.GITHUB_INSTALL)

    fun onCleared() {
        customSourceDownloadJob?.cancel()
    }

    fun installCustomZip(uri: Uri) {
        if (getBusyOperation() != null) {
            postUserMessage("Wait for the current operation to finish.")
            return
        }
        setBusyOperation(BusyOperation.INSTALLING)
        startCustomOperationCard(
            title = "Installing Custom ST",
            details = "Preparing archive…",
            progressPercent = null,
            cancelable = false,
            anchor = CustomOperationAnchor.ZIP_INSTALL
        )
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                payload.installCustomFromZip(uri) { msg ->
                    scope.launch(Dispatchers.Main) {
                        updateCustomOperationCard(details = msg)
                    }
                }
            }
            refreshCustomInstallState()
            setBusyOperation(null)
            val msg = result.fold(
                onSuccess = { "Custom ST installed successfully." },
                onFailure = { "Installation failed: ${it.message ?: "unknown error"}" }
            )
            finishCustomOperationCard(msg)
            appendServiceLog(msg)
        }
    }

    fun resetToDefault() {
        if (getBusyOperation() != null) {
            postUserMessage("Wait for the current operation to finish.")
            return
        }
        setBusyOperation(BusyOperation.RESETTING)
        startCustomOperationCard(
            title = "Resetting to Bundled Version",
            details = "Preparing reset…",
            progressPercent = null,
            cancelable = false,
            anchor = CustomOperationAnchor.RESET_TO_BUNDLED
        )
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                payload.resetToDefault { msg ->
                    scope.launch(Dispatchers.Main) {
                        updateCustomOperationCard(details = msg)
                    }
                }
            }
            refreshCustomInstallState()
            setBusyOperation(null)
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
        if (getBusyOperation() != null && getBusyOperation() != BusyOperation.DOWNLOADING_CUSTOM_SOURCE) {
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
        scope.launch {
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
                scope.launch {
                    appendServiceLog("custom-refs: failed: ${error.message ?: "unknown error"}")
                }
            }
            isLoadingCustomRefs.value = false
        }
    }

    fun startCustomRepoInstall() {
        if (isDownloadingCustomSource.value) return
        customInstallValidationMessage.value = ""
        if (getBusyOperation() != null) {
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
        setBusyOperation(BusyOperation.DOWNLOADING_CUSTOM_SOURCE)
        isDownloadingCustomSource.value = true
        startCustomOperationCard(
            title = "Installing Custom ST",
            details = "Downloading ${selectedRef.refType} ${selectedRef.refName}...",
            progressPercent = 0,
            cancelable = true,
            anchor = CustomOperationAnchor.GITHUB_INSTALL
        )

        customSourceDownloadJob = scope.launch {
            val (owner, repo) = parsedRepo
            val safeRepo = "${owner}_${repo}".replace(Regex("[^A-Za-z0-9._-]"), "_")
            val safeRef = selectedRef.refName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tempDir = AppPaths(application).updatesDir.also { it.mkdirs() }
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
                setBusyOperation(BusyOperation.INSTALLING)
                updateCustomOperationCard(
                    details = "Download complete. Installing...",
                    progressPercent = null,
                    cancelable = false
                )

                val result = withContext(Dispatchers.IO) {
                    payload.installCustomFromZipFile(
                        zipFile = zipFile,
                        onProgress = { msg ->
                            scope.launch(Dispatchers.Main) {
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
                setBusyOperation(null)
            }
        }
    }

    fun cancelCustomSourceDownload() {
        customSourceDownloadJob?.cancel()
    }

    fun removeUserData() {
        setBusyOperation(BusyOperation.REMOVING_DATA)
        scope.launch {
            val paths = AppPaths(application)
            withContext(Dispatchers.IO) {
                paths.configDir.deleteRecursively()
                paths.dataDir.deleteRecursively()
            }
            val msg = "User data removed."
            setBusyOperation(null)
            postUserMessage(msg)
            appendServiceLog(msg)
        }
    }

    private fun refreshCustomInstallState() {
        isCustomInstalled.value = payload.isCustomInstalled()
        customInstallLabel.value = payload.getCustomInstallLabel()
    }

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

    private fun startCustomOperationCard(
        title: String,
        details: String,
        progressPercent: Int?,
        cancelable: Boolean,
        anchor: CustomOperationAnchor
    ) {
        customOperationCardAnchor.value = anchor
        operationCardController.start(
            title = title,
            details = details,
            progressPercent = progressPercent,
            cancelable = cancelable
        )
    }

    private fun updateCustomOperationCard(
        details: String? = null,
        progressPercent: Int? = customOperationCard.value.progressPercent,
        cancelable: Boolean? = null
    ) {
        operationCardController.update(
            details = details,
            progressPercent = progressPercent,
            cancelable = cancelable
        )
    }

    private fun finishCustomOperationCard(finalMessage: String) {
        operationCardController.finish(finalMessage)
    }

    private fun fetchCustomRepoRefs(
        owner: String,
        repo: String
    ): Pair<List<GithubRepoRef>, List<GithubRepoRef>> {
        val defaultBranch = fetchRepoDefaultBranch(owner, repo)
        val branches = fetchRepoBranches(owner, repo)
        val tags = fetchRepoTags(owner, repo)

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
            tagNames = sortedTags.map { it.name },
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

    private fun pickFeaturedRefs(
        defaultBranch: String?,
        tagNames: List<String>,
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
        for (tag in tagNames.take(3)) {
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
        val leftParsed = Versioning.parseVersion(left)
        val rightParsed = Versioning.parseVersion(right)
        if (leftParsed != null && rightParsed != null) {
            val semverCompare = Versioning.compareVersions(rightParsed, leftParsed)
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
                val cmp = Versioning.compareNumericStrings(leftNum, rightNum)
                if (cmp != 0) return cmp
                continue
            }
            if (lc != rc) return lc.compareTo(rc)
            i++
            j++
        }
        return left.length.compareTo(right.length)
    }

    private suspend fun downloadCustomSourceZip(
        downloadUrl: String,
        outputFile: File,
        onProgress: suspend (Long, Long?) -> Unit
    ) {
        HttpDownloader.downloadToFile(
            downloadUrl = downloadUrl,
            outputFile = outputFile,
            userAgent = "st-android-custom-st",
            bufferSize = DOWNLOAD_BUFFER_SIZE,
            acceptEncodingIdentity = true,
            extraTotalBytesHeaders = listOf(
                "X-Linked-Size",
                "x-goog-stored-content-length",
                "Content-Length"
            ),
            unknownLengthProgressStepBytes = UNKNOWN_LENGTH_PROGRESS_STEP_BYTES,
            onProgress = onProgress
        )
    }

    private fun githubApiGet(url: String): String {
        return HttpDownloader.githubApiGet(url, "st-android-custom-install")
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
}

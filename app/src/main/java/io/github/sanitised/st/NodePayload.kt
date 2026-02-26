package io.github.sanitised.st

import android.content.Context
import android.net.Uri
import android.os.Build
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.LinkOption
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.json.JSONObject

@OptIn(ExperimentalPathApi::class)
class NodePayload(private val context: Context) {
    companion object {
        private const val PAYLOAD_PREFS = "payload"
        private const val PREF_CUSTOM_INSTALLED = "custom_installed"
        private const val PREF_ST_PAYLOAD_VERSION = "st_payload_version"
        private const val PREF_CUSTOM_SOURCE_REPO = "custom_source_repo"
        private const val PREF_CUSTOM_SOURCE_REF_TYPE = "custom_source_ref_type"
        private const val PREF_CUSTOM_SOURCE_REF_NAME = "custom_source_ref_name"
        private const val PREF_CUSTOM_SOURCE_COMMIT = "custom_source_commit"
    }

    data class ManifestInfo(
        val payloadVersion: String?,
        val stVersion: String?,
        val stCommit: String?,
        val nodeVersion: String?,
        val nodeCommit: String?,
        val nodeTag: String?,
        val bundleSha256: String?
    )

    data class Layout(
        val nodeBin: File,
        val appDir: File,
        val appEntry: File,
        val logsDir: File,
        val configFile: File,
        val dataDir: File,
        val payloadUpdated: Boolean,
        val payloadVersion: String?
    )

    data class CustomInstallInfo(
        val repo: String,
        val refType: String,
        val refName: String,
        val commitSha: String?
    )

    fun ensureExtracted(): Result<Layout> {
        return runCatching {
            val paths = AppPaths(context)
            val nativeNode = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
            val nodeBin = if (nativeNode.exists()) {
                nativeNode
            } else {
                val abi = selectAbi() ?: throw IllegalStateException(
                    "No matching Node.js binary found in assets for this ABI"
                )
                paths.nodeBin(abi)
            }
            val appDir = paths.stDir
            val appEntry = File(appDir, "server.js")
            val logsDir = paths.logsDir
            val configDir = paths.configDir
            val configFile = paths.configFile
            val dataDir = paths.dataDir
            val payloadVersion = readPayloadVersion()
            var payloadUpdated = false

            // If user has a custom version installed, skip bundle extraction entirely.
            if (isCustomInstalled() && appEntry.exists()) {
                if (!logsDir.exists()) logsDir.mkdirs()
                if (!configDir.exists()) configDir.mkdirs()
                if (!dataDir.exists()) dataDir.mkdirs()
                val configPath = ensureSymlink(link = File(appDir, "config.yaml"), target = configFile)
                val dataPath = ensureSymlink(link = File(appDir, "data"), target = dataDir)
                return@runCatching Layout(
                    nodeBin = nodeBin,
                    appDir = appDir,
                    appEntry = appEntry,
                    logsDir = logsDir,
                    configFile = configPath,
                    dataDir = dataPath,
                    payloadUpdated = false,
                    payloadVersion = null
                )
            }

            if (!nodeBin.exists()) {
                val abi = selectAbi() ?: throw IllegalStateException(
                    "No matching Node.js binary found in assets for this ABI"
                )
                copyAssetToFile("node_payload/bin/$abi/node", nodeBin)
                makeExecutable(nodeBin)
            }

            val bundleAsset = when {
                assetExists("node_payload/st_bundle.tar.gz") -> "node_payload/st_bundle.tar.gz"
                assetExists("node_payload/st_bundle.tar") -> "node_payload/st_bundle.tar"
                else -> null
            }
            if (bundleAsset != null) {
                val installedVersion = getInstalledPayloadVersion()
                if (payloadVersion == null || installedVersion != payloadVersion || !appEntry.exists()) {
                    extractStBundle(bundleAsset)
                    setInstalledPayloadVersion(payloadVersion)
                    payloadUpdated = true
                }
            } else if (!appEntry.exists()) {
                val legacyAppDir = paths.legacyAppDir
                val legacyEntry = File(legacyAppDir, "index.js")
                if (!legacyEntry.exists()) {
                    copyAssetTree("node_payload/app", legacyAppDir)
                }
                return@runCatching Layout(
                    nodeBin = nodeBin,
                    appDir = legacyAppDir,
                    appEntry = legacyEntry,
                    logsDir = logsDir,
                    configFile = File(legacyAppDir, "config.yaml"),
                    dataDir = File(legacyAppDir, "data"),
                    payloadUpdated = payloadUpdated,
                    payloadVersion = payloadVersion
                )
            }

            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            if (!dataDir.exists()) {
                dataDir.mkdirs()
            }

            val configPath = ensureSymlink(link = File(appDir, "config.yaml"), target = configFile)
            val dataPath = ensureSymlink(link = File(appDir, "data"), target = dataDir)

            Layout(
                nodeBin = nodeBin,
                appDir = appDir,
                appEntry = appEntry,
                logsDir = logsDir,
                configFile = configPath,
                dataDir = dataPath,
                payloadUpdated = payloadUpdated,
                payloadVersion = payloadVersion
            )
        }
    }

    private fun makeExecutable(file: File) {
        if (!file.setExecutable(true, true)) {
            // Fall through to chmod attempt.
        }
        try {
            Os.chmod(file.absolutePath, 0x1C0) // 0700
        } catch (_: Exception) {
            // If chmod fails, let the later execution attempt surface the error.
        }
        if (!file.canExecute()) {
            throw IllegalStateException("Node binary is not executable: ${file.absolutePath}")
        }
    }

    private fun selectAbi(): String? {
        val candidates = Build.SUPPORTED_ABIS
        for (abi in candidates) {
            val assetPath = "node_payload/bin/$abi/node"
            if (assetExists(assetPath)) {
                return abi
            }
        }
        return null
    }

    private fun assetExists(path: String): Boolean {
        return try {
            context.assets.open(path).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            copyAssetToFile(assetPath, dest)
            return
        }
        if (!dest.exists()) {
            dest.mkdirs()
        }
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest = File(dest, child)
            copyAssetTree(childAssetPath, childDest)
        }
    }

    private fun copyAssetToFile(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun readPayloadVersion(): String? {
        return readManifestInfo()?.payloadVersion
    }

    fun readManifestInfo(): ManifestInfo? {
        val path = "node_payload/manifest.json"
        if (!assetExists(path)) return null
        return try {
            val text = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            ManifestInfo(
                payloadVersion = json.optString("payload_version", "").ifBlank { null },
                stVersion = json.optString("st_version", "").ifBlank { null },
                stCommit = json.optString("st_commit", "").ifBlank { null },
                nodeVersion = json.optString("node_version", "").ifBlank { null },
                nodeCommit = json.optString("node_commit", "").ifBlank { null },
                nodeTag = json.optString("node_tag", "").ifBlank { null },
                bundleSha256 = json.optString("bundle_sha256", "").ifBlank { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getInstalledPayloadVersion(): String? {
        val prefs = payloadPrefs()
        return prefs.getString(PREF_ST_PAYLOAD_VERSION, null)
    }

    private fun setInstalledPayloadVersion(version: String?) {
        if (version == null) return
        val prefs = payloadPrefs()
        prefs.edit().putString(PREF_ST_PAYLOAD_VERSION, version).apply()
    }

    private fun payloadPrefs() =
        context.getSharedPreferences(PAYLOAD_PREFS, Context.MODE_PRIVATE)

    private fun clearCustomInstallMetadata(edit: android.content.SharedPreferences.Editor) {
        edit.remove(PREF_CUSTOM_SOURCE_REPO)
            .remove(PREF_CUSTOM_SOURCE_REF_TYPE)
            .remove(PREF_CUSTOM_SOURCE_REF_NAME)
            .remove(PREF_CUSTOM_SOURCE_COMMIT)
    }

    private fun extractStBundle(assetPath: String) {
        val paths = AppPaths(context)
        val tmpRoot = paths.tmpDir
        val tmpDir = File(tmpRoot, "st_new")
        if (tmpDir.exists()) {
            tmpDir.toPath().deleteRecursively()
        }
        tmpDir.mkdirs()
        extractTarFromAssets(assetPath, tmpDir)
        val extractedRoot = if (File(tmpDir, "st/server.js").exists()) {
            File(tmpDir, "st")
        } else {
            tmpDir
        }
        val stDir = paths.stDir
        val oldDir = File(tmpRoot, "st_old")
        if (oldDir.exists()) {
            oldDir.toPath().deleteRecursively()
        }
        if (stDir.exists()) {
            if (!stDir.renameTo(oldDir)) {
                stDir.toPath().deleteRecursively()
            }
        }
        val promoted = extractedRoot.renameTo(stDir)
        if (!promoted) {
            extractedRoot.copyRecursively(stDir, overwrite = true)
            extractedRoot.toPath().deleteRecursively()
        }
        if (oldDir.exists()) {
            oldDir.toPath().deleteRecursively()
        }
    }

    private fun ensureSymlink(link: File, target: File): File {
        try {
            if (link.exists() && !Files.isSymbolicLink(link.toPath())) {
                if (!target.exists()) {
                    if (!link.renameTo(target)) {
                        link.deleteRecursively()
                    }
                } else {
                    link.deleteRecursively()
                }
            }
            if (!link.exists()) {
                Files.createSymbolicLink(link.toPath(), target.toPath())
            }
            if (Files.isSymbolicLink(link.toPath())) {
                return target
            }
        } catch (_: Exception) {
            // Fall back to keeping config/data in appDir if symlinks are not supported.
        }
        return link
    }

    private fun extractTarFromAssets(assetPath: String, destDir: File) {
        context.assets.open(assetPath).use { asset ->
            val isGzip = assetPath.endsWith(".gz")
            val inputStream = if (isGzip) {
                GZIPInputStream(BufferedInputStream(asset))
            } else {
                BufferedInputStream(asset)
            }
            inputStream.use { input ->
                TarArchiveInputStream(input).use { tar ->
                    while (true) {
                        val archiveEntry = tar.nextEntry ?: break
                        val entry = archiveEntry as? TarArchiveEntry ?: continue
                        val name = entry.name ?: continue
                        val target = TarUtils.resolveArchiveEntry(destDir, entry)
                        when {
                            entry.isDirectory -> {
                                target.mkdirs()
                            }

                            entry.isFile -> {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { output ->
                                    tar.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Custom ST version management
    // -------------------------------------------------------------------------

    fun isCustomInstalled(): Boolean {
        return payloadPrefs().getBoolean(PREF_CUSTOM_INSTALLED, false)
    }

    fun getCustomInstallInfo(): CustomInstallInfo? {
        if (!isCustomInstalled()) return null
        val prefs = payloadPrefs()
        val repo = prefs.getString(PREF_CUSTOM_SOURCE_REPO, null)?.trim().orEmpty()
        val refType = prefs.getString(PREF_CUSTOM_SOURCE_REF_TYPE, null)?.trim().orEmpty()
        val refName = prefs.getString(PREF_CUSTOM_SOURCE_REF_NAME, null)?.trim().orEmpty()
        val commitSha = prefs.getString(PREF_CUSTOM_SOURCE_COMMIT, null)?.trim()
            .takeIf { !it.isNullOrBlank() }
        if (repo.isBlank() || refType.isBlank() || refName.isBlank()) return null
        return CustomInstallInfo(
            repo = repo,
            refType = refType,
            refName = refName,
            commitSha = commitSha
        )
    }

    fun getCustomInstallLabel(): String? {
        val info = getCustomInstallInfo() ?: return null
        val shortSha = info.commitSha?.take(7)
        return if (shortSha.isNullOrBlank()) {
            "${info.refType}/${info.refName}"
        } else {
            "${info.refType}/${info.refName}-$shortSha"
        }
    }

    /**
     * Install a user-provided SillyTavern source ZIP.
     * The ZIP may have a single top-level directory (GitHub archive format) or
     * place files at the root.  server.js must be present.
     *
     * User data (data/ and config.yaml) is never touched.
     * [onProgress] is called from the worker thread; callers must marshal to
     * the main thread themselves if needed.
     */
    fun installCustomFromZip(uri: Uri, onProgress: (String) -> Unit): Result<Unit> {
        return installCustomFromZipInternal(
            sourceInfo = null,
            onProgress = onProgress
        ) {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open the selected file.")
        }
    }

    fun installCustomFromZipFile(
        zipFile: File,
        onProgress: (String) -> Unit,
        sourceInfo: CustomInstallInfo?
    ): Result<Unit> {
        return installCustomFromZipInternal(
            sourceInfo = sourceInfo,
            onProgress = onProgress
        ) {
            if (!zipFile.exists()) {
                throw IllegalStateException("Downloaded archive not found.")
            }
            zipFile.inputStream()
        }
    }

    private fun installCustomFromZipInternal(
        sourceInfo: CustomInstallInfo?,
        onProgress: (String) -> Unit,
        openInputStream: () -> InputStream
    ): Result<Unit> {
        return runCatching {
            val paths = AppPaths(context)
            val tmpRoot = paths.tmpDir
            tmpRoot.mkdirs()

            val extractDir = File(tmpRoot, "custom_extract")
            try {
                // 1. Extract ZIP
                onProgress("Extracting archive…")
                if (extractDir.exists()) extractDir.toPath().deleteRecursively()
                extractDir.mkdirs()
                openInputStream().use { input ->
                    extractZipToDir(input, extractDir)
                }

                // 2. Locate server.js (handle GitHub's top-level directory wrapper)
                val stRoot = findStRoot(extractDir)
                    ?: throw IllegalStateException(
                        "server.js not found in archive. " +
                            "Make sure you selected a SillyTavern source ZIP."
                    )

                if (!File(stRoot, "package.json").exists()) {
                    throw IllegalStateException(
                        "package.json not found. This does not look like a valid SillyTavern archive."
                    )
                }

                // 3. Ensure npm is available
                onProgress("Preparing npm…")
                val npmCli = ensureNpmExtracted()
                val nodeBin = findNodeBin()

                // 4. npm install
                onProgress("Installing dependencies (npm install)…")
                val npmLog = File(paths.logsDir.also { it.mkdirs() }, "npm_install.log")
                runNpmInstall(stRoot, nodeBin, npmCli, npmLog)

                // 5. Atomic swap: move new tree into place
                onProgress("Installing…")
                val stDir = paths.stDir
                val oldDir = File(tmpRoot, "custom_old")
                if (oldDir.exists()) oldDir.toPath().deleteRecursively()
                if (stDir.exists()) {
                    if (!stDir.renameTo(oldDir)) stDir.toPath().deleteRecursively()
                }
                if (!stRoot.renameTo(stDir)) {
                    stRoot.copyRecursively(stDir, overwrite = true)
                    stRoot.toPath().deleteRecursively()
                }
                if (oldDir.exists()) oldDir.toPath().deleteRecursively()

                // 6. Re-create symlinks so user data paths stay correct
                val configDir = paths.configDir
                val dataDir = paths.dataDir
                if (!configDir.exists()) configDir.mkdirs()
                if (!dataDir.exists()) dataDir.mkdirs()
                ensureSymlink(link = File(stDir, "config.yaml"), target = paths.configFile)
                ensureSymlink(link = File(stDir, "data"), target = dataDir)

                // 7. Persist custom flag; clear bundled-version stamp so a
                //    future reset will force re-extraction of the bundled tar.
                val edit = payloadPrefs().edit()
                    .putBoolean(PREF_CUSTOM_INSTALLED, true)
                    .remove(PREF_ST_PAYLOAD_VERSION)
                if (sourceInfo == null) {
                    clearCustomInstallMetadata(edit)
                } else {
                    edit.putString(PREF_CUSTOM_SOURCE_REPO, sourceInfo.repo)
                    edit.putString(PREF_CUSTOM_SOURCE_REF_TYPE, sourceInfo.refType)
                    edit.putString(PREF_CUSTOM_SOURCE_REF_NAME, sourceInfo.refName)
                    if (sourceInfo.commitSha.isNullOrBlank()) {
                        edit.remove(PREF_CUSTOM_SOURCE_COMMIT)
                    } else {
                        edit.putString(PREF_CUSTOM_SOURCE_COMMIT, sourceInfo.commitSha)
                    }
                }
                edit.apply()

                onProgress("Done!")
            } finally {
                // Best-effort cleanup of the temp extraction directory.
                if (extractDir.exists()) extractDir.toPath().deleteRecursively()
            }
        }
    }

    /**
     * Delete the custom ST installation and restore the bundled version.
     * User data is not affected.
     */
    fun resetToDefault(onProgress: (String) -> Unit = {}): Result<Unit> {
        return runCatching {
            val paths = AppPaths(context)
            onProgress("Removing custom installation…")
            val edit = payloadPrefs().edit()
                .remove(PREF_CUSTOM_INSTALLED)
                .remove(PREF_ST_PAYLOAD_VERSION)
            clearCustomInstallMetadata(edit)
            edit.apply()
            if (Files.exists(paths.stDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                paths.stDir.toPath().deleteRecursively()
            }
            onProgress("Restoring bundled version…")
            ensureExtracted().getOrThrow()
            onProgress("Done!")
        }
    }

    private fun findNodeBin(): File {
        val nativeNode = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
        if (nativeNode.exists()) return nativeNode
        val abi = selectAbi()
            ?: throw IllegalStateException("No matching Node.js binary found for this device ABI.")
        return AppPaths(context).nodeBin(abi)
    }

    private fun ensureNpmExtracted(): File {
        val paths = AppPaths(context)
        val npmCli = File(paths.npmDir, "bin/npm-cli.js")
        if (npmCli.exists()) return npmCli

        val assetPath = "node_payload/npm.tar"
        if (!assetExists(assetPath)) {
            throw IllegalStateException(
                "npm is not bundled in this build. Rebuild the app to enable custom ST installation."
            )
        }
        if (paths.npmDir.exists()) paths.npmDir.deleteRecursively()
        // The tar contains an "npm/" directory; extract into filesDir so the
        // result lands at filesDir/npm/.
        extractTarFromAssets(assetPath, paths.filesDir)
        if (!npmCli.exists()) {
            throw IllegalStateException("npm extraction failed: bin/npm-cli.js not found.")
        }
        return npmCli
    }

    private fun runNpmInstall(stRoot: File, nodeBin: File, npmCli: File, logFile: File) {
        val npmCache = File(context.cacheDir, "npm_cache").also { it.mkdirs() }
        val tmpDir = AppPaths(context).nodeTmpDir.also { it.mkdirs() }

        val builder = ProcessBuilder(
            nodeBin.absolutePath,
            npmCli.absolutePath,
            "install",
            "--omit=dev",
            "--ignore-scripts"
        )
        builder.directory(stRoot)
        builder.environment()["HOME"] = context.filesDir.absolutePath
        builder.environment()["NPM_CONFIG_CACHE"] = npmCache.absolutePath
        builder.environment()["TMPDIR"] = tmpDir.absolutePath
        builder.environment()["TMP"] = tmpDir.absolutePath
        builder.environment()["TEMP"] = tmpDir.absolutePath
        builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        builder.environment()["NODE_ENV"] = "production"
        builder.redirectOutput(logFile)
        builder.redirectErrorStream(true)

        val proc = builder.start()
        val finished = proc.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            proc.destroyForcibly()
            throw IllegalStateException("npm install timed out after 10 minutes.")
        }
        val exitCode = proc.exitValue()
        if (exitCode != 0) {
            val tail = try {
                logFile.readLines().takeLast(20).joinToString("\n")
            } catch (_: Exception) {
                "(no log output)"
            }
            throw IllegalStateException(
                "npm install failed (exit code $exitCode). Last output:\n$tail"
            )
        }
    }

    private fun extractZipToDir(input: InputStream, destDir: File) {
        try {
            ZipArchiveInputStream(BufferedInputStream(input)).use { zis ->
                while (true) {
                    val archiveEntry = zis.nextEntry ?: break
                    val entry = archiveEntry as? ZipArchiveEntry ?: continue
                    if (entry.name.isNotEmpty()) {
                        val target = TarUtils.resolveArchiveEntry(destDir, entry)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> zis.copyTo(out) }
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Unable to read ZIP file: ${e.message}", e)
        }
    }

    private fun findStRoot(dir: File): File? {
        if (File(dir, "server.js").exists()) return dir
        val children = dir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory && File(child, "server.js").exists()) return child
        }
        return null
    }
}

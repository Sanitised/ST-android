package io.github.sanitised.st

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Date
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

object NodeBackup {
    private const val BACKUP_ROOT = "st_backup"

    data class BackupProgress(
        val message: String,
        val percent: Int?
    )

    fun exportToUri(
        context: Context,
        uri: Uri,
        onProgress: (BackupProgress) -> Unit = {}
    ): Result<String> {
        return runCatching {
            val paths = AppPaths(context)
            val configFile = paths.configFile
            val dataDir = paths.dataDir
            val hasConfig = configFile.exists()
            val hasData = dataDir.exists() && dataDir.listFiles()?.isNotEmpty() == true
            if (!hasConfig && !hasData) {
                throw IllegalStateException("Nothing to export")
            }
            val totalBytes = (if (hasConfig) configFile.length() else 0L) +
                (if (hasData) totalRegularFileBytes(dataDir) else 0L)
            var copiedBytes = 0L
            var lastPercent = -1
            fun report(message: String, force: Boolean = false) {
                val percent = if (totalBytes > 0L) {
                    ((copiedBytes.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt().coerceIn(0, 100)
                } else {
                    null
                }
                if (!force && percent != null && percent == lastPercent) return
                if (percent != null) {
                    lastPercent = percent
                }
                onProgress(BackupProgress(message = message, percent = percent))
            }

            report(context.getString(R.string.backup_progress_preparing_export), force = true)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                BufferedOutputStream(output).use { buffered ->
                    GZIPOutputStream(buffered).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                            tar.setAddPaxHeadersForNonAsciiNames(true)
                            writeTarDirectory(tar, "$BACKUP_ROOT/")
                            if (hasConfig) {
                                writeTarFile(
                                    output = tar,
                                    name = "$BACKUP_ROOT/config.yaml",
                                    file = configFile
                                ) { copied ->
                                    copiedBytes += copied
                                    report(context.getString(R.string.backup_progress_exporting))
                                }
                            }
                            if (dataDir.exists()) {
                                writeTarDirectory(tar, "$BACKUP_ROOT/data/", sourceDir = dataDir)
                                writeTarTree(tar, dataDir, "$BACKUP_ROOT/data") { copied ->
                                    copiedBytes += copied
                                    report(context.getString(R.string.backup_progress_exporting))
                                }
                            }
                            tar.finish()
                        }
                    }
                }
            } ?: throw IllegalStateException("Unable to open destination")
            copiedBytes = totalBytes
            val completedMsg = context.getString(R.string.backup_progress_export_completed)
            report(completedMsg, force = true)
            completedMsg
        }
    }

    fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: (BackupProgress) -> Unit = {}
    ): Result<String> {
        return runCatching {
            val paths = AppPaths(context)
            val tmpRoot = paths.tmpDir
            val importDir = File(tmpRoot, "import")
            if (importDir.exists()) {
                importDir.deleteRecursively()
            }
            importDir.mkdirs()
            val totalBytes = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    afd.length.takeIf { it > 0L }
                }
            }.getOrNull()
            onProgress(BackupProgress(context.getString(R.string.backup_progress_preparing_import), null))

            val extractingMsg = context.getString(R.string.backup_progress_extracting)
            context.contentResolver.openInputStream(uri)?.use { raw ->
                val countingRaw = CountingInputStream(BufferedInputStream(raw))
                val pushback = PushbackInputStream(countingRaw, 2)
                var lastPercent = -1
                var lastMessage = ""
                fun reportExtractProgress(message: String, force: Boolean) {
                    val percent = totalBytes?.let { total ->
                        ((countingRaw.bytesRead.coerceAtMost(total) * 100L) / total).toInt().coerceIn(0, 100)
                    }
                    if (!force && message == lastMessage && percent == lastPercent) {
                        return
                    }
                    lastMessage = message
                    if (percent != null) {
                        lastPercent = percent
                    }
                    onProgress(BackupProgress(message, percent))
                }
                val sig = ByteArray(2)
                val read = pushback.read(sig)
                if (read > 0) pushback.unread(sig, 0, read)
                reportExtractProgress(extractingMsg, true)
                when {
                    read == 2 && sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte() ->
                        extractBackupFromZip(pushback, importDir) {
                            reportExtractProgress(extractingMsg, false)
                        }

                    read == 2 && sig[0] == 0x1F.toByte() && sig[1] == 0x8B.toByte() ->
                        extractBackup(GZIPInputStream(pushback), importDir) {
                            reportExtractProgress(extractingMsg, false)
                        }

                    else ->
                        extractBackup(pushback, importDir) {
                            reportExtractProgress(extractingMsg, false)
                        }
                }
            } ?: throw IllegalStateException("Unable to open archive")
            onProgress(BackupProgress(context.getString(R.string.backup_progress_applying), null))

            val configSrc = File(importDir, "config/config.yaml")
            val dataSrc = File(importDir, "data")
            if (!configSrc.exists() && !dataSrc.exists()) {
                throw IllegalStateException(
                    "No recognizable data found in archive. " +
                            "Make sure you selected a valid SillyTavern backup (.tar.gz or .zip)."
                )
            }

            val configDest = paths.configFile
            val dataDest = paths.dataDir

            // Atomic swap for data directory: rename old aside, rename new in.
            // renameTo within the same filesystem is atomic at the OS level,
            // so no partial state is visible even if the process is killed.
            if (dataSrc.exists()) {
                val oldDataDir = File(tmpRoot, "import_data_old")
                if (oldDataDir.exists()) oldDataDir.deleteRecursively()
                if (dataDest.exists()) {
                    if (!dataDest.renameTo(oldDataDir)) {
                        dataDest.deleteRecursively()
                    }
                }
                if (!dataSrc.renameTo(dataDest)) {
                    dataDest.parentFile?.mkdirs()
                    dataSrc.copyRecursively(dataDest, overwrite = true)
                }
                if (oldDataDir.exists()) oldDataDir.deleteRecursively()
            } else {
                dataDest.mkdirs()
            }

            // Atomic swap for config file.
            if (configSrc.exists()) {
                configDest.parentFile?.mkdirs()
                val oldConfig = File(tmpRoot, "import_config_old.yaml")
                if (oldConfig.exists()) oldConfig.delete()
                if (configDest.exists()) {
                    if (!configDest.renameTo(oldConfig)) {
                        configDest.delete()
                    }
                }
                if (!configSrc.renameTo(configDest)) {
                    configSrc.copyTo(configDest, overwrite = true)
                }
                if (oldConfig.exists()) oldConfig.delete()
            }

            importDir.deleteRecursively()
            val importCompleteMsg = context.getString(R.string.backup_progress_import_complete)
            onProgress(BackupProgress(importCompleteMsg, 100))
            importCompleteMsg
        }
    }

    private fun extractBackupFromZip(
        input: InputStream,
        destDir: File,
        onProgressTick: () -> Unit = {}
    ) {
        try {
            ZipArchiveInputStream(input).use { zis ->
                while (true) {
                    val archiveEntry = zis.nextEntry ?: break
                    val entry = archiveEntry as? ZipArchiveEntry ?: continue
                    if (entry.name.isNotEmpty()) {
                        val target = mapBackupPath(destDir, entry.name)
                        if (target != null) {
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out -> zis.copyTo(out) }
                            }
                        }
                    }
                    onProgressTick()
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException("Unable to read ZIP archive: ${e.message}", e)
        }
    }

    private fun extractBackup(
        input: InputStream,
        destDir: File,
        onProgressTick: () -> Unit = {}
    ) {
        BufferedInputStream(input).use { stream ->
            TarArchiveInputStream(stream).use { tar ->
                while (true) {
                    val archiveEntry = tar.nextEntry ?: break
                    val entry = archiveEntry as? TarArchiveEntry ?: continue
                    val entryName = entry.name ?: continue
                    val target = mapBackupPath(destDir, entryName) ?: continue
                    when {
                        entry.isDirectory -> {
                            target.mkdirs()
                            onProgressTick()
                        }

                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                tar.copyTo(output)
                            }
                            onProgressTick()
                        }
                    }
                }
            }
        }
    }

    private fun mapBackupPath(destDir: File, rawName: String): File? {
        val clean = rawName.removePrefix("./")
        val stripped = if (clean.startsWith("$BACKUP_ROOT/")) {
            clean.removePrefix("$BACKUP_ROOT/")
        } else {
            clean
        }
        if (stripped.isEmpty() || stripped == "$BACKUP_ROOT" || stripped == "$BACKUP_ROOT/") return null
        val normalized = when {
            stripped == "config.yaml" -> "config/config.yaml"
            stripped == "config/config.yaml" -> "config/config.yaml"
            stripped == "data" -> "data"
            stripped.startsWith("data/") -> stripped
            else -> return null
        }
        return TarUtils.resolveArchiveEntryName(destDir, normalized)
    }

    private fun writeTarTree(
        output: TarArchiveOutputStream,
        root: File,
        baseName: String,
        onBytesCopied: (Long) -> Unit = {}
    ) {
        val entries = root.listFiles() ?: return
        for (entry in entries) {
            val name = "$baseName/${entry.name}"
            if (entry.isDirectory) {
                writeTarDirectory(output, "$name/", sourceDir = entry)
                writeTarTree(output, entry, name, onBytesCopied)
            } else if (entry.isFile) {
                writeTarFile(output, name, entry, onBytesCopied)
            }
        }
    }

    private fun writeTarDirectory(
        output: TarArchiveOutputStream,
        name: String,
        sourceDir: File? = null
    ) {
        val normalized = if (name.endsWith("/")) name else "$name/"
        val entry = TarArchiveEntry(normalized).apply {
            mode = 493
            size = 0L
            modTime = Date(sourceDir?.lastModified()?.takeIf { it > 0L } ?: System.currentTimeMillis())
        }
        output.putArchiveEntry(entry)
        output.closeArchiveEntry()
    }

    private fun writeTarFile(
        output: TarArchiveOutputStream,
        name: String,
        file: File,
        onBytesCopied: (Long) -> Unit = {}
    ) {
        val entry = TarArchiveEntry(file, name).apply {
            mode = 420
        }
        output.putArchiveEntry(entry)
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                onBytesCopied(read.toLong())
            }
        }
        output.closeArchiveEntry()
    }

    private fun totalRegularFileBytes(root: File): Long {
        if (!root.exists()) return 0L
        if (root.isFile) return root.length()
        val children = root.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            total += totalRegularFileBytes(child)
        }
        return total
    }

    private class CountingInputStream(
        private val delegate: InputStream
    ) : InputStream() {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val result = delegate.read()
            if (result >= 0) {
                bytesRead += 1
            }
            return result
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            val result = delegate.read(buffer, off, len)
            if (result > 0) {
                bytesRead += result.toLong()
            }
            return result
        }

        override fun close() {
            delegate.close()
        }
    }

    // Tar parsing helpers live in TarUtils.
}

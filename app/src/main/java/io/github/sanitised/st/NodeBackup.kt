package io.github.sanitised.st

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object NodeBackup {
    private const val BACKUP_ROOT = "st_backup"

    fun exportToUri(context: Context, uri: Uri): Result<String> {
        return runCatching {
            val paths = AppPaths(context)
            val configFile = paths.configFile
            val dataDir = paths.dataDir
            val hasConfig = configFile.exists()
            val hasData = dataDir.exists() && dataDir.listFiles()?.isNotEmpty() == true
            if (!hasConfig && !hasData) {
                throw IllegalStateException("Nothing to export")
            }
            context.contentResolver.openOutputStream(uri)?.use { output ->
                BufferedOutputStream(output).use { buffered ->
                    GZIPOutputStream(buffered).use { gz ->
                        writeTarDirectory(gz, "$BACKUP_ROOT/")
                        if (hasConfig) {
                            writeTarFile(gz, "$BACKUP_ROOT/config.yaml", configFile)
                        }
                        if (dataDir.exists()) {
                            writeTarDirectory(gz, "$BACKUP_ROOT/data/")
                            writeTarTree(gz, dataDir, "$BACKUP_ROOT/data")
                        }
                        finishTar(gz)
                    }
                }
            } ?: throw IllegalStateException("Unable to open destination")
            "Export complete"
        }
    }

    fun importFromUri(context: Context, uri: Uri): Result<String> {
        return runCatching {
            val paths = AppPaths(context)
            val tmpRoot = paths.tmpDir
            val importDir = File(tmpRoot, "import")
            if (importDir.exists()) {
                importDir.deleteRecursively()
            }
            importDir.mkdirs()

            context.contentResolver.openInputStream(uri)?.use { raw ->
                val stream = maybeGunzip(raw)
                extractBackup(stream, importDir)
            } ?: throw IllegalStateException("Unable to open archive")

            val configDest = paths.configFile
            val dataDest = paths.dataDir
            if (configDest.exists()) {
                configDest.delete()
            }
            if (dataDest.exists()) {
                dataDest.deleteRecursively()
            }

            val configSrc = File(importDir, "config/config.yaml")
            if (configSrc.exists()) {
                configDest.parentFile?.mkdirs()
                configSrc.copyTo(configDest, overwrite = true)
            }
            val dataSrc = File(importDir, "data")
            if (dataSrc.exists()) {
                dataDest.parentFile?.mkdirs()
                dataSrc.copyRecursively(dataDest, overwrite = true)
            } else {
                dataDest.mkdirs()
            }

            importDir.deleteRecursively()
            "Import complete"
        }
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val pushback = PushbackInputStream(BufferedInputStream(input), 2)
        val signature = ByteArray(2)
        val read = pushback.read(signature)
        if (read > 0) {
            pushback.unread(signature, 0, read)
        }
        return if (read == 2 && signature[0] == 0x1F.toByte() && signature[1] == 0x8B.toByte()) {
            GZIPInputStream(pushback)
        } else {
            pushback
        }
    }

    private fun extractBackup(input: InputStream, destDir: File) {
        BufferedInputStream(input).use { stream ->
            val header = ByteArray(512)
            var pendingLongName: String? = null
            while (true) {
                if (!TarUtils.readFully(stream, header)) break
                if (header.all { it == 0.toByte() }) break
                val nameField = TarUtils.parseTarString(header, 0, 100)
                val prefix = TarUtils.parseTarString(header, 345, 155)
                val combined = if (prefix.isNotEmpty()) "$prefix/$nameField" else nameField
                val entryName = pendingLongName ?: combined
                pendingLongName = null
                val size = TarUtils.parseTarNumeric(header, 124, 12)
                val type = header[156].toInt().toChar()

                if (type == 'x' || type == 'g') {
                    val padding = (512 - (size % 512)) % 512
                    TarUtils.skipFully(stream, size + padding)
                    continue
                }
                if (type == 'L' || type == 'K') {
                    val longName = TarUtils.readTarStringPayload(stream, size)
                    val padding = (512 - (size % 512)) % 512
                    if (padding > 0) {
                        TarUtils.skipFully(stream, padding)
                    }
                    if (type == 'L' && longName.isNotEmpty()) {
                        pendingLongName = longName
                    }
                    continue
                }

                val target = mapBackupPath(destDir, entryName)
                if (target == null) {
                    val padding = (512 - (size % 512)) % 512
                    TarUtils.skipFully(stream, size + padding)
                    continue
                }

                if (type == '5' || entryName.endsWith("/")) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        TarUtils.copyExact(stream, output, size)
                    }
                }
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) {
                    TarUtils.skipFully(stream, padding)
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
        return TarUtils.safeResolve(destDir, normalized)
    }

    private fun writeTarTree(output: OutputStream, root: File, baseName: String) {
        val entries = root.listFiles() ?: return
        for (entry in entries) {
            val name = "$baseName/${entry.name}"
            if (entry.isDirectory) {
                writeTarDirectory(output, "$name/")
                writeTarTree(output, entry, name)
            } else {
                writeTarFile(output, name, entry)
            }
        }
    }

    private fun writeTarDirectory(output: OutputStream, name: String) {
        val normalized = if (name.endsWith("/")) name else "$name/"
        writeTarHeader(output, normalized, 0, 493, '5')
    }

    private fun writeTarFile(output: OutputStream, name: String, file: File) {
        val size = file.length()
        writeTarHeader(output, name, size, 420, '0')
        file.inputStream().use { input ->
            input.copyTo(output)
        }
        val padding = (512 - (size % 512)) % 512
        if (padding > 0) {
            output.write(ByteArray(padding.toInt()))
        }
    }

    private fun writeTarHeader(
        output: OutputStream,
        name: String,
        size: Long,
        mode: Int,
        type: Char
    ) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        if (nameBytes.size > 100) {
            writeLongName(output, name)
        }
        val header = ByteArray(512)
        writeString(header, 0, 100, name.take(100))
        writeOctal(header, 100, 8, mode.toLong())
        writeOctal(header, 108, 8, 0)
        writeOctal(header, 116, 8, 0)
        writeOctal(header, 124, 12, size)
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000)
        for (i in 148 until 156) {
            header[i] = 0x20
        }
        header[156] = type.code.toByte()
        writeString(header, 257, 6, "ustar")
        writeString(header, 263, 2, "00")
        writeString(header, 265, 32, "root")
        writeString(header, 297, 32, "root")
        val checksum = header.sumOf { it.toInt() and 0xFF }
        writeOctal(header, 148, 8, checksum.toLong(), withTrailingNull = true)
        output.write(header)
    }

    private fun writeLongName(output: OutputStream, name: String) {
        val bytes = (name + "\u0000").toByteArray(StandardCharsets.UTF_8)
        writeTarHeader(output, "././@LongLink", bytes.size.toLong(), 420, 'L')
        output.write(bytes)
        val padding = (512 - (bytes.size.toLong() % 512)) % 512
        if (padding > 0) {
            output.write(ByteArray(padding.toInt()))
        }
    }

    private fun finishTar(output: OutputStream) {
        output.write(ByteArray(1024))
    }

    private fun writeString(header: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val count = minOf(bytes.size, length)
        System.arraycopy(bytes, 0, header, offset, count)
    }

    private fun writeOctal(
        header: ByteArray,
        offset: Int,
        length: Int,
        value: Long,
        withTrailingNull: Boolean = false
    ) {
        val digits = java.lang.Long.toOctalString(value).padStart(length - 1, '0')
        val bytes = digits.toByteArray(StandardCharsets.UTF_8)
        val count = minOf(bytes.size, length - 1)
        System.arraycopy(bytes, 0, header, offset + (length - 1 - count), count)
        if (withTrailingNull) {
            header[offset + length - 1] = 0
        } else {
            header[offset + length - 1] = 0x20
        }
    }

    // Tar parsing helpers live in TarUtils.
}

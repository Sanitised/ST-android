package io.github.sanitised.st

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object TarUtils {
    fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count <= 0) {
                return false
            }
            offset += count
        }
        return true
    }

    fun copyExact(input: InputStream, output: OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) {
                throw IllegalStateException("Unexpected EOF while extracting tar")
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    fun skipFully(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(4096)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) {
                return
            }
            remaining -= read
        }
    }

    fun parseTarString(header: ByteArray, offset: Int, length: Int): String {
        val end = indexOf(header, 0, offset, offset + length)
        val actualEnd = if (end == -1) offset + length else end
        return String(header.copyOfRange(offset, actualEnd), StandardCharsets.UTF_8).trim()
    }

    fun parseTarNumeric(header: ByteArray, offset: Int, length: Int): Long {
        if (length <= 0) return 0
        val first = header[offset]
        val isBase256 = (first.toInt() and 0x80) != 0
        if (isBase256) {
            var value = (header[offset].toInt() and 0x7F).toLong()
            for (i in 1 until length) {
                value = (value shl 8) or (header[offset + i].toInt() and 0xFF).toLong()
            }
            return value
        }
        val end = indexOf(header, 0, offset, offset + length)
        val actualEnd = if (end == -1) offset + length else end
        val value = String(header.copyOfRange(offset, actualEnd), StandardCharsets.UTF_8).trim()
        if (value.isEmpty()) return 0
        return value.toLongOrNull(8) ?: 0
    }

    fun safeResolve(destDir: File, name: String): File {
        val cleanName = name.removePrefix("./")
        if (cleanName.startsWith("/") || cleanName.contains("..")) {
            throw IllegalStateException("Blocked tar path: $name")
        }
        val target = File(destDir, cleanName)
        val destPath = destDir.canonicalFile
        val targetPath = target.canonicalFile
        if (!targetPath.path.startsWith(destPath.path)) {
            throw IllegalStateException("Blocked tar path: $name")
        }
        return target
    }

    fun readTarStringPayload(input: InputStream, size: Long): String {
        if (size <= 0) return ""
        if (size > 1024 * 1024) {
            skipFully(input, size)
            return ""
        }
        val buf = ByteArray(size.toInt())
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buf, StandardCharsets.UTF_8).trimEnd('\u0000', '\n', '\r')
    }

    private fun indexOf(buffer: ByteArray, value: Int, start: Int, end: Int): Int {
        for (i in start until end) {
            if (buffer[i].toInt() == value) return i
        }
        return -1
    }
}

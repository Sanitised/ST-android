package io.github.sanitised.st

import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

object TarUtils {
    fun resolveArchiveEntry(destDir: File, entry: ArchiveEntry): File {
        val entryName = entry.name
        if (entryName.isNullOrEmpty()) {
            throw IllegalStateException("Blocked archive path: <empty>")
        }
        return try {
            entry.resolveIn(destDir.toPath()).toFile()
        } catch (e: IOException) {
            throw IllegalStateException("Blocked archive path: $entryName", e)
        } catch (e: InvalidPathException) {
            throw IllegalStateException("Blocked archive path: $entryName", e)
        }
    }

    fun resolveArchiveEntryName(destDir: File, entryName: String): File {
        return resolveArchiveEntry(destDir, TarArchiveEntry(entryName))
    }
}

package io.github.sanitised.st

import android.content.Context
import java.io.File

class AppPaths(private val context: Context) {
    val filesDir: File get() = context.filesDir
    val cacheDir: File get() = context.cacheDir

    val stDir: File get() = File(filesDir, "st")
    val logsDir: File get() = File(filesDir, "logs")
    val configDir: File get() = File(filesDir, "config")
    val configFile: File get() = File(configDir, "config.yaml")
    val dataDir: File get() = File(filesDir, "data")
    val tmpDir: File get() = File(filesDir, "tmp")
    val updatesDir: File get() = File(filesDir, "updates")
    val nodeTmpDir: File get() = File(cacheDir, "node_tmp")

    val npmDir: File get() = File(filesDir, "npm")

    val legacyAppDir: File get() = File(filesDir, "node/app")

    fun nodeBin(abi: String): File = File(filesDir, "node/bin/$abi/node")
}

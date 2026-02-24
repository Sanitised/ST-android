package io.github.sanitised.st

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

object HttpDownloader {
    suspend fun downloadToFile(
        downloadUrl: String,
        outputFile: File,
        userAgent: String,
        bufferSize: Int = 16 * 1024,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 20_000,
        acceptEncodingIdentity: Boolean = false,
        extraTotalBytesHeaders: List<String> = emptyList(),
        unknownLengthProgressStepBytes: Long? = null,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ) {
        outputFile.parentFile?.mkdirs()
        val tempFile = File(outputFile.parentFile, "${outputFile.name}.part")
        if (tempFile.exists()) tempFile.delete()

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/octet-stream")
            if (acceptEncodingIdentity) {
                setRequestProperty("Accept-Encoding", "identity")
            }
            setRequestProperty("User-Agent", userAgent)
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val shortBody = body.replace('\n', ' ').take(240)
                throw IllegalStateException("Download HTTP $status: $shortBody")
            }

            val totalBytes = buildList {
                add(connection.contentLengthLong)
                for (header in extraTotalBytesHeaders) {
                    add(connection.getHeaderFieldLong(header, -1L))
                }
            }.firstOrNull { it > 0L }
            var downloadedBytes = 0L
            var lastKnownPercent = -1
            var lastUnknownBucket = -1L

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes != null) {
                            val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastKnownPercent) {
                                lastKnownPercent = percent
                                onProgress(downloadedBytes, totalBytes)
                            }
                        } else if (unknownLengthProgressStepBytes == null) {
                            onProgress(downloadedBytes, null)
                        } else {
                            val bucket = downloadedBytes / unknownLengthProgressStepBytes
                            if (bucket != lastUnknownBucket) {
                                lastUnknownBucket = bucket
                                onProgress(downloadedBytes, null)
                            }
                        }
                    }
                }
            }

            if (totalBytes != null && lastKnownPercent < 100) {
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
}

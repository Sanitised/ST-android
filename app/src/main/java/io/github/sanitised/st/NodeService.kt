package io.github.sanitised.st

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NodeService : Service() {
    companion object {
        const val ACTION_START = "io.github.sanitised.st.action.START_NODE"
        const val ACTION_STOP = "io.github.sanitised.st.action.STOP_NODE"
        const val EXTRA_PORT = "io.github.sanitised.st.extra.PORT"
        private const val CHANNEL_ID = "node_service_v2"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_LOG_BYTES = 10L * 1024L * 1024L
    }

    inner class LocalBinder : Binder() {
        fun getService(): NodeService = this@NodeService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<NodeStatusListener>()
    private val payload = NodePayload(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var process: Process? = null
    private var status: NodeStatus = NodeStatus(NodeState.STOPPED, "Idle")
    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notifyStatus(status)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, status.port)
                setPort(port)
                if (ensureForeground("Starting")) {
                    startNodeAsync()
                }
            }
            ACTION_STOP -> stopNode()
        }
        return START_STICKY
    }

    fun registerListener(listener: NodeStatusListener) {
        listeners.add(listener)
        listener.onStatus(status)
    }

    fun unregisterListener(listener: NodeStatusListener) {
        listeners.remove(listener)
    }

    private fun startNodeAsync() {
        val shouldStart = synchronized(this) {
            if (process != null) return@synchronized false
            if (status.state == NodeState.STARTING || status.state == NodeState.STOPPING) return@synchronized false
            true
        }
        if (!shouldStart) return
        serviceScope.launch { startNodeInternal() }
    }

    private fun startNodeInternal() {
        synchronized(this) {
            if (process != null) {
                return
            }
            stopRequested = false
            updateStatus(NodeState.STARTING, "Starting node")
        }
        val layout = try {
            val layoutResult = payload.ensureExtracted()
            if (layoutResult.isFailure) {
                updateStatus(
                    NodeState.ERROR,
                    layoutResult.exceptionOrNull()?.message ?: "Extraction failed"
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
            layoutResult.getOrThrow()
        } catch (e: Exception) {
            updateStatus(NodeState.ERROR, e.message ?: "Extraction failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (!layout.nodeBin.exists()) {
            updateStatus(NodeState.ERROR, "Node binary not found. Add assets/node_payload/bin/<abi>/node")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (stopRequested) {
            updateStatus(NodeState.STOPPED, "Stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        try {
            if (!layout.logsDir.exists()) {
                layout.logsDir.mkdirs()
            }
            if (layout.payloadUpdated) {
                appendServiceLog(layout.logsDir, "post-install: starting")
                runPostInstall(layout)
                appendServiceLog(layout.logsDir, "post-install: complete")
            }
            val stdout = File(layout.logsDir, "node_stdout.log")
            val stderr = File(layout.logsDir, "node_stderr.log")
            rotateLogIfNeeded(stdout, MAX_LOG_BYTES)
            rotateLogIfNeeded(stderr, MAX_LOG_BYTES)
            val builder = ProcessBuilder(
                layout.nodeBin.absolutePath,
                layout.appEntry.absolutePath,
                "--configPath",
                layout.configFile.absolutePath,
                "--dataRoot",
                layout.dataDir.absolutePath,
                "--browserLaunchEnabled",
                "false"
            )
            builder.directory(layout.appDir)
            val tmpDir = AppPaths(this).nodeTmpDir
            tmpDir.mkdirs()
            builder.environment()["PORT"] = status.port.toString()
            builder.environment()["HOME"] = filesDir.absolutePath
            builder.environment()["TMPDIR"] = tmpDir.absolutePath
            builder.environment()["TMP"] = tmpDir.absolutePath
            builder.environment()["TEMP"] = tmpDir.absolutePath
            builder.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
            builder.environment()["NODE_ENV"] = "production"
            builder.environment()["ST_ANDROID"] = "1"
            builder.redirectOutput(stdout)
            builder.redirectError(stderr)
            val startedProcess = builder.start()
            process = startedProcess

            updateStatus(NodeState.RUNNING, "Running")
            waitForExitAsync(startedProcess)
        } catch (e: Exception) {
            appendServiceLog(layout.logsDir, "start failed: ${e.message ?: "unknown error"}")
            updateStatus(NodeState.ERROR, e.message ?: "Failed to start node")
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun runPostInstall(layout: NodePayload.Layout) {
        val script = File(layout.appDir, "post-install.js")
        if (!script.exists()) {
            appendServiceLog(layout.logsDir, "post-install: script not found")
            return
        }
        val stdout = File(layout.logsDir, "post_install_stdout.log")
        val stderr = File(layout.logsDir, "post_install_stderr.log")
        val builder = ProcessBuilder(layout.nodeBin.absolutePath, script.absolutePath)
        builder.directory(layout.appDir)
        val tmpDir = AppPaths(this).nodeTmpDir
        tmpDir.mkdirs()
        builder.environment()["HOME"] = filesDir.absolutePath
        builder.environment()["TMPDIR"] = tmpDir.absolutePath
        builder.environment()["TMP"] = tmpDir.absolutePath
        builder.environment()["TEMP"] = tmpDir.absolutePath
        builder.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
        builder.environment()["NODE_ENV"] = "production"
        builder.environment()["ST_ANDROID"] = "1"
        builder.redirectOutput(stdout)
        builder.redirectError(stderr)
        val proc = builder.start()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("post-install.js timed out")
        }
        val exitCode = proc.exitValue()
        if (exitCode != 0) {
            throw IllegalStateException("post-install.js failed with code $exitCode")
        }
    }

    private fun appendServiceLog(logsDir: File, message: String) {
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        val logFile = File(logsDir, "service.log")
        rotateLogIfNeeded(logFile, MAX_LOG_BYTES)
        val line = formatServiceLogLine(message)
        logFile.appendText(line, Charsets.UTF_8)
    }

    fun stopNode() {
        serviceScope.launch { stopNodeInternal() }
    }

    private fun stopNodeInternal() {
        val proc = synchronized(this) {
            if (status.state == NodeState.STOPPED || status.state == NodeState.STOPPING) return
            stopRequested = true
            val current = process
            updateStatus(NodeState.STOPPING, "Stopping")
            current ?: return
        }

        try {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        } catch (_: Exception) {
            proc.destroyForcibly()
        } finally {
            synchronized(this) {
                if (process === proc) {
                    process = null
                }
            }
            updateStatus(NodeState.STOPPED, "Stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun waitForExitAsync(startedProcess: Process) {
        serviceScope.launch {
            val exitCode = try {
                startedProcess.waitFor()
            } catch (_: Exception) {
                null
            }
            val wasStopRequested = stopRequested || status.state == NodeState.STOPPING
            synchronized(this) {
                if (process === startedProcess) {
                    process = null
                }
            }
            if (wasStopRequested) {
                updateStatus(NodeState.STOPPED, "Stopped")
            } else {
                if (exitCode == 0) {
                    updateStatus(NodeState.STOPPED, "Exited")
                } else {
                    val message = "Exited with code ${exitCode ?: "?"}"
                    updateStatus(NodeState.ERROR, message)
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun rotateLogIfNeeded(file: File, maxBytes: Long) {
        if (!file.exists()) return
        if (file.length() <= maxBytes) return
        val backup = File(file.parentFile, "${file.name}.1")
        if (backup.exists()) {
            backup.delete()
        }
        file.renameTo(backup)
        file.writeText("", Charsets.UTF_8)
    }

    fun runPostInstallNow(): Result<Unit> {
        synchronized(this) {
            if (process != null) {
                return Result.failure(IllegalStateException("Node is running"))
            }
        }
        return runCatching {
            val layoutResult = payload.ensureExtracted()
            if (layoutResult.isFailure) {
                throw layoutResult.exceptionOrNull() ?: IllegalStateException("Extraction failed")
            }
            val layout = layoutResult.getOrThrow()
            if (!layout.logsDir.exists()) {
                layout.logsDir.mkdirs()
            }
            appendServiceLog(layout.logsDir, "post-install: starting (manual)")
            runPostInstall(layout)
            appendServiceLog(layout.logsDir, "post-install: complete (manual)")
        }
    }

    private fun updateStatus(state: NodeState, message: String, pid: Long? = status.pid) {
        status = status.copy(state = state, message = message, pid = pid)
        notifyStatus(status)
    }

    private fun setPort(port: Int) {
        val safePort = if (port in 1..65535) port else DEFAULT_PORT
        status = status.copy(port = safePort)
        notifyStatus(status)
    }

    private fun notifyStatus(newStatus: NodeStatus) {
        for (listener in listeners) {
            listener.onStatus(newStatus)
        }
        val manager = NotificationManagerCompat.from(this)
        if (newStatus.state == NodeState.STOPPED) {
            manager.cancel(NOTIFICATION_ID)
        } else {
            manager.notify(
                NOTIFICATION_ID,
                buildNotification("${newStatus.state}: ${newStatus.message}")
            )
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NodeService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SillyTavern Node")
            .setContentText(contentText)
            .setContentIntent(openPending)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(status.state == NodeState.RUNNING || status.state == NodeState.STARTING)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun ensureForeground(message: String): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(message))
            true
        } catch (e: Exception) {
            updateStatus(NodeState.ERROR, e.message ?: "Foreground service not allowed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Node service",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.enableVibration(false)
        channel.setSound(null, null)
        channel.setShowBadge(false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

}

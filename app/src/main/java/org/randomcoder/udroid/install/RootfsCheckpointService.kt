package org.randomcoder.udroid.install

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.randomcoder.udroid.MainActivity
import org.randomcoder.udroid.UdroidApplication
import org.randomcoder.udroid.filesystem.notifyRootfsDocumentsChanged
import org.randomcoder.udroid.linuxapps.LinuxApplicationShortcutPublisher
import org.randomcoder.udroid.runtime.ProotMountResolver
import org.randomcoder.udroid.runtime.DesktopSessionPhase
import org.randomcoder.udroid.runtime.RuntimePhase
import org.randomcoder.udroid.runtime.RootfsStorageUsage
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

class RootfsCheckpointService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var active: Future<*>? = null

    private val app: UdroidApplication
        get() = application as UdroidApplication

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val action = intent?.action
        val operation =
            when (action) {
                ACTION_BACKUP -> RootfsCheckpointOperation.BACKUP
                ACTION_RESTORE -> RootfsCheckpointOperation.RESTORE
                else -> null
            }
        val rootfsName = intent?.getStringExtra(EXTRA_ROOTFS_NAME)
        val operationId = intent?.getStringExtra(EXTRA_OPERATION_ID)
        val uri = intent?.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (operation == null || rootfsName == null || operationId == null || uri == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        synchronized(this) {
            if (active?.isDone == false) return START_NOT_STICKY
            val state =
                RootfsCheckpointState(
                    operationId = operationId,
                    rootfsName = rootfsName,
                    operation = operation,
                    stage = RootfsCheckpointStage.PREPARING,
                    detail = if (operation == RootfsCheckpointOperation.BACKUP) {
                        "Preparing the rootfs backup"
                    } else {
                        "Preparing to restore the rootfs"
                    },
                    documentUri = uri.toString(),
                )
            publish(state)
            startForeground(NOTIFICATION_ID, notification(state))
            active =
                worker.submit {
                    runOperation(state, uri, startId)
                }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun runOperation(
        initial: RootfsCheckpointState,
        uri: Uri,
        startId: Int,
    ) {
        try {
            requireRootfsStopped(initial.rootfsName)
            when (initial.operation) {
                RootfsCheckpointOperation.BACKUP -> backup(initial, uri)
                RootfsCheckpointOperation.RESTORE -> restore(initial, uri)
            }
            val completed =
                initial.copy(
                    stage = RootfsCheckpointStage.COMPLETE,
                    detail =
                        if (initial.operation == RootfsCheckpointOperation.BACKUP) {
                            "Rootfs backup created"
                        } else {
                            "Rootfs restored from the selected backup"
                        },
                    progress = 100,
                    documentUri = null,
                )
            publish(completed)
            app.journal.append(
                component = "rootfs",
                severity = "info",
                event =
                    if (initial.operation == RootfsCheckpointOperation.BACKUP) {
                        "checkpoint_created"
                    } else {
                        "checkpoint_restored"
                    },
                message = completed.detail,
                bootId = initial.operationId,
                fields = mapOf("installation" to initial.rootfsName),
            )
        } catch (error: Throwable) {
            if (initial.operation == RootfsCheckpointOperation.BACKUP) {
                runCatching { contentResolver.delete(uri, null, null) }
            }
            val detail =
                if (error is InterruptedException || error is InterruptedIOException) {
                    "Checkpoint operation interrupted"
                } else {
                    error.message ?: "Checkpoint operation failed"
                }
            publish(
                initial.copy(
                    stage = RootfsCheckpointStage.FAILED,
                    detail = detail,
                    progress = null,
                    documentUri = null,
                ),
            )
            app.journal.append(
                component = "rootfs",
                severity = "error",
                event = "checkpoint_failed",
                message = detail,
                bootId = initial.operationId,
                fields =
                    mapOf(
                        "installation" to initial.rootfsName,
                        "operation" to initial.operation.name.lowercase(),
                        "exception" to error.javaClass.name,
                    ),
            )
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun requireRootfsStopped(rootfsName: String) {
        val runtime = app.runtimeState.current()
        check(
            runtime.rootfsName != rootfsName ||
                runtime.phase !in
                setOf(RuntimePhase.STARTING, RuntimePhase.RUNNING, RuntimePhase.STOPPING),
        ) {
            "Stop this Linux terminal before creating or restoring a backup"
        }
        check(
            runtime.desktop.rootfsName != rootfsName ||
                runtime.desktop.phase !in
                setOf(
                    DesktopSessionPhase.STARTING,
                    DesktopSessionPhase.RUNNING,
                    DesktopSessionPhase.STOPPING,
                ),
        ) {
            "Stop this desktop before creating or restoring a backup"
        }
    }

    private fun backup(
        state: RootfsCheckpointState,
        uri: Uri,
    ) {
        val rootfs =
            app.rootfsRegistry.all().firstOrNull { it.name == state.rootfsName }?.directory
                ?: error("Linux system ${state.rootfsName} is no longer installed")
        val totalBytes = RootfsStorageUsage.calculate(rootfs).coerceAtLeast(1L)
        val progress = ProgressPublisher(state, RootfsCheckpointStage.ARCHIVING, "Creating rootfs backup")
        val output =
            contentResolver.openOutputStream(uri, "wt")
                ?: error("Could not open the selected backup destination")
        output.buffered().use { buffered ->
            XZOutputStream(buffered, LZMA2Options(3)).use { compressed ->
                RootfsCheckpointArchiver(this, ProotRuntimeInstaller.install(this)).archive(
                    rootfs = rootfs,
                    output = compressed,
                    onProgress = { bytes -> progress.update(bytes, totalBytes) },
                )
            }
        }
    }

    private fun restore(
        state: RootfsCheckpointState,
        uri: Uri,
    ) {
        val checkpointDirectory = File(filesDir, "checkpoints").apply {
            check(mkdirs() || isDirectory) { "Could not create checkpoint storage" }
        }
        val partial = File(checkpointDirectory, "restore-${state.operationId}.tar.xz.part")
        val archive = File(checkpointDirectory, "restore-${state.operationId}.tar.xz")
        partial.delete()
        archive.delete()
        try {
            val totalBytes =
                runCatching {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }
                    .getOrDefault(-1L)
            val progress = ProgressPublisher(state, RootfsCheckpointStage.COPYING, "Reading the backup")
            val input = contentResolver.openInputStream(uri) ?: error("Could not open the selected backup")
            input.buffered().use { source ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copied = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("Checkpoint restore interrupted")
                        }
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (totalBytes > 0L) progress.update(copied, totalBytes)
                    }
                    output.fd.sync()
                }
            }
            check(partial.renameTo(archive)) { "Could not finish copying the backup" }
            RootfsStoragePreflight.requireSpace(archive, File(filesDir, "rootfs"))
            val extractionProgress =
                ProgressPublisher(state, RootfsCheckpointStage.EXTRACTING, "Extracting the checkpoint")
            RootfsCheckpointRestorer(this, ProotRuntimeInstaller.install(this)).restore(
                archive = archive,
                rootfsName = state.rootfsName,
                operationId = state.operationId,
                onExtractionProgress = extractionProgress::update,
                onStage = { stage, detail -> publish(state.copy(stage = stage, detail = detail)) },
            )
            ProotMountResolver.removeRuntime(this, state.rootfsName)
            LinuxApplicationShortcutPublisher(this).disableForRootfs(state.rootfsName)
            notifyRootfsDocumentsChanged(this)
        } finally {
            partial.delete()
            archive.delete()
        }
    }

    private inner class ProgressPublisher(
        private val state: RootfsCheckpointState,
        private val stage: RootfsCheckpointStage,
        private val detail: String,
    ) {
        private var lastProgress = -1

        fun update(
            completedBytes: Long,
            totalBytes: Long,
        ) {
            if (totalBytes <= 0L) return
            val progress = ((completedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
            if (progress == lastProgress) return
            lastProgress = progress
            publish(state.copy(stage = stage, detail = detail, progress = progress))
        }
    }

    private fun publish(state: RootfsCheckpointState) {
        app.checkpointState.save(state)
        updateNotification(state)
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_OPERATION_ID, state.operationId),
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Linux backups",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Linux rootfs backup and restore operations"
                },
            )
    }

    private fun notification(state: RootfsCheckpointState): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_SHOW_CHECKPOINT)
                    .putExtra(EXTRA_ROOTFS_NAME, state.rootfsName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(
                if (state.operation == RootfsCheckpointOperation.BACKUP) {
                    android.R.drawable.stat_sys_upload
                } else {
                    android.R.drawable.stat_sys_download
                },
            ).setContentTitle(
                if (state.operation == RootfsCheckpointOperation.BACKUP) {
                    "Backing up ${state.rootfsName}"
                } else {
                    "Restoring ${state.rootfsName}"
                },
            ).setContentText(state.detail)
            .setContentIntent(openIntent)
            .setOngoing(state.stage.running)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                state.progress?.let { setProgress(100, it, false) } ?: setProgress(0, 0, true)
            }.build()
    }

    private fun updateNotification(state: RootfsCheckpointState) {
        if (
            android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(state))
        }
    }

    companion object {
        const val ACTION_STATE_CHANGED = "org.randomcoder.udroid.action.CHECKPOINT_STATE_CHANGED"
        const val ACTION_SHOW_CHECKPOINT = "org.randomcoder.udroid.action.SHOW_CHECKPOINT"
        const val EXTRA_ROOTFS_NAME = "org.randomcoder.udroid.extra.CHECKPOINT_ROOTFS_NAME"

        private const val ACTION_BACKUP = "org.randomcoder.udroid.action.BACKUP_ROOTFS"
        private const val ACTION_RESTORE = "org.randomcoder.udroid.action.RESTORE_ROOTFS"
        private const val EXTRA_OPERATION_ID = "org.randomcoder.udroid.extra.CHECKPOINT_OPERATION_ID"
        private const val EXTRA_URI = "org.randomcoder.udroid.extra.CHECKPOINT_URI"
        private const val NOTIFICATION_CHANNEL = "rootfs-checkpoints"
        private const val NOTIFICATION_ID = 4405
        private const val COPY_BUFFER_BYTES = 64 * 1024

        fun backup(
            context: Context,
            rootfsName: String,
            destination: Uri,
        ): String = start(context, ACTION_BACKUP, rootfsName, destination)

        fun restore(
            context: Context,
            rootfsName: String,
            source: Uri,
        ): String = start(context, ACTION_RESTORE, rootfsName, source)

        private fun start(
            context: Context,
            action: String,
            rootfsName: String,
            uri: Uri,
        ): String {
            val operationId = UUID.randomUUID().toString()
            val stateStore = RootfsCheckpointStateStore(context)
            check(stateStore.current()?.stage?.running != true) {
                "Another rootfs backup or restore is already running"
            }
            val operation =
                if (action == ACTION_BACKUP) {
                    RootfsCheckpointOperation.BACKUP
                } else {
                    RootfsCheckpointOperation.RESTORE
                }
            val initial =
                RootfsCheckpointState(
                    operationId = operationId,
                    rootfsName = rootfsName,
                    operation = operation,
                    stage = RootfsCheckpointStage.PREPARING,
                    detail =
                        if (operation == RootfsCheckpointOperation.BACKUP) {
                            "Preparing the rootfs backup"
                        } else {
                            "Preparing to restore the rootfs"
                        },
                    documentUri = uri.toString(),
                )
            stateStore.save(initial)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RootfsCheckpointService::class.java)
                        .setAction(action)
                        .putExtra(EXTRA_ROOTFS_NAME, rootfsName)
                        .putExtra(EXTRA_OPERATION_ID, operationId)
                        .putExtra(EXTRA_URI, uri.toString()),
                )
            } catch (error: Throwable) {
                stateStore.save(
                    initial.copy(
                        stage = RootfsCheckpointStage.FAILED,
                        detail = error.message ?: "Could not start the checkpoint operation",
                        documentUri = null,
                    ),
                )
                throw error
            }
            return operationId
        }
    }
}

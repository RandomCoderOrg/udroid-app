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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.randomcoder.udroid.MainActivity
import org.randomcoder.udroid.UdroidApplication
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.catalog.DistroProvider
import org.randomcoder.udroid.catalog.LinuxDistribution
import java.io.File
import java.io.InterruptedIOException
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class InstallerService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val activeTask = AtomicReference<Future<*>?>(null)

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
        return when (intent?.action) {
            ACTION_START -> {
                val distro = distroFromIntent(intent) ?: app.installState.current()?.distro
                if (distro == null) {
                    stopSelf(startId)
                    START_NOT_STICKY
                } else {
                    startForeground(NOTIFICATION_ID, notification("Preparing download", null))
                    startArtifactOperation(distro)
                    START_REDELIVER_INTENT
                }
            }

            ACTION_PAUSE -> {
                pauseArtifactOperation()
                START_NOT_STICKY
            }

            else -> {
                val persisted = app.installState.current()
                if (persisted?.cancellable == true) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification("Recovering download", persisted.percentage),
                    )
                    startArtifactOperation(persisted.distro)
                    START_REDELIVER_INTENT
                } else {
                    stopSelf(startId)
                    START_NOT_STICKY
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startArtifactOperation(distro: DistroVariant) {
        if (activeTask.get()?.isDone == false) return

        val previous = app.installState.current()
        val operationId =
            previous?.operationId
                ?.takeIf { previous.distro.id == distro.id }
                ?: UUID.randomUUID().toString()
        val startingLines =
            previous?.terminalLines
                ?.takeIf { previous.distro.id == distro.id }
                .orEmpty()
                .plus(
                    if (previous?.stage == InstallStage.PAUSED) {
                        "[resume] continuing ${distro.id} from the saved partial archive"
                    } else {
                        "\$ udroid pull ${distro.id}"
                    },
                )
        publish(
            InstallProgress(
                distro = distro,
                stage = InstallStage.CHECKING,
                stageProgress = 0f,
                currentDetail = "Preparing secure download storage",
                terminalLines = startingLines,
                previewOnly = false,
                operationId = operationId,
                completedBytes = previous?.completedBytes ?: 0L,
                totalBytes = previous?.totalBytes ?: -1L,
                cancellable = true,
            ),
        )
        app.journal.append(
            component = "installer",
            severity = "info",
            event = "artifact_requested",
            message = "Rootfs artifact download requested",
            bootId = operationId,
            fields =
                mapOf(
                    "distro" to distro.id,
                    "architecture" to distro.architecture,
                ),
        )

        val future =
            worker.submit {
                runArtifactOperation(distro, operationId)
            }
        activeTask.set(future)
    }

    private fun runArtifactOperation(
        distro: DistroVariant,
        operationId: String,
    ) {
        val rootfsDirectory = File(filesDir, "rootfs")
        val installedRootfs = File(rootfsDirectory, distro.internalName)
        if (File(installedRootfs, RootfsInstallationPipeline.READY_MARKER).isFile) {
            publishCompleted(distro, operationId, installedRootfs, reused = true)
            activeTask.set(null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val cacheDirectory = File(filesDir, "artifacts").apply { mkdirs() }
        val suffix = artifactSuffix(distro.downloadUrl)
        val finalFile = File(cacheDirectory, "${distro.internalName}$suffix")
        val stagingFile = File(cacheDirectory, "${distro.internalName}$suffix.part")
        val progressPublisher = ProgressPublisher(distro, operationId)

        try {
            val result =
                ResumableArtifactPipeline().execute(
                    request =
                        ArtifactRequest(
                            url = distro.downloadUrl,
                            expectedSha256 = distro.sha256,
                            stagingFile = stagingFile,
                            finalFile = finalFile,
                        ),
                    onDownloadProgress = progressPublisher::download,
                    onVerifyProgress = progressPublisher::verify,
                )
            val previous = app.installState.current()
            publish(
                InstallProgress(
                    distro = distro,
                    stage = InstallStage.ARCHIVE_READY,
                    stageProgress = 1f,
                    currentDetail = "${formatBytes(result.byteCount)} verified; preparing extraction",
                    terminalLines =
                        previous?.terminalLines.orEmpty() +
                            if (result.reusedVerifiedFile) {
                                "[ok] reused verified ${result.file.name}"
                            } else {
                                "[ok] sha256 verified · ${result.file.name}"
                            },
                    previewOnly = false,
                    operationId = operationId,
                    completedBytes = result.byteCount,
                    totalBytes = result.byteCount,
                    cancellable = true,
                ),
            )
            app.journal.append(
                component = "installer",
                severity = "info",
                event = "artifact_ready",
                message = "Rootfs artifact downloaded and verified",
                bootId = operationId,
                fields =
                    mapOf(
                        "distro" to distro.id,
                        "bytes" to result.byteCount,
                        "resumed" to result.resumed,
                        "reused" to result.reusedVerifiedFile,
                    ),
            )
            installRootfs(distro, operationId, result.file, rootfsDirectory, progressPublisher)
        } catch (error: Throwable) {
            val previous = app.installState.current()
            val interrupted =
                error is InterruptedIOException ||
                    error is InterruptedException ||
                    Thread.currentThread().isInterrupted
            val next =
                if (interrupted) {
                    InstallProgress(
                        distro = distro,
                        stage = InstallStage.PAUSED,
                        stageProgress = previous?.overallProgress ?: 0f,
                        currentDetail = pauseDetail(previous),
                        terminalLines =
                            previous?.terminalLines.orEmpty() +
                                if (previous?.stage == InstallStage.EXTRACTING) {
                                    "[paused] incomplete rootfs discarded · verified archive retained"
                                } else {
                                    "[paused] partial archive retained"
                                },
                        previewOnly = false,
                        operationId = operationId,
                        completedBytes = previous?.completedBytes ?: 0L,
                        totalBytes = previous?.totalBytes ?: -1L,
                        cancellable = false,
                    )
                } else {
                    InstallProgress(
                        distro = distro,
                        stage = InstallStage.FAILED,
                        stageProgress = 0f,
                        currentDetail = error.message ?: error.javaClass.simpleName,
                        terminalLines =
                            previous?.terminalLines.orEmpty() +
                                "[error] ${error.message ?: error.javaClass.simpleName}",
                        previewOnly = false,
                        operationId = operationId,
                        completedBytes = previous?.completedBytes ?: 0L,
                        totalBytes = previous?.totalBytes ?: -1L,
                        cancellable = false,
                    )
                }
            publish(next)
            app.journal.append(
                component = "installer",
                severity = if (interrupted) "info" else "error",
                event = if (interrupted) "artifact_paused" else "artifact_failed",
                message = next.currentDetail,
                bootId = operationId,
                fields =
                    mapOf(
                        "distro" to distro.id,
                        "exception" to error.javaClass.name,
                    ),
            )
            updateNotification(
                if (interrupted) "Installation paused" else "Installation needs attention",
                next.percentage,
            )
        } finally {
            activeTask.set(null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun installRootfs(
        distro: DistroVariant,
        operationId: String,
        archive: File,
        rootfsDirectory: File,
        progressPublisher: ProgressPublisher,
    ) {
        RootfsInstallationPipeline.clearInterruptedInstallation(
            rootfsDirectory = rootfsDirectory,
            installationName = distro.internalName,
        )
        RootfsStoragePreflight.requireSpace(archive, rootfsDirectory)
        progressPublisher.configure(
            fraction = 0.05f,
            detail = "Preparing the packaged PRoot runtime",
            terminalLine = "[configure] storage preflight passed",
        )
        val prootRuntime = ProotRuntimeInstaller.install(this)
        val pipeline =
            RootfsInstallationPipeline(
                extractor =
                    ProotTarExtractor(
                        context = this,
                        runtime = prootRuntime,
                        stripComponents = distro.archiveStripComponents,
                        onDiagnostic = { line ->
                            app.journal.append(
                                component = "installer",
                                severity = "debug",
                                event = "proot_extract",
                                message = line,
                                bootId = operationId,
                            )
                        },
                    ),
                configurator = AndroidRootfsConfigurator(),
                healthCheck = ProotRootfsHealthCheck(this, prootRuntime),
            )
        val result =
            pipeline.execute(
                request =
                    RootfsInstallRequest(
                        archive = archive,
                        rootfsDirectory = rootfsDirectory,
                        installationName = distro.internalName,
                        operationId = operationId,
                    ),
                onExtractionProgress = progressPublisher::extract,
                onConfiguring = { detail ->
                    val fraction =
                        if (detail.contains("health", ignoreCase = true)) 0.80f else 0.35f
                    progressPublisher.configure(
                        fraction = fraction,
                        detail = detail,
                        terminalLine =
                            if (fraction > 0.5f) {
                                "[probe] proot /usr/bin/env"
                            } else {
                                "[configure] resolver, profile, proc and Android groups"
                            },
                    )
                },
            )
        publishCompleted(distro, operationId, result.rootfs, result.reusedInstallation)
    }

    private fun publishCompleted(
        distro: DistroVariant,
        operationId: String,
        rootfs: File,
        reused: Boolean,
    ) {
        app.rootfsRegistry.setActiveIfNone(rootfs.name)
        val previous = app.installState.current()
        val completed =
            InstallProgress(
                distro = distro,
                stage = InstallStage.COMPLETE,
                stageProgress = 1f,
                currentDetail = "Installed at ${rootfs.name}",
                terminalLines =
                    previous?.terminalLines.orEmpty() +
                        if (reused) {
                            "[complete] reused healthy ${rootfs.name}"
                        } else {
                            "[complete] health check passed · marked ${rootfs.name} ready"
                        },
                previewOnly = false,
                operationId = operationId,
                completedBytes = previous?.totalBytes ?: 0L,
                totalBytes = previous?.totalBytes ?: -1L,
                cancellable = false,
            )
        publish(completed)
        app.journal.append(
            component = "installer",
            severity = "info",
            event = "rootfs_ready",
            message = "Rootfs installation is ready",
            bootId = operationId,
            fields =
                mapOf(
                    "distro" to distro.id,
                    "path" to rootfs.absolutePath,
                    "reused" to reused,
                ),
        )
        updateNotification("Linux system is ready", 100)
    }

    private fun pauseDetail(previous: InstallProgress?): String =
        if (previous?.stage == InstallStage.EXTRACTING ||
            previous?.stage == InstallStage.CONFIGURING
        ) {
            "Verified archive saved; incomplete setup can restart"
        } else {
            "${formatBytes(previous?.completedBytes ?: 0L)} saved for resume"
        }

    private fun pauseArtifactOperation() {
        val current = app.installState.current()
        if (current?.cancellable == true) {
            publish(
                current.copy(
                    stage = InstallStage.PAUSED,
                    stageProgress = current.overallProgress,
                    currentDetail = "${formatBytes(current.completedBytes)} saved for resume",
                    terminalLines = current.terminalLines + "[paused] pause requested",
                    cancellable = false,
                ),
            )
        }
        activeTask.getAndSet(null)?.cancel(true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish(progress: InstallProgress) {
        app.installState.save(
            progress.copy(terminalLines = progress.terminalLines.takeLast(MAX_TERMINAL_LINES)),
        )
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_STAGE, progress.stage.name)
                .putExtra(EXTRA_PERCENTAGE, progress.percentage),
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "uDroid installation",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Linux image download, verification, and installation"
                },
            )
    }

    private fun notification(
        text: String,
        progress: Int?,
    ): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val pauseIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, InstallerService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("uDroid Linux image")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (progress == null) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(100, progress, false)
                }
            }
            .addAction(0, "Pause", pauseIntent)
            .build()
    }

    private fun updateNotification(
        text: String,
        progress: Int,
    ) {
        if (
            android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(text, progress))
        }
    }

    private inner class ProgressPublisher(
        private val distro: DistroVariant,
        private val operationId: String,
    ) {
        private var lastPublishedAt = 0L
        private var lastSpeedAt = System.currentTimeMillis()
        private var lastSpeedBytes = app.installState.current()?.completedBytes ?: 0L
        private var lastTerminalBucket = -1
        private var lastExtractionBucket = -1
        private var lastNotificationAt = 0L

        fun download(progress: ByteProgress) {
            val now = System.currentTimeMillis()
            val finished = progress.totalBytes > 0L && progress.completedBytes >= progress.totalBytes
            if (!finished && now - lastPublishedAt < PROGRESS_PERSIST_INTERVAL_MS) return

            val elapsed = (now - lastSpeedAt).coerceAtLeast(1L)
            val speed =
                ((progress.completedBytes - lastSpeedBytes).coerceAtLeast(0L) * 1_000L) / elapsed
            lastSpeedAt = now
            lastSpeedBytes = progress.completedBytes
            lastPublishedAt = now

            val fraction =
                if (progress.totalBytes > 0L) {
                    progress.completedBytes.toFloat() / progress.totalBytes.toFloat()
                } else {
                    0f
                }
            val bucket = (fraction * 10f).roundToInt()
            val previous = app.installState.current()
            val line =
                if (bucket > lastTerminalBucket && progress.totalBytes > 0L) {
                    lastTerminalBucket = bucket
                    "[download] ${formatBytes(progress.completedBytes)} / " +
                        "${formatBytes(progress.totalBytes)} · ${formatRate(speed)}" +
                        if (progress.resumed) " · resumed" else ""
                } else {
                    null
                }
            val next =
                InstallProgress(
                    distro = distro,
                    stage = InstallStage.DOWNLOADING,
                    stageProgress = fraction.coerceIn(0f, 1f),
                    currentDetail =
                        if (progress.totalBytes > 0L) {
                            "${formatBytes(progress.completedBytes)} of " +
                                "${formatBytes(progress.totalBytes)} · ${formatRate(speed)}"
                        } else {
                            "${formatBytes(progress.completedBytes)} downloaded · ${formatRate(speed)}"
                        },
                    terminalLines = previous?.terminalLines.orEmpty() + listOfNotNull(line),
                    previewOnly = false,
                    operationId = operationId,
                    completedBytes = progress.completedBytes,
                    totalBytes = progress.totalBytes,
                    bytesPerSecond = speed,
                    cancellable = true,
                )
            publish(next)
            if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS || finished) {
                lastNotificationAt = now
                updateNotification(
                    "Downloading ${distro.releaseName} · ${next.percentage}%",
                    next.percentage,
                )
            }
        }

        fun verify(progress: ByteProgress) {
            val now = System.currentTimeMillis()
            val finished = progress.totalBytes > 0L && progress.completedBytes >= progress.totalBytes
            if (!finished && now - lastPublishedAt < PROGRESS_PERSIST_INTERVAL_MS) return
            lastPublishedAt = now
            val fraction =
                if (progress.totalBytes > 0L) {
                    progress.completedBytes.toFloat() / progress.totalBytes.toFloat()
                } else {
                    0f
                }
            val previous = app.installState.current()
            val next =
                InstallProgress(
                    distro = distro,
                    stage = InstallStage.VERIFYING,
                    stageProgress = fraction.coerceIn(0f, 1f),
                    currentDetail =
                        "Checked ${formatBytes(progress.completedBytes)} of " +
                            formatBytes(progress.totalBytes),
                    terminalLines =
                        if (previous?.stage != InstallStage.VERIFYING) {
                            previous?.terminalLines.orEmpty() + "[verify] sha256 ${distro.sha256}"
                        } else {
                            previous.terminalLines
                        },
                    previewOnly = false,
                    operationId = operationId,
                    completedBytes = progress.completedBytes,
                    totalBytes = progress.totalBytes,
                    cancellable = true,
                )
            publish(next)
            if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS || finished) {
                lastNotificationAt = now
                updateNotification("Verifying Linux image · ${next.percentage}%", next.percentage)
            }
        }

        fun extract(
            completedBytes: Long,
            totalBytes: Long,
        ) {
            val now = System.currentTimeMillis()
            val finished = totalBytes > 0L && completedBytes >= totalBytes
            if (!finished && now - lastPublishedAt < PROGRESS_PERSIST_INTERVAL_MS) return
            lastPublishedAt = now
            val fraction =
                if (totalBytes > 0L) completedBytes.toFloat() / totalBytes.toFloat() else 0f
            val previous = app.installState.current()
            val bucket = (fraction * 10f).roundToInt()
            val line =
                if (bucket > lastExtractionBucket) {
                    lastExtractionBucket = bucket
                    "[extract] ${formatBytes(completedBytes)} / ${formatBytes(totalBytes)} streamed"
                } else {
                    null
                }
            val next =
                InstallProgress(
                    distro = distro,
                    stage = InstallStage.EXTRACTING,
                    stageProgress = fraction.coerceIn(0f, 1f),
                    currentDetail =
                        "Unpacked ${formatBytes(completedBytes)} of ${formatBytes(totalBytes)}",
                    terminalLines = previous?.terminalLines.orEmpty() + listOfNotNull(line),
                    previewOnly = false,
                    operationId = operationId,
                    completedBytes = completedBytes,
                    totalBytes = totalBytes,
                    cancellable = true,
                )
            publish(next)
            if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS || finished) {
                lastNotificationAt = now
                updateNotification("Building Linux system · ${next.percentage}%", next.percentage)
            }
        }

        fun configure(
            fraction: Float,
            detail: String,
            terminalLine: String,
        ) {
            val previous = app.installState.current()
            val next =
                InstallProgress(
                    distro = distro,
                    stage = InstallStage.CONFIGURING,
                    stageProgress = fraction,
                    currentDetail = detail,
                    terminalLines =
                        if (previous?.terminalLines?.lastOrNull() == terminalLine) {
                            previous.terminalLines
                        } else {
                            previous?.terminalLines.orEmpty() + terminalLine
                        },
                    previewOnly = false,
                    operationId = operationId,
                    completedBytes = previous?.completedBytes ?: 0L,
                    totalBytes = previous?.totalBytes ?: -1L,
                    cancellable = true,
                )
            publish(next)
            updateNotification("Finishing Linux setup · ${next.percentage}%", next.percentage)
        }
    }

    companion object {
        const val ACTION_STATE_CHANGED = "org.randomcoder.udroid.action.INSTALL_STATE_CHANGED"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_PERCENTAGE = "percentage"

        private const val ACTION_START = "org.randomcoder.udroid.action.START_INSTALL"
        private const val ACTION_PAUSE = "org.randomcoder.udroid.action.PAUSE_INSTALL"
        private const val NOTIFICATION_CHANNEL = "installer"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_TERMINAL_LINES = 160
        private const val PROGRESS_PERSIST_INTERVAL_MS = 300L
        private const val NOTIFICATION_INTERVAL_MS = 1_000L

        private const val EXTRA_SUITE = "suite"
        private const val EXTRA_VARIANT = "variant"
        private const val EXTRA_INTERNAL_NAME = "internal-name"
        private const val EXTRA_FRIENDLY_NAME = "friendly-name"
        private const val EXTRA_ARCHITECTURE = "architecture"
        private const val EXTRA_DOWNLOAD_URL = "download-url"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_DISTRIBUTION = "distribution"
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_RELEASE_LABEL = "release-label"
        private const val EXTRA_ARCHIVE_STRIP_COMPONENTS = "archive-strip-components"

        fun start(
            context: Context,
            distro: DistroVariant,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, InstallerService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SUITE, distro.suite)
                    .putExtra(EXTRA_VARIANT, distro.variant)
                    .putExtra(EXTRA_INTERNAL_NAME, distro.internalName)
                    .putExtra(EXTRA_FRIENDLY_NAME, distro.friendlyName)
                    .putExtra(EXTRA_ARCHITECTURE, distro.architecture)
                    .putExtra(EXTRA_DOWNLOAD_URL, distro.downloadUrl)
                    .putExtra(EXTRA_SHA256, distro.sha256)
                    .putExtra(EXTRA_DISTRIBUTION, distro.distribution.id)
                    .putExtra(EXTRA_PROVIDER, distro.provider.name)
                    .putExtra(EXTRA_RELEASE_LABEL, distro.releaseLabel)
                    .putExtra(
                        EXTRA_ARCHIVE_STRIP_COMPONENTS,
                        distro.archiveStripComponents,
                    ),
            )
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, InstallerService::class.java).setAction(ACTION_PAUSE),
            )
        }

        private fun distroFromIntent(intent: Intent): DistroVariant? {
            val suite = intent.getStringExtra(EXTRA_SUITE) ?: return null
            return DistroVariant(
                suite = suite,
                variant = intent.getStringExtra(EXTRA_VARIANT) ?: return null,
                internalName = intent.getStringExtra(EXTRA_INTERNAL_NAME) ?: return null,
                friendlyName = intent.getStringExtra(EXTRA_FRIENDLY_NAME) ?: return null,
                architecture = intent.getStringExtra(EXTRA_ARCHITECTURE) ?: return null,
                downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: return null,
                sha256 = intent.getStringExtra(EXTRA_SHA256) ?: return null,
                distribution =
                    intent.getStringExtra(EXTRA_DISTRIBUTION)
                        ?.let { id -> LinuxDistribution.entries.firstOrNull { it.id == id } }
                        ?: LinuxDistribution.UBUNTU,
                provider =
                    intent.getStringExtra(EXTRA_PROVIDER)
                        ?.let { name -> DistroProvider.entries.firstOrNull { it.name == name } }
                        ?: DistroProvider.UDROID,
                releaseLabel = intent.getStringExtra(EXTRA_RELEASE_LABEL),
                archiveStripComponents =
                    intent.getIntExtra(EXTRA_ARCHIVE_STRIP_COMPONENTS, 0),
            )
        }

        private fun artifactSuffix(url: String): String {
            val path = runCatching { URL(url).path.lowercase(Locale.US) }.getOrDefault("")
            return when {
                path.endsWith(".tar.xz") -> ".tar.xz"
                path.endsWith(".tar.zst") -> ".tar.zst"
                path.endsWith(".tgz") -> ".tgz"
                else -> ".tar.gz"
            }
        }

        private fun formatBytes(bytes: Long): String =
            when {
                bytes < 0L -> "unknown size"
                bytes >= 1024L * 1024L * 1024L ->
                    String.format(
                        Locale.US,
                        "%.2f GiB",
                        bytes / (1024.0 * 1024.0 * 1024.0),
                    )
                bytes >= 1024L * 1024L ->
                    String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
                else -> "$bytes B"
            }

        private fun formatRate(bytesPerSecond: Long): String =
            if (bytesPerSecond <= 0L) "measuring…" else "${formatBytes(bytesPerSecond)}/s"
    }
}

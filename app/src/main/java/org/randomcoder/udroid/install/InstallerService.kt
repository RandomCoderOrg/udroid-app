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
import org.randomcoder.udroid.oci.OciImageReference
import org.randomcoder.udroid.oci.OciInstallEvent
import org.randomcoder.udroid.oci.OciInstallStage
import org.randomcoder.udroid.oci.OciPlatform
import org.randomcoder.udroid.oci.OciRootfsInstallRequest
import org.randomcoder.udroid.oci.OciRootfsInstaller
import java.io.File
import java.io.InterruptedIOException
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

class InstallerService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val operationLock = Any()
    private var activeOperation: ActiveOperation? = null

    private class ActiveOperation(
        val work: InstallerWorkRequest,
        var startId: Int,
        var future: Future<*>? = null,
    )

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
                val encodedWork = intent.getStringExtra(EXTRA_WORK_REQUEST)
                val work =
                    if (encodedWork == null) {
                        app.installState.current()?.work
                    } else {
                        runCatching { InstallerWorkRequestCodec.decode(encodedWork) }
                            .getOrElse { error ->
                                app.journal.append(
                                    component = "installer",
                                    severity = "error",
                                    event = "work_rejected",
                                    message = error.message ?: "Invalid installer work request",
                                    bootId = null,
                                    fields = mapOf("exception" to error.javaClass.name),
                                )
                                stopSelf(startId)
                                return START_NOT_STICKY
                            }
                    }
                if (work == null) {
                    stopSelf(startId)
                    START_NOT_STICKY
                } else {
                    startForeground(
                        NOTIFICATION_ID,
                        notification("Preparing ${work.displayName}", null),
                    )
                    startWork(work, startId)
                    START_REDELIVER_INTENT
                }
            }

            ACTION_PAUSE -> {
                pauseWorkOperation(startId)
                START_NOT_STICKY
            }

            else -> {
                val persisted = app.installState.current()
                if (persisted?.cancellable == true) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification("Recovering download", persisted.percentage),
                    )
                    startWork(persisted.work, startId)
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

    private fun startWork(
        work: InstallerWorkRequest,
        startId: Int,
    ) {
        val operation = claimOperation(work, startId) ?: return
        try {
            when (work) {
                is InstallerWorkRequest.Archive ->
                    startArtifactOperation(work, operation)

                is InstallerWorkRequest.Oci ->
                    startOciOperation(work, operation)
            }
        } catch (error: Throwable) {
            completeOperation(operation)
            throw error
        }
    }

    private fun startArtifactOperation(
        work: InstallerWorkRequest.Archive,
        operation: ActiveOperation,
    ) {
        val distro = work.distro
        val previous = app.installState.current()
        val operationId = work.operationId
        val startingLines =
            previous?.terminalLines
                ?.takeIf { previous.archiveDistro?.id == distro.id }
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
                work = work,
                stage = InstallStage.CHECKING,
                stageProgress = 0f,
                currentDetail = "Preparing secure download storage",
                terminalLines = startingLines,
                previewOnly = false,
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
                runArtifactOperation(work, operation)
            }
        attachFuture(operation, future)
    }

    private fun runArtifactOperation(
        work: InstallerWorkRequest.Archive,
        operation: ActiveOperation,
    ) {
        val distro = work.distro
        val operationId = work.operationId
        val rootfsDirectory = File(filesDir, "rootfs")
        val installedRootfs = File(rootfsDirectory, distro.internalName)
        if (File(installedRootfs, RootfsInstallationPipeline.READY_MARKER).isFile) {
            publishCompleted(work, installedRootfs, reused = true)
            finishOperation(operation)
            return
        }
        val cacheDirectory = File(filesDir, "artifacts").apply { mkdirs() }
        val suffix = artifactSuffix(distro.downloadUrl)
        val finalFile = File(cacheDirectory, "${distro.internalName}$suffix")
        val stagingFile = File(cacheDirectory, "${distro.internalName}$suffix.part")
        val progressPublisher = ProgressPublisher(work)

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
                    work = work,
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
            installRootfs(work, result.file, rootfsDirectory, progressPublisher)
        } catch (error: Throwable) {
            val previous = app.installState.current()
            val interrupted =
                error is InterruptedIOException ||
                    error is InterruptedException ||
                    Thread.currentThread().isInterrupted
            val next =
                if (interrupted) {
                    InstallProgress(
                        work = work,
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
                        completedBytes = previous?.completedBytes ?: 0L,
                        totalBytes = previous?.totalBytes ?: -1L,
                        cancellable = false,
                    )
                } else {
                    InstallProgress(
                        work = work,
                        stage = InstallStage.FAILED,
                        stageProgress = 0f,
                        currentDetail = error.message ?: error.javaClass.simpleName,
                        terminalLines =
                            previous?.terminalLines.orEmpty() +
                                "[error] ${error.message ?: error.javaClass.simpleName}",
                        previewOnly = false,
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
            finishOperation(operation)
        }
    }

    private fun startOciOperation(
        work: InstallerWorkRequest.Oci,
        operation: ActiveOperation,
    ) {
        val previous =
            app.installState.current()
                ?.takeIf { it.operationId == work.operationId }
        publish(
            InstallProgress(
                work = work,
                stage = InstallStage.CHECKING,
                stageProgress = 0f,
                currentDetail = "Preparing secure OCI image storage",
                terminalLines =
                    previous?.terminalLines.orEmpty() +
                        if (previous?.stage == InstallStage.PAUSED) {
                            "[resume] continuing ${work.reference} from verified image data"
                        } else {
                            "\$ udroid install ${work.reference}"
                        },
                previewOnly = false,
                completedBytes = previous?.completedBytes ?: 0L,
                totalBytes = previous?.totalBytes ?: -1L,
                cancellable = true,
            ),
        )
        app.journal.append(
            component = "installer",
            severity = "info",
            event = "oci_requested",
            message = "OCI rootfs installation requested",
            bootId = work.operationId,
            fields =
                mapOf(
                    "reference" to work.reference.toString(),
                    "platform" to
                        listOfNotNull(
                            work.platform.os,
                            work.platform.architecture,
                            work.platform.variant,
                        ).joinToString("/"),
                    "installation" to work.installationName,
                ),
        )
        val future = worker.submit { runOciOperation(work, operation) }
        attachFuture(operation, future)
    }

    private fun runOciOperation(
        work: InstallerWorkRequest.Oci,
        operation: ActiveOperation,
    ) {
        val progress = OciForegroundProgress(work)
        try {
            val result =
                OciRootfsInstaller(this).install(
                    request =
                        OciRootfsInstallRequest(
                            reference = work.reference,
                            platform = work.platform,
                            rootfsDirectory = File(filesDir, "rootfs"),
                            blobCacheDirectory = File(filesDir, "artifacts/oci-blobs"),
                            installationName = work.installationName,
                            operationId = work.operationId,
                        ),
                    onEvent = progress::publish,
                )
            app.rootfsRegistry.setActiveIfNone(result.rootfs.name)
            progress.complete(result.reusedInstallation)
            app.journal.append(
                component = "installer",
                severity = "info",
                event = "rootfs_ready",
                message = "OCI rootfs installation is ready",
                bootId = work.operationId,
                fields =
                    mapOf(
                        "source" to "oci",
                        "reference" to work.reference.toString(),
                        "manifest" to result.manifestDigest,
                        "path" to result.rootfs.absolutePath,
                        "compressed_bytes" to result.compressedBytes,
                        "reused" to result.reusedInstallation,
                    ),
            )
        } catch (error: Throwable) {
            val interrupted =
                error is InterruptedIOException ||
                    error is InterruptedException ||
                    Thread.currentThread().isInterrupted
            progress.fail(error, interrupted)
            val title =
                if (interrupted) {
                    "OCI installation paused"
                } else {
                    "OCI installation needs attention"
                }
            app.journal.append(
                component = "installer",
                severity = if (interrupted) "info" else "error",
                event = if (interrupted) "oci_paused" else "oci_failed",
                message = error.message ?: error.javaClass.simpleName,
                bootId = work.operationId,
                fields =
                    mapOf(
                        "source" to "oci",
                        "reference" to work.reference.toString(),
                        "installation" to work.installationName,
                        "exception" to error.javaClass.name,
                    ),
            )
            updateNotification(title, progress.percentage)
        } finally {
            finishOperation(operation)
        }
    }

    private fun installRootfs(
        work: InstallerWorkRequest.Archive,
        archive: File,
        rootfsDirectory: File,
        progressPublisher: ProgressPublisher,
    ) {
        val distro = work.distro
        val operationId = work.operationId
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
        publishCompleted(work, result.rootfs, result.reusedInstallation)
    }

    private fun publishCompleted(
        work: InstallerWorkRequest.Archive,
        rootfs: File,
        reused: Boolean,
    ) {
        val distro = work.distro
        val operationId = work.operationId
        app.rootfsRegistry.setActiveIfNone(rootfs.name)
        val previous = app.installState.current()
        val completed =
            InstallProgress(
                work = work,
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

    private fun pauseWorkOperation(startId: Int) {
        val operation =
            synchronized(operationLock) {
                activeOperation.also { activeOperation = null }
            }
        val work = operation?.work
        val current = app.installState.current()
        if (work != null && current?.operationId == work.operationId && current.cancellable) {
            publish(
                current.copy(
                    stage = InstallStage.PAUSED,
                    stageProgress = current.overallProgress,
                    currentDetail =
                        when (work) {
                            is InstallerWorkRequest.Archive ->
                                "${formatBytes(current.completedBytes)} saved for resume"
                            is InstallerWorkRequest.Oci ->
                                "Partial verified image data saved for resume"
                        },
                    terminalLines = current.terminalLines + "[paused] pause requested",
                    cancellable = false,
                ),
            )
        }
        if (work is InstallerWorkRequest.Oci) {
            app.journal.append(
                component = "installer",
                severity = "info",
                event = "oci_pause_requested",
                message = "Pause requested; partial verified blobs will be retained",
                bootId = work.operationId,
                fields =
                    mapOf(
                        "source" to "oci",
                        "reference" to work.reference.toString(),
                        "installation" to work.installationName,
                ),
            )
        }
        operation?.future?.cancel(true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun claimOperation(
        work: InstallerWorkRequest,
        startId: Int,
    ): ActiveOperation? =
        synchronized(operationLock) {
            activeOperation?.let { current ->
                current.startId = startId
                return@synchronized null
            }
            ActiveOperation(work, startId).also { activeOperation = it }
        }

    private fun attachFuture(
        operation: ActiveOperation,
        future: Future<*>,
    ) {
        val shouldCancel =
            synchronized(operationLock) {
                operation.future = future
                activeOperation !== operation
            }
        if (shouldCancel) future.cancel(true)
    }

    private fun completeOperation(operation: ActiveOperation): Boolean =
        synchronized(operationLock) {
            if (activeOperation === operation) {
                activeOperation = null
                true
            } else {
                false
            }
        }

    private fun finishOperation(operation: ActiveOperation) {
        if (completeOperation(operation)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelfResult(operation.startId)
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

    private inner class OciForegroundProgress(
        private val work: InstallerWorkRequest.Oci,
    ) {
        var percentage: Int = 0
            private set

        private var lastStage: OciInstallStage? = null
        private var lastPublishedAt = 0L
        private var lastNotificationAt = 0L
        private var lastSpeedAt = System.currentTimeMillis()
        private var lastSpeedBytes =
            app.installState.current()
                ?.takeIf { it.operationId == work.operationId }
                ?.completedBytes
                ?: 0L
        private var lastTerminalBucket = -1

        fun publish(event: OciInstallEvent) {
            val now = System.currentTimeMillis()
            val rawStageChanged = event.stage != lastStage
            val mapped = OciInstallProgressMapping.map(event)
            percentage =
                maxOf(
                    percentage,
                    mapped.percentage,
                )
            if (rawStageChanged) {
                lastStage = event.stage
                app.journal.append(
                    component = "installer",
                    severity = "info",
                    event = "oci_${event.stage.name.lowercase(Locale.US)}",
                    message = event.detail,
                    bootId = work.operationId,
                    fields =
                        mapOf(
                            "source" to "oci",
                            "reference" to work.reference.toString(),
                            "installation" to work.installationName,
                            "completed_bytes" to event.completedBytes,
                            "total_bytes" to event.totalBytes,
                            "resumed" to event.resumed,
                        ),
                )
            }
            val terminalLine =
                if (rawStageChanged) {
                    "[${event.stage.terminalLabel()}] ${event.detail}"
                } else if (
                    event.totalBytes > 0L &&
                    event.stage == OciInstallStage.DOWNLOADING
                ) {
                    val bucket =
                        ((event.completedBytes.toDouble() / event.totalBytes.toDouble()) * 10.0)
                            .roundToInt()
                    if (bucket > lastTerminalBucket) {
                        lastTerminalBucket = bucket
                        "[download] ${formatBytes(event.completedBytes)} / " +
                            formatBytes(event.totalBytes) +
                            if (event.resumed) " · resumed" else ""
                    } else {
                        null
                    }
                } else {
                    null
                }
            val finished = event.stage == OciInstallStage.READY
            if (
                rawStageChanged ||
                finished ||
                now - lastPublishedAt >= PROGRESS_PERSIST_INTERVAL_MS
            ) {
                val previous =
                    app.installState.current()
                        ?.takeIf { it.operationId == work.operationId }
                val elapsed = (now - lastSpeedAt).coerceAtLeast(1L)
                val speed =
                    if (event.stage == OciInstallStage.DOWNLOADING) {
                        ((event.completedBytes - lastSpeedBytes).coerceAtLeast(0L) * 1_000L) /
                            elapsed
                    } else {
                        0L
                    }
                if (event.stage == OciInstallStage.DOWNLOADING) {
                    lastSpeedAt = now
                    lastSpeedBytes = event.completedBytes
                }
                val previousFraction =
                    previous
                        ?.takeIf { it.stage == mapped.stage }
                        ?.stageProgress
                        ?: 0f
                val next =
                    InstallProgress(
                        work = work,
                        stage = mapped.stage,
                        stageProgress = maxOf(previousFraction, mapped.stageProgress),
                        currentDetail = event.detail,
                        terminalLines =
                            previous?.terminalLines.orEmpty() + listOfNotNull(terminalLine),
                        previewOnly = false,
                        completedBytes =
                            if (event.totalBytes > 0L || event.completedBytes > 0L) {
                                event.completedBytes
                            } else {
                                previous?.completedBytes ?: 0L
                            },
                        totalBytes =
                            if (event.totalBytes > 0L) {
                                event.totalBytes
                            } else {
                                previous?.totalBytes ?: -1L
                            },
                        bytesPerSecond = speed,
                        cancellable = !finished,
                    )
                this@InstallerService.publish(next)
                percentage = maxOf(percentage, next.percentage)
                lastPublishedAt = now
            }
            if (
                rawStageChanged ||
                finished ||
                now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS
            ) {
                lastNotificationAt = now
                updateNotification(event.detail, percentage)
            }
        }

        fun complete(reused: Boolean) {
            percentage = 100
            val previous =
                app.installState.current()
                    ?.takeIf { it.operationId == work.operationId }
            this@InstallerService.publish(
                InstallProgress(
                    work = work,
                    stage = InstallStage.COMPLETE,
                    stageProgress = 1f,
                    currentDetail =
                        if (reused) {
                            "Reused healthy ${work.installationName}"
                        } else {
                            "Installed at ${work.installationName}"
                        },
                    terminalLines =
                        previous?.terminalLines.orEmpty() +
                            if (reused) {
                                "[complete] reused healthy ${work.installationName}"
                            } else {
                                "[complete] health check passed · activated ${work.installationName}"
                            },
                    previewOnly = false,
                    completedBytes = previous?.completedBytes ?: 0L,
                    totalBytes = previous?.totalBytes ?: -1L,
                    cancellable = false,
                ),
            )
            updateNotification(
                if (reused) {
                    "Reused healthy ${work.displayName}"
                } else {
                    "${work.displayName} is ready"
                },
                percentage,
            )
        }

        fun fail(
            error: Throwable,
            interrupted: Boolean,
        ) {
            val previous =
                app.installState.current()
                    ?.takeIf { it.operationId == work.operationId }
            if (interrupted && previous?.stage == InstallStage.PAUSED) return
            this@InstallerService.publish(
                InstallProgress(
                    work = work,
                    stage = if (interrupted) InstallStage.PAUSED else InstallStage.FAILED,
                    stageProgress = previous?.overallProgress ?: 0f,
                    currentDetail =
                        if (interrupted) {
                            "Partial verified image data saved for resume"
                        } else {
                            error.message ?: error.javaClass.simpleName
                        },
                    terminalLines =
                        previous?.terminalLines.orEmpty() +
                            if (interrupted) {
                                "[paused] verified OCI blobs retained"
                            } else {
                                "[error] ${error.message ?: error.javaClass.simpleName}"
                            },
                    previewOnly = false,
                    completedBytes = previous?.completedBytes ?: 0L,
                    totalBytes = previous?.totalBytes ?: -1L,
                    cancellable = false,
                ),
            )
        }

        private fun OciInstallStage.terminalLabel(): String =
            when (this) {
                OciInstallStage.RESOLVING -> "resolve"
                OciInstallStage.DOWNLOADING -> "download"
                OciInstallStage.VERIFYING -> "verify"
                OciInstallStage.ASSEMBLING -> "extract"
                OciInstallStage.CONFIGURING -> "configure"
                OciInstallStage.HEALTH_CHECKING -> "probe"
                OciInstallStage.ACTIVATING -> "activate"
                OciInstallStage.READY -> "complete"
            }
    }

    private inner class ProgressPublisher(
        private val work: InstallerWorkRequest.Archive,
    ) {
        private val distro = work.distro
        private val operationId = work.operationId
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
                    work = work,
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
                    work = work,
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
                    work = work,
                    stage = InstallStage.EXTRACTING,
                    stageProgress = fraction.coerceIn(0f, 1f),
                    currentDetail =
                        "Unpacked ${formatBytes(completedBytes)} of ${formatBytes(totalBytes)}",
                    terminalLines = previous?.terminalLines.orEmpty() + listOfNotNull(line),
                    previewOnly = false,
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
                    work = work,
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
        private const val EXTRA_WORK_REQUEST = "work-request"

        fun start(
            context: Context,
            distro: DistroVariant,
        ) {
            val previous = InstallStateStore(context).current()
            val operationId =
                previous?.operationId
                    ?.takeIf { previous.archiveDistro?.id == distro.id }
                    ?: UUID.randomUUID().toString()
            start(
                context,
                InstallerWorkRequest.Archive(
                    distro = distro,
                    operationId = operationId,
                ),
            )
        }

        internal fun startOci(
            context: Context,
            reference: OciImageReference,
            platform: OciPlatform,
            installationName: String,
            displayName: String,
            architecture: String,
        ) {
            start(
                context,
                InstallerWorkRequest.Oci(
                    reference = reference,
                    platform = platform,
                    installationName = installationName,
                    displayName = displayName,
                    architecture = architecture,
                    operationId = UUID.randomUUID().toString(),
                ),
            )
        }

        internal fun start(
            context: Context,
            work: InstallerWorkRequest,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, InstallerService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(
                        EXTRA_WORK_REQUEST,
                        InstallerWorkRequestCodec.encode(work),
                    ),
            )
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, InstallerService::class.java).setAction(ACTION_PAUSE),
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

package org.randomcoder.udroid.update

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import org.randomcoder.udroid.install.ArtifactRequest
import org.randomcoder.udroid.install.ByteProgress
import org.randomcoder.udroid.install.ResumableArtifactPipeline
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

class AppUpdateDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val activeTask = AtomicReference<Future<*>?>(null)
    private val store by lazy { AppUpdateStateStore(this) }

    override fun onCreate() {
        super.onCreate()
        AppUpdateNotifier.createChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelDownload()
            ACTION_START -> startDownload()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeTask.getAndSet(null)?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startDownload() {
        if (activeTask.get()?.isDone == false) return
        val release = store.current().release
        if (release == null) {
            stopSelf()
            return
        }
        startForeground(
            AppUpdateNotifier.DOWNLOAD_NOTIFICATION,
            downloadNotification(release, "Preparing verified update", null),
        )
        val task = executor.submit { download(release) }
        if (!activeTask.compareAndSet(null, task)) {
            task.cancel(true)
        }
    }

    private fun download(release: AppRelease) {
        try {
            val expectedSha256 = fetchExpectedSha256(release)
            val updateDirectory = File(filesDir, "updates").apply {
                check(mkdirs() || isDirectory) { "Could not create update storage" }
            }
            val finalFile = File(updateDirectory, "udroid-${release.version}.apk")
            val partialFile = File(updateDirectory, "udroid-${release.version}.apk.part")
            val initial =
                store.current().copy(
                    phase = AppUpdatePhase.DOWNLOADING,
                    release = release,
                    completedBytes = partialFile.length(),
                    totalBytes = release.apkSize,
                    downloadedApkPath = null,
                    message = "Downloading uDroid ${release.version}",
                )
            store.save(initial)
            broadcastState(this)
            val progress = DownloadProgress(release)
            val result =
                ResumableArtifactPipeline()
                    .execute(
                        request =
                            ArtifactRequest(
                                url = release.apkUrl,
                                expectedSha256 = expectedSha256,
                                stagingFile = partialFile,
                                finalFile = finalFile,
                            ),
                        onDownloadProgress = progress::publish,
                    )
            store.save(
                store.current().copy(
                    phase = AppUpdatePhase.READY,
                    completedBytes = result.byteCount,
                    totalBytes = result.byteCount,
                    downloadedApkPath = result.file.absolutePath,
                    message = "Verified update ready to install",
                ),
            )
            broadcastState(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            AppUpdateNotifier.post(
                this,
                AppUpdateNotifier.DOWNLOAD_NOTIFICATION,
                AppUpdateNotifier.builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("uDroid ${release.version} is ready")
                    .setContentText("Tap to review and install")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (error: Throwable) {
            val interrupted =
                error is InterruptedException ||
                    error is InterruptedIOException ||
                    Thread.currentThread().isInterrupted
            store.save(
                store.current().copy(
                    phase = AppUpdatePhase.AVAILABLE,
                    message =
                        if (interrupted) {
                            "Update download paused; partial data is saved"
                        } else {
                            error.message ?: "Update download failed"
                        },
                ),
            )
            broadcastState(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
        } finally {
            activeTask.set(null)
            stopSelf()
        }
    }

    private fun cancelDownload() {
        activeTask.getAndSet(null)?.cancel(true)
        store.update {
            it.copy(
                phase = AppUpdatePhase.AVAILABLE,
                message = "Update download paused; partial data is saved",
            )
        }
        broadcastState(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fetchExpectedSha256(release: AppRelease): String {
        check(isRepositoryReleaseUrl(release.checksumsUrl, release.tag)) {
            "Untrusted checksum URL"
        }
        val text = downloadSmallText(release.checksumsUrl)
        val checksum = Sha256Sums.digestFor(text, release.apkName)
            ?: throw IOException("SHA256SUMS does not contain ${release.apkName}")
        val apiDigest =
            release.apkSha256
                ?: throw IOException("GitHub did not publish an APK digest")
        if (!checksum.equals(apiDigest, ignoreCase = true)) {
            throw IOException("GitHub digest and SHA256SUMS disagree")
        }
        return checksum
    }

    private fun downloadSmallText(initialUrl: String): String {
        var current = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirects ->
            val connection =
                (current.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "uDroid-Android-Updater")
                }
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK ->
                        return connection.inputStream.bufferedReader().use { reader ->
                            if (connection.contentLengthLong > MAX_CHECKSUM_BYTES) {
                                throw IOException("SHA256SUMS is unexpectedly large")
                            }
                            val output = StringBuilder()
                            val buffer = CharArray(2 * 1024)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                if (output.length + count > MAX_CHECKSUM_BYTES) {
                                    throw IOException("SHA256SUMS exceeded the size limit")
                                }
                                output.append(buffer, 0, count)
                            }
                            output.toString()
                        }
                    in REDIRECT_CODES -> {
                        if (redirects == MAX_REDIRECTS) {
                            throw IOException("Checksum download exceeded redirect limit")
                        }
                        val location =
                            connection.getHeaderField("Location")
                                ?: throw IOException("Checksum redirect omitted Location")
                        val next = URL(current, location)
                        if (next.protocol != "https") {
                            throw IOException("Checksum redirect attempted to leave HTTPS")
                        }
                        current = next
                    }
                    else ->
                        throw IOException(
                            "Checksum download returned HTTP ${connection.responseCode}",
                        )
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Checksum redirect resolution failed")
    }

    private fun isRepositoryReleaseUrl(
        value: String,
        tag: String,
    ): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.path == "/RandomCoderOrg/udroid-app/releases/download/$tag/SHA256SUMS"
    }

    private fun downloadNotification(
        release: AppRelease,
        detail: String,
        progress: Int?,
    ): Notification {
        val cancelIntent =
            PendingIntent.getService(
                this,
                4102,
                Intent(this, AppUpdateDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return AppUpdateNotifier.builder(this)
            .setContentTitle("uDroid ${release.version}")
            .setContentText(detail)
            .setOngoing(true)
            .apply {
                if (progress == null) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(100, progress, false)
                }
            }
            .addAction(0, "Pause", cancelIntent)
            .build()
    }

    private inner class DownloadProgress(private val release: AppRelease) {
        private var lastPublishedAt = 0L

        fun publish(progress: ByteProgress) {
            val now = System.currentTimeMillis()
            val finished =
                progress.totalBytes > 0L &&
                    progress.completedBytes >= progress.totalBytes
            if (!finished && now - lastPublishedAt < 500L) return
            lastPublishedAt = now
            val total =
                progress.totalBytes.takeIf { it > 0L }
                    ?: release.apkSize
            val state =
                store.save(
                    store.current().copy(
                        phase = AppUpdatePhase.DOWNLOADING,
                        completedBytes = progress.completedBytes,
                        totalBytes = total,
                        message =
                            if (progress.resumed) {
                                "Resuming verified update download"
                            } else {
                                "Downloading verified update"
                            },
                    ),
                )
            broadcastState(this@AppUpdateDownloadService)
            AppUpdateNotifier.post(
                this@AppUpdateDownloadService,
                AppUpdateNotifier.DOWNLOAD_NOTIFICATION,
                downloadNotification(
                    release,
                    state.message.orEmpty(),
                    state.percentage.takeIf { total > 0L },
                ),
            )
        }
    }

    companion object {
        private const val ACTION_START = "org.randomcoder.udroid.action.START_APP_UPDATE"
        private const val ACTION_CANCEL = "org.randomcoder.udroid.action.CANCEL_APP_UPDATE"
        private const val MAX_REDIRECTS = 5
        private const val MAX_CHECKSUM_BYTES = 64 * 1024
        private val REDIRECT_CODES =
            setOf(
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308,
            )

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AppUpdateDownloadService::class.java).setAction(ACTION_START),
            )
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, AppUpdateDownloadService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}

internal object Sha256Sums {
    private val linePattern = Regex("""([a-fA-F0-9]{64})\s+\*?(.+)""")

    fun digestFor(
        manifest: String,
        fileName: String,
    ): String? =
        manifest.lineSequence()
            .mapNotNull { line -> linePattern.matchEntire(line.trim()) }
            .firstOrNull { match -> match.groupValues[2] == fileName }
            ?.groupValues
            ?.get(1)
            ?.lowercase()
}

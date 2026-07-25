package org.randomcoder.udroid.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.randomcoder.udroid.BuildConfig
import org.randomcoder.udroid.MainActivity
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

object AppUpdateContract {
    const val ACTION_STATE_CHANGED = "org.randomcoder.udroid.action.APP_UPDATE_STATE_CHANGED"
    const val ACTION_SHOW_UPDATE = "org.randomcoder.udroid.action.SHOW_APP_UPDATE"
}

private const val UPDATE_LOG_TAG = "AppUpdate"

private enum class UpdateCheckResult {
    COMPLETE,
    RETRY,
}

private class AppUpdateCheck(
    private val context: Context,
) {
    fun run(manual: Boolean): UpdateCheckResult {
        val store = AppUpdateStateStore(context)
        val previous = store.current()
        if (manual) {
            store.save(
                previous.copy(
                    phase = AppUpdatePhase.CHECKING,
                    message = "Checking GitHub releases…",
                ),
            )
            broadcastState(context)
        }
        return try {
            when (
                val result =
                    GitHubReleaseClient(BuildConfig.UPDATE_RELEASES_API)
                        .check(BuildConfig.VERSION_NAME, previous.etag)
            ) {
                is GitHubReleaseCheck.Available -> {
                    Log.i(UPDATE_LOG_TAG, "release_available tag=${result.release.tag}")
                    val current = store.current()
                    val activeReleaseState =
                        sequenceOf(current, previous)
                            .firstOrNull { state ->
                                state.release?.tag == result.release.tag &&
                                    state.phase in
                                    setOf(AppUpdatePhase.DOWNLOADING, AppUpdatePhase.READY)
                            }
                    val sameRelease = current.release?.tag == result.release.tag
                    val next =
                        if (activeReleaseState != null) {
                            activeReleaseState.copy(
                                checkedAtMillis = System.currentTimeMillis(),
                                etag = result.etag ?: activeReleaseState.etag,
                            )
                        } else {
                            current.downloadedApkPath?.let { path ->
                                if (!sameRelease) java.io.File(path).delete()
                            }
                            AppUpdateState(
                                phase = AppUpdatePhase.AVAILABLE,
                                release = result.release,
                                checkedAtMillis = System.currentTimeMillis(),
                                totalBytes = result.release.apkSize,
                                message = "uDroid ${result.release.version} is available",
                                etag = result.etag,
                                notifiedTag = current.notifiedTag,
                            )
                        }
                    val shouldNotify = next.notifiedTag != result.release.tag
                    val notified =
                        shouldNotify &&
                            AppUpdateNotifier.notifyAvailable(context, next)
                    store.save(
                        if (notified) {
                            next.copy(notifiedTag = result.release.tag)
                        } else {
                            next
                        },
                    )
                    broadcastState(context)
                    UpdateCheckResult.COMPLETE
                }
                is GitHubReleaseCheck.UpToDate -> {
                    Log.i(
                        UPDATE_LOG_TAG,
                        "up_to_date current=${BuildConfig.VERSION_NAME}",
                    )
                    store.save(
                        AppUpdateState(
                            phase = AppUpdatePhase.UP_TO_DATE,
                            checkedAtMillis = System.currentTimeMillis(),
                            message = "uDroid ${BuildConfig.VERSION_NAME} is current",
                            etag = result.etag,
                            notifiedTag = previous.notifiedTag,
                        ),
                    )
                    broadcastState(context)
                    UpdateCheckResult.COMPLETE
                }
                GitHubReleaseCheck.NotModified -> {
                    Log.i(UPDATE_LOG_TAG, "release_metadata_not_modified")
                    val unchanged =
                        when {
                            previous.release != null &&
                                previous.phase == AppUpdatePhase.FAILED ->
                                previous.copy(
                                    phase = AppUpdatePhase.AVAILABLE,
                                    message =
                                        "uDroid ${previous.release.version} is available",
                                )
                            previous.release != null -> previous
                            else ->
                                previous.copy(
                                    phase = AppUpdatePhase.UP_TO_DATE,
                                    message = "uDroid ${BuildConfig.VERSION_NAME} is current",
                                )
                        }
                    val release = unchanged.release
                    val notified =
                        release != null &&
                            unchanged.notifiedTag != release.tag &&
                            AppUpdateNotifier.notifyAvailable(context, unchanged)
                    store.save(
                        unchanged.copy(
                            checkedAtMillis = System.currentTimeMillis(),
                            notifiedTag =
                                if (notified) release?.tag else unchanged.notifiedTag,
                        ),
                    )
                    broadcastState(context)
                    UpdateCheckResult.COMPLETE
                }
            }
        } catch (error: IOException) {
            Log.w(UPDATE_LOG_TAG, "Release check will retry", error)
            if (manual) {
                store.save(
                    previous.copy(
                        phase =
                            previous.phase.takeIf {
                                it in setOf(AppUpdatePhase.DOWNLOADING, AppUpdatePhase.READY)
                            } ?: AppUpdatePhase.FAILED,
                        message = error.message ?: "Could not check for updates",
                    ),
                )
                broadcastState(context)
            }
            UpdateCheckResult.RETRY
        } catch (error: Throwable) {
            Log.e(UPDATE_LOG_TAG, "Release check failed", error)
            store.save(
                previous.copy(
                    phase =
                        previous.phase.takeIf {
                            it in setOf(AppUpdatePhase.DOWNLOADING, AppUpdatePhase.READY)
                        } ?: AppUpdatePhase.FAILED,
                    message = error.message ?: "Could not check for updates",
                ),
            )
            broadcastState(context)
            UpdateCheckResult.COMPLETE
        }
    }
}

class AppUpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val manual = params.extras.getBoolean(EXTRA_MANUAL, false)
        lateinit var task: FutureTask<Unit>
        task =
            FutureTask {
                val retry =
                    try {
                        AppUpdateCheck(applicationContext).run(manual) ==
                            UpdateCheckResult.RETRY
                    } catch (_: Throwable) {
                        true
                    }
                if (!Thread.currentThread().isInterrupted) {
                    jobFinished(params, retry)
                }
                TASKS.remove(params.jobId, task)
            }
        TASKS[params.jobId] = task
        EXECUTOR.execute(task)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        TASKS.remove(params.jobId)?.cancel(true)
        return true
    }

    companion object {
        internal const val EXTRA_MANUAL = "manual"
        private val EXECUTOR = Executors.newSingleThreadExecutor()
        private val TASKS = java.util.concurrent.ConcurrentHashMap<Int, FutureTask<Unit>>()
    }
}

object AppUpdateScheduler {
    private const val PERIODIC_JOB_ID = 4_100
    private const val STARTUP_JOB_ID = 4_101
    private const val MANUAL_JOB_ID = 4_102
    private const val CHECK_INTERVAL_HOURS = 12L
    private const val CHECK_FLEX_HOURS = 1L
    private const val STARTUP_STALE_HOURS = 6L
    private const val RETRY_MINUTES = 30L

    fun ensureScheduled(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(PERIODIC_JOB_ID) == null) {
                scheduler.schedule(
                    baseJob(context, PERIODIC_JOB_ID)
                        .setRequiresBatteryNotLow(true)
                        .setPersisted(true)
                        .setPeriodic(
                            TimeUnit.HOURS.toMillis(CHECK_INTERVAL_HOURS),
                            TimeUnit.HOURS.toMillis(CHECK_FLEX_HOURS),
                        )
                        .build(),
                )
            }

            val checkedAt = AppUpdateStateStore(context).current().checkedAtMillis
            val staleBefore =
                System.currentTimeMillis() - TimeUnit.HOURS.toMillis(STARTUP_STALE_HOURS)
            if (
                checkedAt <= staleBefore &&
                scheduler.getPendingJob(STARTUP_JOB_ID) == null
            ) {
                scheduler.schedule(baseJob(context, STARTUP_JOB_ID).build())
            }
        }.onFailure { error ->
            Log.w(UPDATE_LOG_TAG, "Could not register app update jobs", error)
        }
    }

    fun checkNow(context: Context) {
        val store = AppUpdateStateStore(context)
        val previous = store.current()
        store.save(
            previous.copy(
                phase = AppUpdatePhase.CHECKING,
                message = "Waiting for a network connection…",
            ),
        )
        broadcastState(context)
        val scheduled =
            runCatching {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                scheduler.cancel(MANUAL_JOB_ID)
                scheduler.schedule(
                    baseJob(context, MANUAL_JOB_ID)
                        .setExtras(
                            PersistableBundle().apply {
                                putBoolean(AppUpdateJobService.EXTRA_MANUAL, true)
                            },
                        )
                        .build(),
                )
            }.getOrDefault(JobScheduler.RESULT_FAILURE)
        if (scheduled == JobScheduler.RESULT_FAILURE) {
            store.save(
                previous.copy(
                    phase = AppUpdatePhase.FAILED,
                    message = "Android could not schedule the update check",
                ),
            )
            broadcastState(context)
        }
    }

    private fun baseJob(
        context: Context,
        jobId: Int,
    ): JobInfo.Builder =
        JobInfo.Builder(
            jobId,
            ComponentName(context, AppUpdateJobService::class.java),
        ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(
                TimeUnit.MINUTES.toMillis(RETRY_MINUTES),
                JobInfo.BACKOFF_POLICY_EXPONENTIAL,
            )
}

object AppUpdateNotifier {
    private const val CHANNEL = "udroid-app-updates"
    private const val AVAILABLE_NOTIFICATION = 4101
    const val DOWNLOAD_NOTIFICATION = 4102

    fun notifyAvailable(
        context: Context,
        state: AppUpdateState,
    ): Boolean {
        val release = state.release ?: return false
        createChannel(context)
        return post(
            context,
            AVAILABLE_NOTIFICATION,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("uDroid ${release.version} is available")
                .setContentText("Review and install the verified GitHub release")
                .setContentIntent(openUpdateIntent(context))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "uDroid updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "New uDroid releases and update download progress"
                },
            )
    }

    fun builder(context: Context): NotificationCompat.Builder {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openUpdateIntent(context))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    @SuppressLint("MissingPermission")
    fun post(
        context: Context,
        id: Int,
        notification: Notification,
    ): Boolean {
        if (!canNotify(context)) return false
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun openUpdateIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            4101,
            Intent(context, MainActivity::class.java)
                .setAction(AppUpdateContract.ACTION_SHOW_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
            )
}

internal fun broadcastState(context: Context) {
    context.sendBroadcast(
        Intent(AppUpdateContract.ACTION_STATE_CHANGED).setPackage(context.packageName),
    )
}

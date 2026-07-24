package org.randomcoder.udroid.runtime

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
import org.json.JSONObject
import org.randomcoder.udroid.MainActivity
import org.randomcoder.udroid.R
import org.randomcoder.udroid.UdroidApplication
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class RuntimeSupervisorService : Service() {
    private val workerPool = Executors.newCachedThreadPool()
    private val ownedProcess = AtomicReference<Process?>(null)

    private val app: UdroidApplication
        get() = application as UdroidApplication

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        app.journal.append(
            component = "supervisor",
            severity = "info",
            event = "service_created",
            message = "Runtime supervisor service created",
            bootId = app.runtimeState.current().bootId,
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val action = intent?.action
        val persisted = app.runtimeState.current()

        return when {
            action == ACTION_START_RUNTIME -> {
                startForeground(NOTIFICATION_ID, notification("Starting uDroid runtime…"))
                startRuntime()
                START_STICKY
            }

            action == ACTION_STOP_RUNTIME -> {
                stopRuntime(userRequested = true)
                START_NOT_STICKY
            }

            action == null && persisted.desiredRunning -> {
                startForeground(NOTIFICATION_ID, notification("Recovering uDroid runtime…"))
                app.journal.append(
                    component = "supervisor",
                    severity = "warning",
                    event = "sticky_restart",
                    message = "Android recreated the supervisor for a desired running state",
                    bootId = persisted.bootId,
                )
                startRuntime()
                START_STICKY
            }

            else -> {
                stopSelf(startId)
                START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val snapshot = app.runtimeState.current()
        app.journal.append(
            component = "supervisor",
            severity = if (snapshot.desiredRunning) "warning" else "info",
            event = "service_destroyed",
            message = "Runtime supervisor service destroyed",
            bootId = snapshot.bootId,
            fields = mapOf("desired_running" to snapshot.desiredRunning),
        )
        workerPool.shutdownNow()
        super.onDestroy()
    }

    private fun startRuntime() {
        val existing = ownedProcess.get()
        if (existing?.isAlive == true) {
            publishState(
                app.runtimeState.update {
                    it.copy(
                        phase = RuntimePhase.RUNNING,
                        desiredRunning = true,
                        message = "Development runtime probe is already running",
                        childPid = it.childPid,
                    )
                },
            )
            return
        }

        val bootId = UUID.randomUUID().toString()
        publishState(
            app.runtimeState.update {
                RuntimeSnapshot(
                    phase = RuntimePhase.STARTING,
                    desiredRunning = true,
                    bootId = bootId,
                    message = "Preparing packaged runtime probe",
                )
            },
        )
        app.journal.append(
            component = "supervisor",
            severity = "info",
            event = "runtime_start_requested",
            message = "Starting a new supervised runtime generation",
            bootId = bootId,
        )

        workerPool.execute {
            runCatching {
                val executable = NativeProbeInstaller.install(this)
                ProcessBuilder(AndroidExecutableCommand.create(executable, bootId))
                    .redirectErrorStream(true)
                    .start()
            }.onSuccess { process ->
                if (!ownedProcess.compareAndSet(null, process)) {
                    process.destroy()
                    app.journal.append(
                        component = "supervisor",
                        severity = "warning",
                        event = "duplicate_process_rejected",
                        message = "A second runtime process lost the ownership race",
                        bootId = bootId,
                    )
                    return@onSuccess
                }

                val running =
                    app.runtimeState.update {
                        it.copy(
                            phase = RuntimePhase.RUNNING,
                            desiredRunning = true,
                            message = "Supervised uDroid process started",
                            childPid = null,
                        )
                    }
                publishState(running)
                updateNotification("uDroid runtime probe is running")
                app.journal.append(
                    component = "runtime-probe",
                    severity = "info",
                    event = "process_started",
                    message = "Packaged native child process started",
                    bootId = bootId,
                )
                monitorProcess(process, bootId)
            }.onFailure { error ->
                val failed =
                    app.runtimeState.update {
                        it.copy(
                            phase = RuntimePhase.CRASHED,
                            desiredRunning = false,
                            message = error.message ?: error.javaClass.simpleName,
                            childPid = null,
                        )
                    }
                publishState(failed)
                app.journal.append(
                    component = "supervisor",
                    severity = "error",
                    event = "runtime_start_failed",
                    message = failed.message,
                    bootId = bootId,
                    fields = mapOf("exception" to error.javaClass.name),
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun monitorProcess(
        process: Process,
        bootId: String,
    ) {
        var lastSequence: Long? = null
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val payload = runCatching { JSONObject(line) }.getOrNull()
                    val event = payload?.optString("event").orEmpty().ifBlank { "child_output" }
                    if (event == "probe_started") {
                        val childPid = payload?.optLong("pid")?.takeIf { it > 0 }
                        val identified =
                            app.runtimeState.update {
                                it.copy(
                                    message =
                                        childPid?.let { pid ->
                                            "Supervised uDroid process is healthy · PID $pid"
                                        } ?: "Supervised uDroid process is healthy",
                                    childPid = childPid,
                                )
                            }
                        publishState(identified)
                        updateNotification(identified.message)
                    } else if (event == "heartbeat") {
                        lastSequence = payload?.optLong("sequence")
                        if (
                            lastSequence != null &&
                            lastSequence!! % HEARTBEAT_UI_INTERVAL == 0L
                        ) {
                            publishState(
                                app.runtimeState.update {
                                    it.copy(
                                        message = "Runtime heartbeat $lastSequence",
                                        heartbeatSequence = lastSequence,
                                    )
                                },
                            )
                        }
                    }
                    app.journal.append(
                        component = "runtime-probe",
                        severity = "debug",
                        event = event,
                        message = line,
                        bootId = bootId,
                        fields = mapOf("sequence" to lastSequence),
                    )
                }
            }
        } catch (error: IOException) {
            app.journal.append(
                component = "runtime-probe",
                severity = if (app.runtimeState.current().desiredRunning) "warning" else "debug",
                event = "output_stream_closed",
                message = error.message ?: "Runtime output stream closed",
                bootId = bootId,
            )
        }

        val exitCode =
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                if (process.isAlive) process.destroyForcibly()
                val interruptedExitCode =
                    runCatching { process.exitValue() }.getOrDefault(-1)
                Thread.currentThread().interrupt()
                interruptedExitCode
            }
        val wasOwner = ownedProcess.compareAndSet(process, null)
        if (!wasOwner) return
        val beforeExit = app.runtimeState.current()
        val expected = !beforeExit.desiredRunning || beforeExit.phase == RuntimePhase.STOPPING
        val next =
            app.runtimeState.update { RuntimeStateMachine.afterProcessExit(it, exitCode) }
        publishState(next)
        app.journal.append(
            component = "runtime-probe",
            severity = if (expected) "info" else "error",
            event = if (expected) "process_stopped" else "process_crashed",
            message = next.message,
            bootId = bootId,
            fields =
                mapOf(
                    "exit_code" to exitCode,
                    "last_heartbeat" to lastSequence,
                ),
        )
        updateNotification(next.message)

        if (expected) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRuntime(userRequested: Boolean) {
        val current = app.runtimeState.current()
        val stopping =
            app.runtimeState.update {
                it.copy(
                    phase = RuntimePhase.STOPPING,
                    desiredRunning = false,
                    message =
                        if (userRequested) {
                            "Stopping the supervised runtime"
                        } else {
                            "Supervisor is shutting down"
                        },
                )
            }
        publishState(stopping)
        app.journal.append(
            component = "supervisor",
            severity = "info",
            event = "runtime_stop_requested",
            message = stopping.message,
            bootId = current.bootId,
            fields = mapOf("user_requested" to userRequested),
        )

        val process = ownedProcess.get()
        if (process == null || !process.isAlive) {
            ownedProcess.compareAndSet(process, null)
            val stopped =
                app.runtimeState.update {
                    it.copy(
                        phase = RuntimePhase.STOPPED,
                        desiredRunning = false,
                        message = "uDroid runtime is stopped",
                        childPid = null,
                        heartbeatSequence = null,
                    )
                }
            publishState(stopped)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        process.destroy()
        workerPool.execute {
            try {
                Thread.sleep(GRACEFUL_STOP_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            }
            if (process.isAlive && ownedProcess.get() === process) {
                app.journal.append(
                    component = "supervisor",
                    severity = "warning",
                    event = "runtime_force_stop",
                    message = "Runtime ignored SIGTERM; forcing the owned process to stop",
                    bootId = current.bootId,
                    fields = mapOf("child_pid" to current.childPid),
                )
                process.destroyForcibly()
            }
        }
    }

    private fun publishState(snapshot: RuntimeSnapshot) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_PHASE, snapshot.phase.name)
                .putExtra(EXTRA_UPDATED_AT, snapshot.updatedAtEpochMs),
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "uDroid runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Runtime lifecycle and recovery status"
            },
        )
    }

    private fun notification(text: String): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RuntimeSupervisorService::class.java)
                    .setAction(ACTION_STOP_RUNTIME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("uDroid runtime")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        if (
            android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(text))
        }
    }

    companion object {
        const val ACTION_STATE_CHANGED = "org.randomcoder.udroid.action.STATE_CHANGED"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_UPDATED_AT = "updated-at"

        private const val ACTION_START_RUNTIME =
            "org.randomcoder.udroid.action.START_RUNTIME"
        private const val ACTION_STOP_RUNTIME =
            "org.randomcoder.udroid.action.STOP_RUNTIME"
        private const val NOTIFICATION_CHANNEL = "runtime-supervisor"
        private const val NOTIFICATION_ID = 1001
        private const val GRACEFUL_STOP_TIMEOUT_MS = 3_000L
        private const val HEARTBEAT_UI_INTERVAL = 2L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RuntimeSupervisorService::class.java)
                    .setAction(ACTION_START_RUNTIME),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RuntimeSupervisorService::class.java)
                    .setAction(ACTION_STOP_RUNTIME),
            )
        }
    }
}

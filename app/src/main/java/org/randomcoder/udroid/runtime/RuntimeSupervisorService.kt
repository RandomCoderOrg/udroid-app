package org.randomcoder.udroid.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import org.randomcoder.udroid.MainActivity
import org.randomcoder.udroid.UdroidApplication
import org.randomcoder.udroid.install.ProotRuntimeInstaller
import org.randomcoder.udroid.linuxapps.LinuxApplication
import org.randomcoder.udroid.x11.X11ServerController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class RuntimeSupervisorService : Service() {
    private val binder = RuntimeBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ownedSession = AtomicReference<TerminalSession?>(null)
    private val attachedViews = CopyOnWriteArraySet<TerminalView>()
    private val applicationProcesses = ConcurrentHashMap<String, Process>()
    private val applicationExecutor = Executors.newCachedThreadPool()
    private val x11Controller by lazy { X11ServerController(this, app.journal) }

    private val app: UdroidApplication
        get() = application as UdroidApplication

    private val terminalClient =
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                attachedViews
                    .filter { it.mTermSession === changedSession }
                    .forEach(TerminalView::onScreenUpdated)
            }

            override fun onTitleChanged(changedSession: TerminalSession) {
                onTextChanged(changedSession)
            }

            override fun onSessionFinished(finishedSession: TerminalSession) {
                handleSessionFinished(finishedSession)
            }

            override fun onCopyTextToClipboard(
                session: TerminalSession,
                text: String,
            ) {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("uDroid terminal", text))
            }

            override fun onPasteTextFromClipboard(session: TerminalSession) {
                val clipboard = getSystemService(ClipboardManager::class.java)
                val text =
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.coerceToText(this@RuntimeSupervisorService)
                        ?.toString()
                        .orEmpty()
                if (text.isNotEmpty()) session.write(text)
            }

            override fun onBell(session: TerminalSession) = Unit

            override fun onColorsChanged(session: TerminalSession) {
                onTextChanged(session)
            }

            override fun onTerminalCursorStateChange(state: Boolean) {
                attachedViews.forEach(TerminalView::invalidate)
            }

            override fun getTerminalCursorStyle(): Int? = null

            override fun logError(
                tag: String,
                message: String,
            ) = logTerminal(Log.ERROR, tag, message)

            override fun logWarn(
                tag: String,
                message: String,
            ) = logTerminal(Log.WARN, tag, message)

            override fun logInfo(
                tag: String,
                message: String,
            ) = logTerminal(Log.INFO, tag, message)

            override fun logDebug(
                tag: String,
                message: String,
            ) = logTerminal(Log.DEBUG, tag, message)

            override fun logVerbose(
                tag: String,
                message: String,
            ) = logTerminal(Log.VERBOSE, tag, message)

            override fun logStackTraceWithMessage(
                tag: String,
                message: String,
                error: Exception,
            ) {
                Log.e(tag, message, error)
            }

            override fun logStackTrace(
                tag: String,
                error: Exception,
            ) {
                Log.e(tag, error.message, error)
            }
        }

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
                startForeground(NOTIFICATION_ID, notification("Starting Linux terminal…"))
                startRuntime()
                START_STICKY
            }

            action == ACTION_STOP_RUNTIME -> {
                stopRuntime(userRequested = true)
                START_NOT_STICKY
            }

            action == null && persisted.desiredRunning -> {
                startForeground(NOTIFICATION_ID, notification("Recovering Linux terminal…"))
                app.journal.append(
                    component = "supervisor",
                    severity = "warning",
                    event = "sticky_restart",
                    message = "Android recreated the desired Linux terminal session",
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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        attachedViews.clear()
        mainHandler.removeCallbacksAndMessages(null)
        stopApplicationProcesses()
        applicationExecutor.shutdownNow()
        val snapshot = app.runtimeState.current()
        app.journal.append(
            component = "supervisor",
            severity = if (snapshot.desiredRunning) "warning" else "info",
            event = "service_destroyed",
            message = "Runtime supervisor service destroyed",
            bootId = snapshot.bootId,
            fields = mapOf("desired_running" to snapshot.desiredRunning),
        )
        super.onDestroy()
    }

    fun currentTerminalSession(): TerminalSession? = ownedSession.get()

    fun attachTerminalView(view: TerminalView) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Terminal views must attach on the main thread"
        }
        attachedViews += view
        ownedSession.get()?.let { session ->
            if (view.mTermSession !== session) {
                view.attachSession(session)
            }
            view.onScreenUpdated()
        }
    }

    fun detachTerminalView(view: TerminalView) {
        attachedViews -= view
    }

    fun writeToTerminal(text: String) {
        ownedSession.get()?.takeIf(TerminalSession::isRunning)?.write(text)
    }

    fun requestX11RendererConnection(callback: (ParcelFileDescriptor?) -> Unit) {
        x11Controller.requestRendererConnection(callback)
    }

    fun launchLinuxApplication(
        application: LinuxApplication,
        callback: (Result<Unit>) -> Unit,
    ) {
        val snapshot = app.runtimeState.current()
        if (
            snapshot.phase != RuntimePhase.RUNNING ||
            ownedSession.get()?.isRunning != true
        ) {
            callback(Result.failure(IllegalStateException("Start Linux before launching an app")))
            return
        }
        if (application.terminal) {
            val command =
                (listOf(application.executable) + application.arguments)
                    .joinToString(" ", transform = ::shellQuote)
            writeToTerminal("$command\n")
            app.journal.append(
                component = "linux-app",
                severity = "info",
                event = "terminal_app_launched",
                message = "Opened ${application.name} in the supervised terminal",
                bootId = snapshot.bootId,
                fields =
                    mapOf(
                        "desktop_id" to application.id,
                        "executable" to application.executable,
                    ),
            )
            callback(Result.success(Unit))
            return
        }

        x11Controller.whenReady { socketDirectory ->
            if (socketDirectory == null) {
                callback(Result.failure(IllegalStateException("Embedded X11 failed to start")))
                return@whenReady
            }
            applicationExecutor.execute {
                val result =
                    runCatching {
                        val rootfs = InstalledRootfsResolver.resolve(this)
                        val launch =
                            ProotApplicationLaunchBuilder.create(
                                context = this,
                                runtime = ProotRuntimeInstaller.install(this),
                                rootfs = rootfs,
                                x11SocketDirectory = socketDirectory,
                                application = application,
                            )
                        val process =
                            ProcessBuilder(launch.command)
                                .directory(launch.workingDirectory)
                                .redirectErrorStream(true)
                                .apply {
                                    environment().clear()
                                    environment().putAll(launch.environment)
                                }.start()
                        applicationProcesses.put(application.id, process)?.destroy()
                        drainApplicationOutput(application, process)
                        app.journal.append(
                            component = "linux-app",
                            severity = "info",
                            event = "app_launched",
                            message = "Launched ${application.name} on display :0",
                            bootId = snapshot.bootId,
                            fields =
                                mapOf(
                                    "desktop_id" to application.id,
                                    "executable" to application.executable,
                                ),
                        )
                    }
                mainHandler.post { callback(result) }
            }
        }
    }

    private fun startRuntime() {
        val existing = ownedSession.get()
        if (existing?.isRunning == true && existing.pid > 0) {
            publishState(
                app.runtimeState.update {
                    it.copy(
                        phase = RuntimePhase.RUNNING,
                        desiredRunning = true,
                        message = "Linux terminal is already running · PID ${existing.pid}",
                        childPid = existing.pid.toLong(),
                    )
                },
            )
            return
        }
        if (existing != null) {
            ownedSession.compareAndSet(existing, null)
        }

        val bootId = UUID.randomUUID().toString()
        publishState(
            app.runtimeState.update {
                RuntimeSnapshot(
                    phase = RuntimePhase.STARTING,
                    desiredRunning = true,
                    bootId = bootId,
                    message = "Preparing the installed Linux terminal",
                )
            },
        )
        app.journal.append(
            component = "supervisor",
            severity = "info",
            event = "terminal_start_requested",
            message = "Starting a supervised PRoot terminal generation",
            bootId = bootId,
        )

        runCatching {
            val runtime = ProotRuntimeInstaller.install(this)
            val rootfs = InstalledRootfsResolver.resolve(this)
            val x11SocketDirectory = x11Controller.start(rootfs, bootId)
            ProotTerminalLaunchBuilder.create(
                context = this,
                runtime = runtime,
                rootfs = rootfs,
                x11SocketDirectory = x11SocketDirectory,
            )
        }.mapCatching { launch ->
            configureTerminalColors()
            val session =
                TerminalSession(
                    launch.executable,
                    launch.workingDirectory,
                    launch.arguments,
                    launch.environment,
                    TRANSCRIPT_ROWS,
                    terminalClient,
                )
            session.mSessionName = launch.rootfs.name
            check(ownedSession.compareAndSet(null, session)) {
                "Another terminal session won the ownership race"
            }
            try {
                session.updateSize(
                    DEFAULT_COLUMNS,
                    DEFAULT_ROWS,
                    DEFAULT_CELL_WIDTH_PX,
                    DEFAULT_CELL_HEIGHT_PX,
                )
            } catch (error: Throwable) {
                ownedSession.compareAndSet(session, null)
                if (session.pid > 0) session.finishIfRunning()
                throw error
            }
            check(session.pid > 0) { "Termux did not return a terminal child PID" }
            launch to session
        }.onSuccess { (launch, session) ->
            attachedViews.forEach { view ->
                view.attachSession(session)
                view.onScreenUpdated()
            }
            val running =
                app.runtimeState.update {
                    it.copy(
                        phase = RuntimePhase.RUNNING,
                        desiredRunning = true,
                        message = "${launch.rootfs.name} terminal is running · PID ${session.pid}",
                        childPid = session.pid.toLong(),
                    )
                }
            publishState(running)
            updateNotification("Linux terminal is running")
            app.journal.append(
                component = "terminal",
                severity = "info",
                event = "session_started",
                message = "Interactive PRoot terminal started",
                bootId = bootId,
                fields =
                    mapOf(
                        "pid" to session.pid,
                        "rootfs" to launch.rootfs.name,
                        "terminal" to "termux-v0.118.3",
                    ),
            )
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
                event = "terminal_start_failed",
                message = failed.message,
                bootId = bootId,
                fields = mapOf("exception" to error.javaClass.name),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun configureTerminalColors() {
        TerminalColors.COLOR_SCHEME.updateWith(
            Properties().apply {
                setProperty("foreground", "#E3E7EF")
                setProperty("background", "#11131F")
                setProperty("cursor", "#43D292")
                setProperty("color2", "#43D292")
                setProperty("color3", "#E8B86D")
                setProperty("color4", "#65A7D8")
            },
        )
    }

    private fun handleSessionFinished(session: TerminalSession) {
        if (!ownedSession.compareAndSet(session, null)) return
        attachedViews.forEach(TerminalView::onScreenUpdated)
        val exitCode = session.exitStatus
        val beforeExit = app.runtimeState.current()
        val expected = !beforeExit.desiredRunning || beforeExit.phase == RuntimePhase.STOPPING
        val next =
            app.runtimeState.update { RuntimeStateMachine.afterProcessExit(it, exitCode) }
        stopApplicationProcesses()
        x11Controller.stop(beforeExit.bootId)
        publishState(next)
        app.journal.append(
            component = "terminal",
            severity = if (expected) "info" else "error",
            event = if (expected) "session_stopped" else "session_crashed",
            message = next.message,
            bootId = beforeExit.bootId,
            fields = mapOf("exit_code" to exitCode),
        )
        updateNotification(next.message)
        if (expected) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRuntime(userRequested: Boolean) {
        val current = app.runtimeState.current()
        stopApplicationProcesses()
        x11Controller.stop(current.bootId)
        val stopping =
            app.runtimeState.update {
                it.copy(
                    phase = RuntimePhase.STOPPING,
                    desiredRunning = false,
                    message =
                        if (userRequested) {
                            "Stopping the Linux terminal"
                        } else {
                            "Supervisor is shutting down"
                        },
                )
            }
        publishState(stopping)
        app.journal.append(
            component = "supervisor",
            severity = "info",
            event = "terminal_stop_requested",
            message = stopping.message,
            bootId = current.bootId,
            fields = mapOf("user_requested" to userRequested),
        )

        val session = ownedSession.get()
        if (session == null || !session.isRunning || session.pid < 1) {
            ownedSession.compareAndSet(session, null)
            publishStoppedState()
            return
        }

        try {
            Os.kill(session.pid, OsConstants.SIGTERM)
        } catch (error: ErrnoException) {
            app.journal.append(
                component = "supervisor",
                severity = "warning",
                event = "terminal_sigterm_failed",
                message = error.message ?: "Could not signal the terminal",
                bootId = current.bootId,
            )
        }
        mainHandler.postDelayed(
            {
                if (ownedSession.get() === session && session.isRunning) {
                    app.journal.append(
                        component = "supervisor",
                        severity = "warning",
                        event = "terminal_force_stop",
                        message = "Terminal ignored SIGTERM; forcing the owned session to stop",
                        bootId = current.bootId,
                        fields = mapOf("child_pid" to session.pid),
                    )
                    session.finishIfRunning()
                }
            },
            GRACEFUL_STOP_TIMEOUT_MS,
        )
    }

    private fun publishStoppedState() {
        val stopped =
            app.runtimeState.update {
                it.copy(
                    phase = RuntimePhase.STOPPED,
                    desiredRunning = false,
                    message = "uDroid terminal is stopped",
                    childPid = null,
                    heartbeatSequence = null,
                )
            }
        publishState(stopped)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishState(snapshot: RuntimeSnapshot) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_PHASE, snapshot.phase.name)
                .putExtra(EXTRA_UPDATED_AT, snapshot.updatedAtEpochMs),
        )
    }

    private fun logTerminal(
        priority: Int,
        tag: String,
        message: String,
    ) {
        Log.println(priority, tag, message)
    }

    private fun drainApplicationOutput(
        application: LinuxApplication,
        process: Process,
    ) {
        applicationExecutor.execute {
            var outputLines = 0
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    when {
                        outputLines < MAX_APP_OUTPUT_LINES ->
                            app.journal.append(
                                component = "linux-app",
                                severity = "debug",
                                event = "app_output",
                                message = line.take(MAX_APP_OUTPUT_CHARS),
                                bootId = app.runtimeState.current().bootId,
                                fields = mapOf("desktop_id" to application.id),
                            )
                        outputLines == MAX_APP_OUTPUT_LINES ->
                            app.journal.append(
                                component = "linux-app",
                                severity = "warning",
                                event = "app_output_suppressed",
                                message = "Further ${application.name} output is suppressed",
                                bootId = app.runtimeState.current().bootId,
                                fields = mapOf("desktop_id" to application.id),
                            )
                    }
                    outputLines++
                }
            }
        }
        applicationExecutor.execute {
            val exitCode = process.waitFor()
            applicationProcesses.remove(application.id, process)
            app.journal.append(
                component = "linux-app",
                severity = if (exitCode == 0) "info" else "warning",
                event = "app_exited",
                message = "${application.name} exited with $exitCode",
                bootId = app.runtimeState.current().bootId,
                fields =
                    mapOf(
                        "desktop_id" to application.id,
                        "exit_code" to exitCode,
                    ),
            )
        }
    }

    private fun stopApplicationProcesses() {
        applicationProcesses.values.forEach { process ->
            if (process.isAlive) process.destroy()
        }
        applicationProcesses.clear()
    }

    private fun shellQuote(argument: String): String =
        "'${argument.replace("'", "'\"'\"'")}'"

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "uDroid runtime",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Interactive Linux terminal lifecycle"
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
            .setContentTitle("uDroid terminal")
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

    inner class RuntimeBinder : Binder() {
        fun service(): RuntimeSupervisorService = this@RuntimeSupervisorService
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
        private const val TRANSCRIPT_ROWS = 8_000
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_CELL_WIDTH_PX = 10
        private const val DEFAULT_CELL_HEIGHT_PX = 20
        private const val MAX_APP_OUTPUT_CHARS = 2_000
        private const val MAX_APP_OUTPUT_LINES = 200

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

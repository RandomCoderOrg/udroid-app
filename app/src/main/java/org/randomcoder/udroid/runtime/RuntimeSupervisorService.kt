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
import android.content.pm.ServiceInfo
import android.os.Build
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import org.randomcoder.udroid.MainActivity
import org.randomcoder.udroid.UdroidApplication
import org.randomcoder.udroid.audio.AudioConfiguration
import org.randomcoder.udroid.audio.AudioConfigurationStore
import org.randomcoder.udroid.audio.AudioServerController
import org.randomcoder.udroid.audio.AudioSessionSnapshot
import org.randomcoder.udroid.install.ProotRuntimeInstaller
import org.randomcoder.udroid.linuxapps.LinuxApplication
import org.randomcoder.udroid.x11.X11ServerController
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class RuntimeSupervisorService : Service() {
    private val binder = RuntimeBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val terminalTabs = TerminalTabRegistry<TerminalSession>()
    private val closingTerminalIds = mutableSetOf<String>()
    private var nextTerminalNumber = 1
    private var terminalCreationInFlight = false
    private val ownedDesktop = AtomicReference<OwnedDesktopProcess?>(null)
    private val desktopLaunchToken = AtomicReference<String?>(null)
    private val pendingDesktopRestart = AtomicReference<DesktopLaunchRequest?>(null)
    private val attachedViews = CopyOnWriteArraySet<TerminalView>()
    private val applicationProcesses = ConcurrentHashMap<String, Process>()
    private val applicationExecutor = Executors.newCachedThreadPool()
    private val x11Controller by lazy { X11ServerController(this, app.journal) }
    private val audioConfigurationStore by lazy { AudioConfigurationStore(this) }
    private val audioController by lazy {
        AudioServerController(this, app.journal, applicationExecutor)
    }

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
                try {
                    val clip = getSystemService(ClipboardManager::class.java).primaryClip
                    if (clip == null || clip.itemCount == 0) return

                    val text =
                        clip
                            .getItemAt(0)
                            .coerceToText(this@RuntimeSupervisorService)
                            ?.toString()
                            .orEmpty()
                    if (text.isEmpty()) return

                    // Keep paste semantics in the terminal emulator. It removes
                    // unsafe control characters, normalizes line endings, and
                    // wraps input when applications such as nano enable
                    // bracketed-paste mode.
                    session.emulator.paste(text)
                } catch (error: RuntimeException) {
                    Log.e("uDroidTerminal", "Could not paste clipboard text", error)
                    app.journal.append(
                        component = "terminal",
                        severity = "error",
                        event = "clipboard_paste_failed",
                        message = "Android clipboard paste failed",
                        bootId = app.runtimeState.current().bootId,
                        fields =
                            mapOf(
                                "error_type" to error.javaClass.simpleName,
                            ),
                    )
                }
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
                val rootfsName = intent.getStringExtra(EXTRA_ROOTFS_NAME)
                val configuration = effectiveAudioConfiguration(rootfsName, allowMicrophone = true)
                startRuntimeForeground("Starting Linux terminal…", configuration)
                startRuntime(rootfsName, configuration)
                START_STICKY
            }

            action == ACTION_STOP_RUNTIME -> {
                stopRuntime(userRequested = true)
                START_NOT_STICKY
            }

            action == null && persisted.desiredRunning -> {
                val configuration =
                    effectiveAudioConfiguration(persisted.rootfsName, allowMicrophone = false)
                startRuntimeForeground("Recovering Linux terminal…", configuration)
                app.journal.append(
                    component = "supervisor",
                    severity = "warning",
                    event = "sticky_restart",
                    message = "Android recreated the desired Linux terminal session",
                    bootId = persisted.bootId,
                )
                startRuntime(persisted.rootfsName, configuration)
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
        terminalTabs.clear().forEach { it.value.finishIfRunning() }
        closingTerminalIds.clear()
        pendingDesktopRestart.set(null)
        desktopLaunchToken.set(null)
        ownedDesktop.getAndSet(null)?.let { terminateDesktopProcess(it, OsConstants.SIGKILL) }
        stopApplicationProcesses()
        audioController.stop(app.runtimeState.current().bootId)
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

    fun currentTerminalSession(): TerminalSession? = terminalTabs.active()?.value

    fun terminalTabSnapshots(): List<TerminalTabSnapshot> =
        terminalTabs.all().map { tab ->
            TerminalTabSnapshot(
                id = tab.id,
                title = tab.title,
                rootfsName = tab.rootfsName,
                pid = tab.value.pid.takeIf { it > 0 }?.toLong(),
                running = tab.value.isRunning,
                active = tab.id == terminalTabs.activeId,
            )
        }

    fun createTerminalTab(
        rootfsName: String,
        onComplete: (Result<String>) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Terminal tabs must be created from the main thread"
        }
        if (terminalCreationInFlight) {
            onComplete(Result.failure(IllegalStateException("A terminal is already opening")))
            return
        }
        val snapshot = app.runtimeState.current()
        if (snapshot.phase != RuntimePhase.RUNNING) {
            onComplete(Result.failure(IllegalStateException("Start Linux before opening another terminal")))
            return
        }
        terminalCreationInFlight = true
        val x11SocketDirectory = x11Controller.activeSocketDirectory()
        val audioEndpoint = audioController.endpoint()
        applicationExecutor.execute {
            val prepared =
                runCatching {
                    val rootfs =
                        app.rootfsRegistry
                            .all()
                            .firstOrNull { it.name == rootfsName }
                            ?.directory
                            ?: error("Linux system $rootfsName is not installed or is not ready")
                    ProotTerminalLaunchBuilder.create(
                        context = this,
                        runtime = ProotRuntimeInstaller.install(this),
                        rootfs = rootfs,
                        x11SocketDirectory = x11SocketDirectory,
                        audioEndpoint = audioEndpoint,
                    )
                }
            mainHandler.post {
                val result =
                    prepared.mapCatching { launch ->
                        val current = app.runtimeState.current()
                        check(current.phase == RuntimePhase.RUNNING) {
                            "Linux stopped while the terminal was opening"
                        }
                        val tab = createTerminalSession(launch)
                        attachActiveTerminalToViews()
                        publishTerminalTabState("${tab.title} is ready")
                        app.journal.append(
                            component = "terminal",
                            severity = "info",
                            event = "tab_created",
                            message = "Created ${tab.title}",
                            bootId = current.bootId,
                            fields =
                                mapOf(
                                    "tab_id" to tab.id,
                                    "pid" to tab.value.pid,
                                    "rootfs" to tab.rootfsName,
                                ),
                        )
                        tab.id
                    }.onFailure { error ->
                        app.journal.append(
                            component = "terminal",
                            severity = "error",
                            event = "tab_create_failed",
                            message = error.message ?: "Could not create terminal tab",
                            bootId = app.runtimeState.current().bootId,
                            fields = mapOf("exception" to error.javaClass.name),
                        )
                    }
                terminalCreationInFlight = false
                onComplete(result)
            }
        }
    }

    fun selectTerminalTab(id: String): Boolean {
        val tab = terminalTabs.select(id) ?: return false
        attachedViews.forEach { view ->
            if (view.mTermSession !== tab.value) view.attachSession(tab.value)
            view.onScreenUpdated()
        }
        publishTerminalTabState("${tab.title} selected")
        return true
    }

    fun renameTerminalTab(
        id: String,
        requestedTitle: String,
    ): Boolean {
        val title = requestedTitle.trim().take(MAX_TERMINAL_TITLE_CHARS)
        if (title.isBlank()) return false
        val tab = terminalTabs.rename(id, title) ?: return false
        publishTerminalTabState("Renamed terminal to ${tab.title}")
        app.journal.append(
            component = "terminal",
            severity = "info",
            event = "tab_renamed",
            message = "Renamed terminal tab",
            bootId = app.runtimeState.current().bootId,
            fields = mapOf("tab_id" to id, "title" to title),
        )
        return true
    }

    fun closeTerminalTab(id: String): Boolean {
        val tab = terminalTabs.get(id) ?: return false
        if (terminalTabs.size() == 1) {
            stopRuntime(userRequested = true)
            return true
        }
        closingTerminalIds += id
        terminateTerminalTab(tab)
        return true
    }

    fun currentDesktopSession(): DesktopSessionSnapshot = app.runtimeState.current().desktop

    fun currentAudioSession(): AudioSessionSnapshot = audioController.current()

    fun applyAudioConfiguration(
        rootfsName: String,
        configuration: AudioConfiguration,
        callback: (Result<AudioSessionSnapshot>) -> Unit,
    ) {
        val session = currentTerminalSession()
        if (session?.isRunning != true || session.mSessionName != rootfsName) {
            callback(
                Result.failure(
                    IllegalStateException("$rootfsName is not the running Linux system"),
                ),
            )
            return
        }
        val effective =
            if (
                configuration.microphoneEnabled &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                configuration.copy(microphoneEnabled = false)
            } else {
                configuration
            }
        startRuntimeForeground("Applying Linux audio settings…", effective)
        val bootId = app.runtimeState.current().bootId
        applicationExecutor.execute {
            runCatching { audioController.apply(effective, bootId) }
                .onSuccess {
                    mainHandler.post {
                        updateNotification(
                            if (it.microphoneEnabled) {
                                "Linux audio and microphone are connected"
                            } else if (it.outputEnabled) {
                                "Linux audio is connected"
                            } else {
                                "Linux terminal is running"
                            },
                        )
                        publishState(app.runtimeState.current())
                        callback(Result.success(it))
                    }
                }.onFailure { error ->
                    app.journal.append(
                        component = "audio",
                        severity = "error",
                        event = "configuration_failed",
                        message = error.message ?: "Could not apply Linux audio settings",
                        bootId = bootId,
                        fields = mapOf("exception" to error.javaClass.name),
                    )
                    mainHandler.post {
                        publishState(app.runtimeState.current())
                        callback(Result.failure(error))
                    }
                }
        }
    }

    fun attachTerminalView(view: TerminalView) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Terminal views must attach on the main thread"
        }
        attachedViews += view
        currentTerminalSession()?.let { session ->
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
        currentTerminalSession()?.takeIf(TerminalSession::isRunning)?.write(text)
    }

    fun requestX11RendererConnection(callback: (ParcelFileDescriptor?) -> Unit) {
        x11Controller.requestRendererConnection(callback)
    }

    fun startDesktop(
        rootfsName: String,
        environment: DesktopEnvironment,
        configuration: DesktopConfiguration,
    ) {
        pendingDesktopRestart.set(null)
        startDesktopInternal(
            DesktopLaunchRequest(rootfsName, environment, configuration),
        )
    }

    fun restartDesktop(
        rootfsName: String,
        environment: DesktopEnvironment,
        configuration: DesktopConfiguration,
    ) {
        val request = DesktopLaunchRequest(rootfsName, environment, configuration)
        val current = ownedDesktop.get()
        if (current == null || !current.process.isAlive) {
            if (app.runtimeState.current().desktop.phase == DesktopSessionPhase.STARTING) {
                pendingDesktopRestart.set(request)
                stopDesktopProcess(restarting = true)
                return
            }
            pendingDesktopRestart.set(null)
            startDesktopInternal(request)
            return
        }
        pendingDesktopRestart.set(request)
        stopDesktopProcess(restarting = true)
    }

    fun stopDesktop() {
        pendingDesktopRestart.set(null)
        stopDesktopProcess(restarting = false)
    }

    fun launchLinuxApplication(
        application: LinuxApplication,
        rootfsName: String,
        callback: (Result<Unit>) -> Unit,
    ) {
        val snapshot = app.runtimeState.current()
        if (
            snapshot.phase != RuntimePhase.RUNNING ||
            currentTerminalSession()?.isRunning != true
        ) {
            callback(Result.failure(IllegalStateException("Start Linux before launching an app")))
            return
        }
        if (currentTerminalSession()?.mSessionName != rootfsName) {
            callback(
                Result.failure(
                    IllegalStateException("Switch to $rootfsName before launching ${application.name}"),
                ),
            )
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

        if (applicationProcesses[application.id]?.isAlive == true) {
            app.journal.append(
                component = "linux-app",
                severity = "info",
                event = "app_reused",
                message = "Returned to the running ${application.name} window",
                bootId = snapshot.bootId,
                fields = mapOf("desktop_id" to application.id),
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
                        val rootfs = InstalledRootfsResolver.resolve(this, rootfsName)
                        val launch =
                            ProotApplicationLaunchBuilder.create(
                                context = this,
                                runtime = ProotRuntimeInstaller.install(this),
                                rootfs = rootfs,
                                x11SocketDirectory = socketDirectory,
                                application = application,
                                audioEndpoint = audioController.endpoint(),
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

    private fun startDesktopInternal(request: DesktopLaunchRequest) {
        val runtime = app.runtimeState.current()
        val terminal = currentTerminalSession()
        if (
            runtime.phase != RuntimePhase.RUNNING ||
            terminal?.isRunning != true ||
            terminal.mSessionName != request.rootfsName
        ) {
            publishDesktopFailure(
                request,
                "Start ${request.rootfsName} before starting its desktop",
            )
            return
        }
        if (runtime.desktop.phase == DesktopSessionPhase.STARTING) {
            publishState(
                app.runtimeState.update {
                    it.copy(
                        desktop =
                            it.desktop.copy(
                                message =
                                    "${runtime.desktop.environmentName ?: "A desktop"} " +
                                        "is already starting on display :0",
                            ),
                    )
                },
            )
            return
        }
        val existing = ownedDesktop.get()
        if (existing?.process?.isAlive == true) {
            if (
                existing.rootfsName == request.rootfsName &&
                existing.environment.id == request.environment.id
            ) {
                publishState(
                    app.runtimeState.update {
                        it.copy(
                            desktop =
                                it.desktop.copy(
                                    phase = DesktopSessionPhase.RUNNING,
                                    desiredRunning = true,
                                    message = "${request.environment.name} is already running",
                                ),
                        )
                    },
                )
            } else {
                publishDesktopFailure(
                    request,
                    "${existing.environment.name} owns display :0; stop it before switching",
                )
            }
            return
        }
        ownedDesktop.compareAndSet(existing, null)
        val launchToken = UUID.randomUUID().toString()
        desktopLaunchToken.set(launchToken)
        publishState(
            app.runtimeState.update {
                it.copy(
                    desktop =
                        DesktopSessionSnapshot(
                            phase = DesktopSessionPhase.STARTING,
                            desiredRunning = true,
                            rootfsName = request.rootfsName,
                            environmentId = request.environment.id,
                            environmentName = request.environment.name,
                            displayNumber = DISPLAY_NUMBER,
                            message = "Starting ${request.environment.name} on display :0",
                        ),
                )
            },
        )
        app.journal.append(
            component = "desktop",
            severity = "info",
            event = "desktop_start_requested",
            message = "Starting ${request.environment.name} for ${request.rootfsName}",
            bootId = runtime.bootId,
            fields =
                mapOf(
                    "rootfs" to request.rootfsName,
                    "environment_id" to request.environment.id,
                    "display" to DISPLAY_NUMBER,
                    "compositing" to request.configuration.compositingEnabled,
                    "touch_scale" to request.configuration.touchScaleEnabled,
                ),
        )
        x11Controller.whenReady { socketDirectory ->
            if (desktopLaunchToken.get() != launchToken) return@whenReady
            if (socketDirectory == null) {
                if (desktopLaunchToken.compareAndSet(launchToken, null)) {
                    publishDesktopFailure(request, "Embedded X11 display :0 is unavailable")
                }
                return@whenReady
            }
            applicationExecutor.execute {
                runCatching {
                    if (desktopLaunchToken.get() != launchToken) return@runCatching null
                    val rootfs = InstalledRootfsResolver.resolve(this, request.rootfsName)
                    val launch =
                        ProotDesktopLaunchBuilder.create(
                            context = this,
                            runtime = ProotRuntimeInstaller.install(this),
                            rootfs = rootfs,
                            x11SocketDirectory = socketDirectory,
                            environment = request.environment,
                            configuration = request.configuration,
                            audioEndpoint = audioController.endpoint(),
                        )
                    val pidFile =
                        File(cacheDir, "desktop-process-$launchToken.pid").apply {
                            delete()
                        }
                    val process =
                        ProcessBuilder(wrapWithPidFile(launch.command, pidFile))
                            .directory(launch.workingDirectory)
                            .redirectErrorStream(true)
                            .apply {
                                environment().clear()
                                environment().putAll(launch.environment)
                            }.start()
                    val hostPid =
                        awaitHostPid(pidFile)
                            ?: run {
                                process.destroy()
                                error("Desktop launcher did not publish its host PID")
                            }
                    if (desktopLaunchToken.get() != launchToken) {
                        runCatching { Os.kill(hostPid, OsConstants.SIGKILL) }
                        pidFile.delete()
                        return@runCatching null
                    }
                    val owned =
                        OwnedDesktopProcess(
                            process = process,
                            hostPid = hostPid,
                            pidFile = pidFile,
                            rootfsName = request.rootfsName,
                            environment = request.environment,
                        )
                    check(ownedDesktop.compareAndSet(null, owned)) {
                        "Another desktop session won display :0"
                    }
                    owned
                }.onSuccess { owned ->
                    if (owned == null) return@onSuccess
                    if (!desktopLaunchToken.compareAndSet(launchToken, null)) {
                        ownedDesktop.compareAndSet(owned, null)
                        terminateDesktopProcess(owned, OsConstants.SIGKILL)
                        return@onSuccess
                    }
                    publishState(
                        app.runtimeState.update {
                            it.copy(
                                desktop =
                                    DesktopSessionSnapshot(
                                        phase = DesktopSessionPhase.RUNNING,
                                        desiredRunning = true,
                                        rootfsName = owned.rootfsName,
                                        environmentId = owned.environment.id,
                                        environmentName = owned.environment.name,
                                        displayNumber = DISPLAY_NUMBER,
                                        message =
                                            "${owned.environment.name} owns display :0",
                                    ),
                            )
                        },
                    )
                    updateNotification("${owned.environment.name} desktop is running")
                    app.journal.append(
                        component = "desktop",
                        severity = "info",
                        event = "desktop_started",
                        message = "${owned.environment.name} started on display :0",
                        bootId = app.runtimeState.current().bootId,
                        fields =
                            mapOf(
                                "rootfs" to owned.rootfsName,
                                "display" to DISPLAY_NUMBER,
                            ),
                    )
                    monitorDesktop(owned)
                }.onFailure { error ->
                    if (desktopLaunchToken.compareAndSet(launchToken, null)) {
                        publishDesktopFailure(
                            request,
                            error.message ?: "Could not start ${request.environment.name}",
                        )
                    }
                }
            }
        }
    }

    private fun stopDesktopProcess(restarting: Boolean) {
        desktopLaunchToken.set(null)
        val current = ownedDesktop.get()
        if (current == null || !current.process.isAlive) {
            ownedDesktop.compareAndSet(current, null)
            publishState(
                app.runtimeState.update {
                    it.copy(
                        desktop =
                            DesktopSessionSnapshot(
                                message =
                                    if (restarting) {
                                        "Preparing to restart the desktop"
                                    } else {
                                        "Desktop session is stopped"
                                    },
                            ),
                    )
                },
            )
            pendingDesktopRestart.getAndSet(null)?.let(::startDesktopInternal)
            return
        }
        publishState(
            app.runtimeState.update {
                it.copy(
                    desktop =
                        it.desktop.copy(
                            phase = DesktopSessionPhase.STOPPING,
                            desiredRunning = false,
                            message =
                                if (restarting) {
                                    "Restarting ${current.environment.name}"
                                } else {
                                    "Stopping ${current.environment.name}"
                                },
                        ),
                )
            },
        )
        terminateDesktopProcess(current, OsConstants.SIGTERM)
        mainHandler.postDelayed(
            {
                if (ownedDesktop.get() === current && current.process.isAlive) {
                    app.journal.append(
                        component = "desktop",
                        severity = "warning",
                        event = "desktop_force_stop",
                        message = "${current.environment.name} ignored SIGTERM",
                        bootId = app.runtimeState.current().bootId,
                        fields = mapOf("rootfs" to current.rootfsName),
                    )
                    terminateDesktopProcess(current, OsConstants.SIGKILL)
                }
            },
            GRACEFUL_STOP_TIMEOUT_MS,
        )
    }

    private fun monitorDesktop(owned: OwnedDesktopProcess) {
        applicationExecutor.execute {
            var linesRead = 0
            runCatching {
                BufferedReader(InputStreamReader(owned.process.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (linesRead < MAX_DESKTOP_OUTPUT_LINES) {
                            app.journal.append(
                                component = "desktop",
                                severity = "debug",
                                event = "desktop_output",
                                message = line.take(MAX_APP_OUTPUT_CHARS),
                                bootId = app.runtimeState.current().bootId,
                                fields = mapOf("environment_id" to owned.environment.id),
                            )
                        }
                        linesRead++
                    }
                }
            }
        }
        applicationExecutor.execute {
            val exitCode =
                try {
                    owned.process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            mainHandler.post { handleDesktopExit(owned, exitCode) }
        }
    }

    private fun handleDesktopExit(
        owned: OwnedDesktopProcess,
        exitCode: Int,
    ) {
        if (!ownedDesktop.compareAndSet(owned, null)) return
        owned.pidFile.delete()
        val previous = app.runtimeState.current().desktop
        val expected =
            previous.phase == DesktopSessionPhase.STOPPING ||
                !previous.desiredRunning
        publishState(
            app.runtimeState.update {
                it.copy(
                    desktop =
                        DesktopSessionSnapshot(
                            phase =
                                if (expected) {
                                    DesktopSessionPhase.STOPPED
                                } else {
                                    DesktopSessionPhase.CRASHED
                                },
                            message =
                                if (expected) {
                                    "${owned.environment.name} stopped"
                                } else {
                                    "${owned.environment.name} exited with $exitCode"
                                },
                        ),
                )
            },
        )
        app.journal.append(
            component = "desktop",
            severity = if (expected) "info" else "error",
            event = if (expected) "desktop_stopped" else "desktop_crashed",
            message = "${owned.environment.name} exited with $exitCode",
            bootId = app.runtimeState.current().bootId,
            fields =
                mapOf(
                    "rootfs" to owned.rootfsName,
                    "environment_id" to owned.environment.id,
                    "exit_code" to exitCode,
                ),
        )
        pendingDesktopRestart.getAndSet(null)?.let(::startDesktopInternal)
    }

    private fun publishDesktopFailure(
        request: DesktopLaunchRequest,
        message: String,
    ) {
        pendingDesktopRestart.set(null)
        publishState(
            app.runtimeState.update {
                it.copy(
                    desktop =
                        DesktopSessionSnapshot(
                            phase = DesktopSessionPhase.CRASHED,
                            rootfsName = request.rootfsName,
                            environmentId = request.environment.id,
                            environmentName = request.environment.name,
                            message = message,
                        ),
                )
            },
        )
        app.journal.append(
            component = "desktop",
            severity = "error",
            event = "desktop_start_failed",
            message = message,
            bootId = app.runtimeState.current().bootId,
            fields =
                mapOf(
                    "rootfs" to request.rootfsName,
                    "environment_id" to request.environment.id,
                ),
        )
    }

    private fun wrapWithPidFile(
        command: List<String>,
        pidFile: File,
    ): List<String> =
        buildList {
            add("/system/bin/sh")
            add("-c")
            add("printf '%s' \"\$\$\" > \"\$1\"; shift; exec \"\$@\"")
            add("udroid-desktop-host")
            add(pidFile.absolutePath)
            addAll(command)
        }

    private fun awaitHostPid(pidFile: File): Int? {
        repeat(50) {
            pidFile
                .takeIf(File::isFile)
                ?.let { file ->
                    file.readText().trim().toIntOrNull()?.let { return it }
                }
            Thread.sleep(20)
        }
        return null
    }

    private fun terminateDesktopProcess(
        owned: OwnedDesktopProcess,
        signal: Int,
    ) {
        try {
            Os.kill(owned.hostPid, signal)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESRCH) {
                app.journal.append(
                    component = "desktop",
                    severity = "warning",
                    event = "desktop_signal_failed",
                    message = error.message ?: "Could not signal desktop process",
                    bootId = app.runtimeState.current().bootId,
                    fields =
                        mapOf(
                            "rootfs" to owned.rootfsName,
                            "host_pid" to owned.hostPid,
                            "signal" to signal,
                        ),
                )
            }
        }
        if (signal == OsConstants.SIGKILL) owned.pidFile.delete()
    }

    private fun startRuntime(
        requestedRootfsName: String? = null,
        audioConfiguration: AudioConfiguration =
            effectiveAudioConfiguration(requestedRootfsName, allowMicrophone = false),
    ) {
        val existing = terminalTabs.active()
        if (existing?.value?.isRunning == true && existing.value.pid > 0) {
            if (requestedRootfsName != null && existing.rootfsName != requestedRootfsName) {
                publishState(
                    app.runtimeState.update {
                        it.copy(
                            message =
                                "${existing.rootfsName} is running; stop it before switching " +
                                    "to $requestedRootfsName",
                        )
                    },
                )
                return
            }
            publishState(
                app.runtimeState.update {
                    it.copy(
                        phase = RuntimePhase.RUNNING,
                        desiredRunning = true,
                        message = "${terminalTabs.size()} terminal tab(s) running",
                        childPid = existing.value.pid.toLong(),
                        rootfsName = existing.rootfsName,
                    )
                },
            )
            return
        }
        terminalTabs.clear().forEach { it.value.finishIfRunning() }
        closingTerminalIds.clear()
        nextTerminalNumber = 1

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
            val rootfs = InstalledRootfsResolver.resolve(this, requestedRootfsName)
            runCatching { audioController.apply(audioConfiguration, bootId) }
                .onFailure { error ->
                    app.journal.append(
                        component = "audio",
                        severity = "error",
                        event = "server_start_failed",
                        message = error.message ?: "PulseAudio failed to start",
                        bootId = bootId,
                        fields = mapOf("exception" to error.javaClass.name),
                    )
                }
            val x11SocketDirectory = x11Controller.start(rootfs, bootId)
            ProotTerminalLaunchBuilder.create(
                context = this,
                runtime = runtime,
                rootfs = rootfs,
                x11SocketDirectory = x11SocketDirectory,
                audioEndpoint = audioController.endpoint(),
            )
        }.mapCatching { launch ->
            launch to createTerminalSession(launch)
        }
            .onSuccess { (launch, tab) ->
                attachActiveTerminalToViews()
                val session = tab.value
                val running =
                    app.runtimeState.update {
                        it.copy(
                            phase = RuntimePhase.RUNNING,
                            desiredRunning = true,
                            message = "${tab.title} is running · PID ${session.pid}",
                            childPid = session.pid.toLong(),
                            rootfsName = launch.rootfs.name,
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
                            "tab_id" to tab.id,
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

    private fun createTerminalSession(launch: ProotTerminalLaunch): TerminalTab<TerminalSession> {
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
        val tab =
            TerminalTab(
                id = session.mHandle,
                title = "Terminal ${nextTerminalNumber++}",
                rootfsName = launch.rootfs.name,
                value = session,
            )
        terminalTabs.add(tab)
        try {
            session.updateSize(
                DEFAULT_COLUMNS,
                DEFAULT_ROWS,
                DEFAULT_CELL_WIDTH_PX,
                DEFAULT_CELL_HEIGHT_PX,
            )
            check(session.pid > 0) { "Termux did not return a terminal child PID" }
        } catch (error: Throwable) {
            terminalTabs.remove(tab.id)
            if (session.pid > 0) session.finishIfRunning()
            throw error
        }
        return tab
    }

    private fun attachActiveTerminalToViews() {
        val session = currentTerminalSession() ?: return
        attachedViews.forEach { view ->
            if (view.mTermSession !== session) view.attachSession(session)
            view.onScreenUpdated()
        }
    }

    private fun publishTerminalTabState(message: String) {
        val active = terminalTabs.active()
        val next =
            app.runtimeState.update {
                it.copy(
                    phase = if (active == null) it.phase else RuntimePhase.RUNNING,
                    desiredRunning = active != null || it.desiredRunning,
                    message = message,
                    childPid = active?.value?.pid?.takeIf { pid -> pid > 0 }?.toLong(),
                    rootfsName = active?.rootfsName ?: it.rootfsName,
                )
            }
        publishState(next)
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
        val tab = terminalTabs.findByValue(session) ?: return
        val individuallyClosed = closingTerminalIds.remove(tab.id)
        terminalTabs.remove(tab.id)
        val exitCode = session.exitStatus
        val beforeExit = app.runtimeState.current()
        val expected = !beforeExit.desiredRunning || beforeExit.phase == RuntimePhase.STOPPING

        if (terminalTabs.size() > 0) {
            attachActiveTerminalToViews()
            val active = terminalTabs.active()
            val detail =
                if (individuallyClosed || expected) {
                    "${tab.title} closed · ${terminalTabs.size()} tab(s) remaining"
                } else {
                    "${tab.title} exited with code $exitCode · ${terminalTabs.size()} tab(s) remaining"
                }
            if (beforeExit.phase == RuntimePhase.STOPPING) {
                publishState(
                    app.runtimeState.update {
                        it.copy(
                            message = "Stopping ${terminalTabs.size()} remaining terminal tab(s)",
                            childPid = active?.value?.pid?.takeIf { pid -> pid > 0 }?.toLong(),
                        )
                    },
                )
            } else {
                publishTerminalTabState(detail)
            }
            app.journal.append(
                component = "terminal",
                severity = if (individuallyClosed || expected) "info" else "error",
                event = if (individuallyClosed || expected) "tab_closed" else "tab_crashed",
                message =
                    if (individuallyClosed || expected) {
                        "Terminal tab closed"
                    } else {
                        "Terminal tab exited unexpectedly with code $exitCode"
                    },
                bootId = beforeExit.bootId,
                fields =
                    mapOf(
                        "tab_id" to tab.id,
                        "title" to tab.title,
                        "exit_code" to exitCode,
                        "active_tab_id" to active?.id,
                        "remaining_tabs" to terminalTabs.size(),
                    ),
            )
            updateNotification("${terminalTabs.size()} Linux terminal tabs are running")
            return
        }

        attachedViews.forEach(TerminalView::onScreenUpdated)
        pendingDesktopRestart.set(null)
        stopDesktopProcess(restarting = false)
        val next =
            app.runtimeState.update { RuntimeStateMachine.afterProcessExit(it, exitCode) }
        stopApplicationProcesses()
        x11Controller.stop(beforeExit.bootId)
        audioController.stop(beforeExit.bootId)
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
        pendingDesktopRestart.set(null)
        stopDesktopProcess(restarting = false)
        stopApplicationProcesses()
        x11Controller.stop(current.bootId)
        audioController.stop(current.bootId)
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

        val sessions = terminalTabs.all()
        if (sessions.isEmpty()) {
            publishStoppedState()
            return
        }
        sessions.forEach { tab ->
            closingTerminalIds += tab.id
            terminateTerminalTab(tab)
        }
    }

    private fun terminateTerminalTab(tab: TerminalTab<TerminalSession>) {
        val session = tab.value
        if (!session.isRunning || session.pid < 1) {
            handleSessionFinished(session)
            return
        }
        try {
            Os.kill(session.pid, OsConstants.SIGTERM)
        } catch (error: ErrnoException) {
            app.journal.append(
                component = "supervisor",
                severity = "warning",
                event = "terminal_sigterm_failed",
                message = error.message ?: "Could not signal ${tab.title}",
                bootId = app.runtimeState.current().bootId,
                fields = mapOf("tab_id" to tab.id, "child_pid" to session.pid),
            )
        }
        mainHandler.postDelayed(
            {
                if (terminalTabs.get(tab.id)?.value === session && session.isRunning) {
                    app.journal.append(
                        component = "supervisor",
                        severity = "warning",
                        event = "terminal_force_stop",
                        message = "${tab.title} ignored SIGTERM; forcing it to stop",
                        bootId = app.runtimeState.current().bootId,
                        fields = mapOf("tab_id" to tab.id, "child_pid" to session.pid),
                    )
                    session.finishIfRunning()
                }
            },
            GRACEFUL_STOP_TIMEOUT_MS,
        )
    }

    private fun publishStoppedState() {
        terminalTabs.clear()
        closingTerminalIds.clear()
        val stopped =
            app.runtimeState.update {
                it.copy(
                    phase = RuntimePhase.STOPPED,
                    desiredRunning = false,
                    message = "uDroid terminal is stopped",
                    childPid = null,
                    heartbeatSequence = null,
                    rootfsName = null,
                    desktop = DesktopSessionSnapshot(),
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
            runCatching {
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
            }.onFailure { error ->
                val replaced = applicationProcesses[application.id] !== process
                if (!replaced && process.isAlive) {
                    runCatching {
                        app.journal.append(
                            component = "linux-app",
                            severity = "warning",
                            event = "app_output_failed",
                            message =
                                "Could not read ${application.name} output: " +
                                    (error.message ?: error.javaClass.simpleName),
                            bootId = app.runtimeState.current().bootId,
                            fields = mapOf("desktop_id" to application.id),
                        )
                    }
                }
            }
        }
        applicationExecutor.execute processWait@{
            val exitCode =
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@processWait
                }
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
                    description = "Linux runtime and desktop lifecycle"
                },
            )
    }

    private fun effectiveAudioConfiguration(
        rootfsName: String?,
        allowMicrophone: Boolean,
    ): AudioConfiguration {
        val resolvedName =
            rootfsName
                ?: runCatching { app.rootfsRegistry.active()?.name }.getOrNull()
                ?: return AudioConfiguration()
        val saved = audioConfigurationStore.load(resolvedName)
        val microphoneGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        return saved.copy(
            microphoneEnabled =
                saved.microphoneEnabled && allowMicrophone && microphoneGranted,
        )
    }

    private fun startRuntimeForeground(
        text: String,
        configuration: AudioConfiguration,
    ) {
        val type =
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    var value = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    if (configuration.outputEnabled) {
                        value = value or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    }
                    if (configuration.microphoneEnabled) {
                        value = value or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                    value
                }
                Build.VERSION.SDK_INT >= 30 -> {
                    var value = 0
                    if (configuration.outputEnabled) {
                        value = value or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    }
                    if (configuration.microphoneEnabled) {
                        value = value or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                    value
                }
                Build.VERSION.SDK_INT >= 29 -> {
                    if (configuration.outputEnabled) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        0
                    }
                }
                else -> 0
            }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(text),
            type,
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
            .setContentTitle("uDroid Linux")
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
        const val EXTRA_ROOTFS_NAME = "rootfs-name"

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
        private const val MAX_DESKTOP_OUTPUT_LINES = 400
        private const val MAX_TERMINAL_TITLE_CHARS = 40
        private const val DISPLAY_NUMBER = 0

        fun start(
            context: Context,
            rootfsName: String? = null,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RuntimeSupervisorService::class.java)
                    .setAction(ACTION_START_RUNTIME)
                    .apply {
                        if (rootfsName != null) putExtra(EXTRA_ROOTFS_NAME, rootfsName)
                    },
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RuntimeSupervisorService::class.java)
                    .setAction(ACTION_STOP_RUNTIME),
            )
        }
    }

    private data class DesktopLaunchRequest(
        val rootfsName: String,
        val environment: DesktopEnvironment,
        val configuration: DesktopConfiguration,
    )

    private data class OwnedDesktopProcess(
        val process: Process,
        val hostPid: Int,
        val pidFile: File,
        val rootfsName: String,
        val environment: DesktopEnvironment,
    )
}

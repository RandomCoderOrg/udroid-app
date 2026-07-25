package org.randomcoder.udroid

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.randomcoder.udroid.catalog.DistroCatalogRepository
import org.randomcoder.udroid.catalog.DistroCatalogState
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.install.InstallationSelection
import org.randomcoder.udroid.install.InstallerService
import org.randomcoder.udroid.linuxapps.DesktopApplicationScanner
import org.randomcoder.udroid.linuxapps.LinuxApplication
import org.randomcoder.udroid.linuxapps.LinuxApplicationShortcutContract
import org.randomcoder.udroid.linuxapps.LinuxApplicationShortcutPublisher
import org.randomcoder.udroid.linuxapps.LinuxApplicationsState
import org.randomcoder.udroid.runtime.CapabilityProbe
import org.randomcoder.udroid.runtime.CapabilityResult
import org.randomcoder.udroid.runtime.InstalledRootfsResolver
import org.randomcoder.udroid.runtime.RuntimeSnapshot
import org.randomcoder.udroid.runtime.RuntimeSupervisorService
import org.randomcoder.udroid.ui.UdroidApp
import org.randomcoder.udroid.ui.UdroidCanvas
import org.randomcoder.udroid.ui.UdroidDestination
import org.randomcoder.udroid.ui.UdroidTerminal
import org.randomcoder.udroid.ui.UdroidTheme
import org.randomcoder.udroid.ui.workspaceJourney
import org.randomcoder.udroid.update.AppUpdateContract
import org.randomcoder.udroid.update.AppUpdateDownloadService
import org.randomcoder.udroid.update.AppUpdateInstaller
import org.randomcoder.udroid.update.AppUpdatePhase
import org.randomcoder.udroid.update.AppUpdateScheduler
import org.randomcoder.udroid.update.AppUpdateState
import org.randomcoder.udroid.update.UpdateInstallResult

class MainActivity : ComponentActivity() {
    private val app: UdroidApplication
        get() = application as UdroidApplication

    private var snapshot by mutableStateOf(RuntimeSnapshot())
    private var capabilities by mutableStateOf<List<CapabilityResult>>(emptyList())
    private var journalLines by mutableStateOf<List<String>>(emptyList())
    private var selectedDestination by mutableStateOf(UdroidDestination.HOME)
    private var catalogueState by mutableStateOf<DistroCatalogState>(DistroCatalogState.Loading)
    private var installProgress by mutableStateOf<InstallProgress?>(null)
    private var updateState by mutableStateOf(AppUpdateState())
    private var installedRootfsName by mutableStateOf<String?>(null)
    private var linuxApplicationsState by
        mutableStateOf<LinuxApplicationsState>(LinuxApplicationsState.Loading)
    private var linuxApplicationMessage by mutableStateOf<String?>(null)
    private var pendingLinuxApplication: LinuxApplication? = null
    private var pendingLinuxApplicationId: String? = null
    private var showInstallTerminal by mutableStateOf(false)
    private var runtimeService by mutableStateOf<RuntimeSupervisorService?>(null)
    private var runtimeServiceBound = false

    private val runtimeServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                val connectedService =
                    (binder as? RuntimeSupervisorService.RuntimeBinder)?.service()
                runtimeService = connectedService
                refreshFromDisk()
                launchPendingLinuxApplication()
                if (snapshot.desiredRunning && connectedService?.currentTerminalSession() == null) {
                    RuntimeSupervisorService.start(this@MainActivity)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                runtimeService = null
            }
        }

    private val stateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                refreshFromDisk()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(UdroidDestination.HOME)
        setContent {
            UdroidTheme {
                UdroidApp(
                    destination = selectedDestination,
                    snapshot = snapshot,
                    capabilities = capabilities,
                    journalLines = journalLines,
                    catalogueState = catalogueState,
                    installProgress = installProgress,
                    updateState = updateState,
                    installedRootfsName = installedRootfsName,
                    linuxApplicationsState = linuxApplicationsState,
                    linuxApplicationMessage = linuxApplicationMessage,
                    showInstallTerminal = showInstallTerminal,
                    runtimeService = runtimeService,
                    onDestinationSelected = ::selectDestination,
                    onStart = {
                        ensureNotificationPermission()
                        RuntimeSupervisorService.start(this)
                        selectDestination(UdroidDestination.TERMINAL)
                    },
                    onStop = { RuntimeSupervisorService.stop(this) },
                    onRefresh = { refreshAll() },
                    onReloadCatalogue = { loadCatalogue() },
                    onPreviewInstall = { selectDistro(it) },
                    onStartDownload = { startSelectedDownload() },
                    onPauseDownload = { InstallerService.pause(this) },
                    onToggleInstallTerminal = {
                        showInstallTerminal = !showInstallTerminal
                    },
                    onCloseInstall = {
                        if (installProgress?.cancellable != true) {
                            app.installState.clear()
                            installProgress = null
                            showInstallTerminal = false
                            if (installedRootfsName != null) {
                                selectDestination(UdroidDestination.HOME)
                            }
                        }
                    },
                    onRetryDownload = { startSelectedDownload() },
                    onRefreshLinuxApplications = { loadLinuxApplications() },
                    onLaunchLinuxApplication = { launchLinuxApplication(it) },
                    onPinLinuxApplication = { pinLinuxApplication(it) },
                    onCheckForUpdates = { AppUpdateScheduler.checkNow(this) },
                    onDownloadUpdate = {
                        ensureNotificationPermission()
                        AppUpdateDownloadService.start(this)
                    },
                    onCancelUpdate = { AppUpdateDownloadService.cancel(this) },
                    onInstallUpdate = { installDownloadedUpdate() },
                    onOpenUpdateRelease = { openUpdateRelease() },
                )
            }
        }
        refreshAll()
        loadCatalogue()
        handleUpdateIntent(intent)
        if (!handleShortcutIntent(intent)) loadLinuxApplications()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
        handleShortcutIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter().apply {
                addAction(RuntimeSupervisorService.ACTION_STATE_CHANGED)
                addAction(InstallerService.ACTION_STATE_CHANGED)
                addAction(AppUpdateContract.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        runtimeServiceBound =
            bindService(
                Intent(this, RuntimeSupervisorService::class.java),
                runtimeServiceConnection,
                Context.BIND_AUTO_CREATE,
            )
        refreshFromDisk()
    }

    override fun onStop() {
        if (runtimeServiceBound) {
            unbindService(runtimeServiceConnection)
            runtimeServiceBound = false
            runtimeService = null
        }
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun selectDestination(destination: UdroidDestination) {
        val resolvedDestination =
            workspaceJourney(
                requestedDestination = destination,
                hasInstalledLinux = installedRootfsName != null,
                hasInstallation = installProgress != null,
                compactNavigation = false,
            ).destination
        selectedDestination = resolvedDestination
        applySystemBars(resolvedDestination)
        if (resolvedDestination == UdroidDestination.APPS) loadLinuxApplications()
    }

    private fun applySystemBars(destination: UdroidDestination) {
        val terminal =
            destination == UdroidDestination.TERMINAL ||
                destination == UdroidDestination.DESKTOP
        val scrim = if (terminal) UdroidTerminal.toArgb() else UdroidCanvas.toArgb()
        enableEdgeToEdge(
            statusBarStyle =
                if (terminal) {
                    SystemBarStyle.dark(scrim)
                } else {
                    SystemBarStyle.light(scrim, scrim)
                },
            navigationBarStyle =
                if (terminal) {
                    SystemBarStyle.dark(scrim)
                } else {
                    SystemBarStyle.light(scrim, scrim)
                },
        )
    }

    private fun refreshAll() {
        refreshFromDisk()
        lifecycleScope.launch {
            capabilities = withContext(Dispatchers.IO) { CapabilityProbe.run(this@MainActivity) }
        }
    }

    private fun refreshFromDisk() {
        snapshot = app.runtimeState.current()
        installProgress = app.installState.current()
        updateState = app.updateState.current()
        installedRootfsName =
            runCatching { InstalledRootfsResolver.resolve(this).name }
                .getOrNull()
        val resolvedDestination =
            workspaceJourney(
                requestedDestination = selectedDestination,
                hasInstalledLinux = installedRootfsName != null,
                hasInstallation = installProgress != null,
                compactNavigation = false,
            ).destination
        if (resolvedDestination != selectedDestination) {
            selectedDestination = resolvedDestination
            applySystemBars(resolvedDestination)
        }
        journalLines = app.journal.tail()
        launchPendingLinuxApplication()
    }

    private fun loadCatalogue() {
        catalogueState = DistroCatalogState.Loading
        lifecycleScope.launch {
            catalogueState =
                runCatching {
                    withContext(Dispatchers.IO) {
                        DistroCatalogRepository(this@MainActivity).load()
                    }
                }.fold(
                    onSuccess = { DistroCatalogState.Ready(it) },
                    onFailure = {
                        DistroCatalogState.Failed(
                            it.message ?: "The distro catalogue could not be read",
                        )
                    },
                )
        }
    }

    private fun selectDistro(distro: DistroVariant) {
        selectDestination(UdroidDestination.DISTROS)
        showInstallTerminal = false
        installProgress = app.installState.save(InstallationSelection.initial(distro))
    }

    private fun startSelectedDownload() {
        ensureNotificationPermission()
        installProgress?.distro?.let { InstallerService.start(this, it) }
    }

    private fun loadLinuxApplications() {
        linuxApplicationsState = LinuxApplicationsState.Loading
        lifecycleScope.launch {
            val loadedState =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val rootfs = InstalledRootfsResolver.resolve(this@MainActivity)
                        LinuxApplicationsState.Ready(
                            rootfsName = rootfs.name,
                            result = DesktopApplicationScanner().scan(rootfs),
                        )
                    }
                }.getOrElse {
                    LinuxApplicationsState.Failed(
                        it.message ?: "The installed Linux image could not be scanned",
                    )
                }
            linuxApplicationsState = loadedState
            resolveShortcutApplication(loadedState)
        }
    }

    private fun launchLinuxApplication(application: LinuxApplication) {
        pendingLinuxApplicationId = null
        linuxApplicationMessage = "Preparing ${application.name}…"
        if (
            snapshot.phase != org.randomcoder.udroid.runtime.RuntimePhase.RUNNING ||
            runtimeService?.currentTerminalSession()?.isRunning != true
        ) {
            pendingLinuxApplication = application
            ensureNotificationPermission()
            RuntimeSupervisorService.start(this)
            linuxApplicationMessage = "Starting Linux for ${application.name}…"
            return
        }
        launchWithRuntime(application)
    }

    private fun pinLinuxApplication(application: LinuxApplication) {
        linuxApplicationMessage = "Preparing ${application.name} shortcut…"
        LinuxApplicationShortcutPublisher(this)
            .publishAndRequestPin(application)
            .fold(
                onSuccess = { result ->
                    linuxApplicationMessage =
                        if (result.dynamicPublished) {
                            "${application.name} is now a launcher shortcut. " +
                                "Confirm Add to home screen."
                        } else {
                            "Confirm Add to home screen for ${application.name}."
                        }
                },
                onFailure = {
                    linuxApplicationMessage =
                        it.message ?: "Could not create a shortcut for ${application.name}"
                },
            )
    }

    private fun handleShortcutIntent(intent: Intent?): Boolean {
        if (intent?.action != LinuxApplicationShortcutContract.ACTION_LAUNCH) return false
        val applicationId =
            intent
                .getStringExtra(LinuxApplicationShortcutContract.EXTRA_APPLICATION_ID)
                ?.takeIf(String::isNotBlank)
                ?: return false
        intent.removeExtra(LinuxApplicationShortcutContract.EXTRA_APPLICATION_ID)
        pendingLinuxApplicationId = applicationId
        linuxApplicationMessage = "Finding the Linux application…"
        selectDestination(UdroidDestination.APPS)
        return true
    }

    private fun resolveShortcutApplication(state: LinuxApplicationsState) {
        val applicationId = pendingLinuxApplicationId ?: return
        when (state) {
            LinuxApplicationsState.Loading -> Unit
            is LinuxApplicationsState.Ready -> {
                pendingLinuxApplicationId = null
                val application =
                    state.result.applications.firstOrNull { it.id == applicationId }
                if (application == null) {
                    linuxApplicationMessage =
                        "This shortcut is no longer available in ${state.rootfsName}"
                } else {
                    launchLinuxApplication(application)
                }
            }
            is LinuxApplicationsState.Failed -> {
                pendingLinuxApplicationId = null
                linuxApplicationMessage = state.message
            }
        }
    }

    private fun launchPendingLinuxApplication() {
        if (
            snapshot.phase != org.randomcoder.udroid.runtime.RuntimePhase.RUNNING ||
            runtimeService?.currentTerminalSession()?.isRunning != true
        ) {
            return
        }
        pendingLinuxApplication?.let { application ->
            pendingLinuxApplication = null
            launchWithRuntime(application)
        }
    }

    private fun launchWithRuntime(application: LinuxApplication) {
        runtimeService?.launchLinuxApplication(application) { result ->
            result.fold(
                onSuccess = {
                    linuxApplicationMessage = "${application.name} launched"
                    selectDestination(
                        if (application.terminal) {
                            UdroidDestination.TERMINAL
                        } else {
                            UdroidDestination.DESKTOP
                        },
                    )
                },
                onFailure = {
                    linuxApplicationMessage =
                        it.message ?: "Could not launch ${application.name}"
                    selectDestination(UdroidDestination.APPS)
                },
            )
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.action != AppUpdateContract.ACTION_SHOW_UPDATE) return
        intent.action = null
        selectDestination(UdroidDestination.ABOUT)
        refreshFromDisk()
    }

    private fun installDownloadedUpdate() {
        when (val result = AppUpdateInstaller.install(this, updateState)) {
            UpdateInstallResult.Submitted -> {
                updateState =
                    app.updateState.update {
                        it.copy(message = "Waiting for Android installation confirmation")
                    }
            }
            UpdateInstallResult.PermissionRequested -> {
                updateState =
                    app.updateState.update {
                        it.copy(
                            phase = AppUpdatePhase.READY,
                            message = "Allow uDroid to install unknown apps, then tap Install",
                        )
                    }
            }
            is UpdateInstallResult.Failed -> {
                updateState =
                    app.updateState.update {
                        it.copy(
                            phase = AppUpdatePhase.READY,
                            message = result.message,
                        )
                    }
            }
        }
    }

    private fun openUpdateRelease() {
        val url = updateState.release?.releaseUrl ?: return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 101
    }
}

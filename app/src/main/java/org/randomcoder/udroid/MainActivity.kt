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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.randomcoder.udroid.audio.AudioConfiguration
import org.randomcoder.udroid.audio.AudioConfigurationStore
import org.randomcoder.udroid.catalog.DistroCatalogRepository
import org.randomcoder.udroid.catalog.DistroCatalogState
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.install.InstallStage
import org.randomcoder.udroid.install.InstallationSelection
import org.randomcoder.udroid.install.InstallerService
import org.randomcoder.udroid.install.InstallerWorkRequest
import org.randomcoder.udroid.install.OciInstallationSelection
import org.randomcoder.udroid.install.ResetInstallationSelection
import org.randomcoder.udroid.linuxapps.DesktopApplicationScanner
import org.randomcoder.udroid.linuxapps.LinuxApplication
import org.randomcoder.udroid.linuxapps.LinuxApplicationShortcutContract
import org.randomcoder.udroid.linuxapps.LinuxApplicationShortcutPublisher
import org.randomcoder.udroid.linuxapps.LinuxApplicationsState
import org.randomcoder.udroid.media.MediaAccelerationConfiguration
import org.randomcoder.udroid.media.MediaAccelerationConfigurationStore
import org.randomcoder.udroid.oci.OciHubCatalogRepository
import org.randomcoder.udroid.oci.OciHubCatalogueState
import org.randomcoder.udroid.oci.OciHubRepository
import org.randomcoder.udroid.oci.OciHubTagPlatform
import org.randomcoder.udroid.oci.OciHubTagRepository
import org.randomcoder.udroid.oci.OciHubTagsState
import org.randomcoder.udroid.oci.OciPlatform
import org.randomcoder.udroid.runtime.CapabilityProbe
import org.randomcoder.udroid.runtime.CapabilityResult
import org.randomcoder.udroid.runtime.DesktopCompositorSupport
import org.randomcoder.udroid.runtime.DesktopConfiguration
import org.randomcoder.udroid.runtime.DesktopConfigurationStore
import org.randomcoder.udroid.runtime.DesktopEnvironment
import org.randomcoder.udroid.runtime.DesktopEnvironmentScanner
import org.randomcoder.udroid.runtime.DesktopSessionPhase
import org.randomcoder.udroid.runtime.InstalledRootfs
import org.randomcoder.udroid.runtime.RuntimePhase
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
import java.util.UUID

private data class PendingLinuxApplication(
    val application: LinuxApplication,
    val rootfsName: String,
)

private data class PendingDesktopStart(
    val rootfsName: String,
    val environment: DesktopEnvironment,
    val configuration: DesktopConfiguration,
)

class MainActivity : ComponentActivity() {
    private val app: UdroidApplication
        get() = application as UdroidApplication

    private var snapshot by mutableStateOf(RuntimeSnapshot())
    private var capabilities by mutableStateOf<List<CapabilityResult>>(emptyList())
    private var journalLines by mutableStateOf<List<String>>(emptyList())
    private var selectedDestination by mutableStateOf(UdroidDestination.HOME)
    private var catalogueState by mutableStateOf<DistroCatalogState>(DistroCatalogState.Loading)
    private var ociCatalogueState by
        mutableStateOf<OciHubCatalogueState>(OciHubCatalogueState.Loading)
    private var selectedOciRepository by mutableStateOf<OciHubRepository?>(null)
    private var ociTagsState by mutableStateOf<OciHubTagsState>(OciHubTagsState.Idle)
    private var installProgress by mutableStateOf<InstallProgress?>(null)
    private var updateState by mutableStateOf(AppUpdateState())
    private var installedRootfsName by mutableStateOf<String?>(null)
    private var installedRootfses by mutableStateOf<List<InstalledRootfs>>(emptyList())
    private var resettableRootfsNames by mutableStateOf<Set<String>>(emptySet())
    private var selectedSystemRootfsName by mutableStateOf<String?>(null)
    private var rootfsMaintenanceName by mutableStateOf<String?>(null)
    private var rootfsMaintenanceMessage by mutableStateOf<String?>(null)
    private var desktopEnvironments by mutableStateOf<List<DesktopEnvironment>>(emptyList())
    private var desktopConfiguration by
        mutableStateOf(DesktopConfiguration(null, compositingEnabled = false, touchScaleEnabled = true))
    private var desktopScanLoading by mutableStateOf(false)
    private var desktopScanMessage by mutableStateOf<String?>(null)
    private var audioConfiguration by mutableStateOf(AudioConfiguration())
    private var audioConfigurationMessage by mutableStateOf<String?>(null)
    private var mediaAccelerationConfiguration by
        mutableStateOf(MediaAccelerationConfiguration())
    private var mediaAccelerationConfigurationMessage by mutableStateOf<String?>(null)
    private var linuxApplicationsState by
        mutableStateOf<LinuxApplicationsState>(LinuxApplicationsState.Loading)
    private var linuxApplicationMessage by mutableStateOf<String?>(null)
    private var pendingLinuxApplication: PendingLinuxApplication? = null
    private var pendingLinuxApplicationId: String? = null
    private var pendingDesktopStart: PendingDesktopStart? = null
    private var pendingShortcutRootfsName: String? = null
    private var pendingTerminalRootfsName: String? = null
    private var pendingMicrophoneRootfsName: String? = null
    private var linuxApplicationsLoadGeneration = 0L
    private var desktopScanGeneration = 0L
    private var ociCatalogueLoadGeneration = 0L
    private var ociTagsLoadGeneration = 0L
    private var restoredOciRepositoryName: String? = null
    private var showInstallTerminal by mutableStateOf(false)
    private var runtimeService by mutableStateOf<RuntimeSupervisorService?>(null)
    private var runtimeServiceBound = false
    private val desktopConfigurationStore by lazy { DesktopConfigurationStore(this) }
    private val audioConfigurationStore by lazy { AudioConfigurationStore(this) }
    private val mediaAccelerationConfigurationStore by lazy {
        MediaAccelerationConfigurationStore(this)
    }
    private val ociHubCatalogueRepository by lazy { OciHubCatalogRepository(this) }
    private val ociHubTagRepository by lazy { OciHubTagRepository(this) }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val rootfsName = pendingMicrophoneRootfsName
            pendingMicrophoneRootfsName = null
            if (rootfsName == null) return@registerForActivityResult
            if (granted) {
                val current = audioConfigurationStore.load(rootfsName)
                saveAudioConfiguration(
                    rootfsName,
                    current.copy(microphoneEnabled = true),
                )
            } else {
                if (selectedSystemRootfsName == rootfsName) {
                    audioConfiguration =
                        audioConfigurationStore
                            .load(rootfsName)
                            .copy(microphoneEnabled = false)
                    audioConfigurationMessage =
                        "Microphone access was not granted. Linux input remains off."
                }
            }
        }

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
                launchPendingDesktop()
                if (snapshot.desiredRunning && connectedService?.currentTerminalSession() == null) {
                    RuntimeSupervisorService.start(
                        this@MainActivity,
                        app.rootfsRegistry.active()?.name,
                    )
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
        restoredOciRepositoryName = savedInstanceState?.getString(STATE_OCI_REPOSITORY)
        applySystemBars(UdroidDestination.HOME)
        setContent {
            UdroidTheme {
                UdroidApp(
                    destination = selectedDestination,
                    snapshot = snapshot,
                    capabilities = capabilities,
                    journalLines = journalLines,
                    catalogueState = catalogueState,
                    ociCatalogueState = ociCatalogueState,
                    selectedOciRepository = selectedOciRepository,
                    ociTagsState = ociTagsState,
                    installProgress = installProgress,
                    updateState = updateState,
                    installedRootfsName = installedRootfsName,
                    installedRootfses = installedRootfses,
                    resettableRootfsNames = resettableRootfsNames,
                    selectedSystemRootfsName = selectedSystemRootfsName,
                    rootfsMaintenanceName = rootfsMaintenanceName,
                    rootfsMaintenanceMessage = rootfsMaintenanceMessage,
                    desktopEnvironments = desktopEnvironments,
                    desktopConfiguration = desktopConfiguration,
                    desktopScanLoading = desktopScanLoading,
                    desktopScanMessage = desktopScanMessage,
                    audioConfiguration = audioConfiguration,
                    audioConfigurationMessage = audioConfigurationMessage,
                    mediaAccelerationConfiguration = mediaAccelerationConfiguration,
                    mediaAccelerationConfigurationMessage = mediaAccelerationConfigurationMessage,
                    linuxApplicationsState = linuxApplicationsState,
                    linuxApplicationMessage = linuxApplicationMessage,
                    showInstallTerminal = showInstallTerminal,
                    runtimeService = runtimeService,
                    onDestinationSelected = ::selectDestination,
                    onPrimaryDestinationSelected = ::selectPrimaryDestination,
                    onStart = {
                        ensureNotificationPermission()
                        RuntimeSupervisorService.start(this, installedRootfsName)
                        selectDestination(UdroidDestination.TERMINAL)
                    },
                    onStop = { RuntimeSupervisorService.stop(this) },
                    onRefresh = { refreshAll() },
                    onReloadCatalogue = { loadCatalogue(forceOciRefresh = true) },
                    onPreviewInstall = { selectDistro(it) },
                    onSelectOciRepository = { selectOciRepository(it) },
                    onRetryOciTags = {
                        selectedOciRepository?.let {
                            selectOciRepository(it, forceRefresh = true)
                        }
                    },
                    onBackFromOciRepository = { closeOciRepository() },
                    onSelectOciTag = { repository, tag -> selectOciTag(repository, tag) },
                    onOpenInstalledSystem = { openSystemDetails(it) },
                    onOpenRootfsTerminal = { openRootfsTerminal(it) },
                    onOpenRootfsApps = { openRootfsApps(it) },
                    onResetRootfs = { rootfsName, fallback ->
                        resetRootfs(rootfsName, fallback)
                    },
                    onDeleteRootfs = { deleteRootfs(it) },
                    onSelectDesktopEnvironment = { selectDesktopEnvironment(it) },
                    onCompositingChanged = { updateCompositing(it) },
                    onTouchScaleChanged = { updateTouchScale(it) },
                    onAudioOutputChanged = { updateAudioOutput(it) },
                    onMicrophoneChanged = { updateMicrophone(it) },
                    onMediaAccelerationChanged = { updateMediaAcceleration(it) },
                    onStartDesktop = { startSelectedDesktop() },
                    onStopDesktop = { runtimeService?.stopDesktop() },
                    onRestartDesktop = { restartSelectedDesktop() },
                    onStartDownload = { startSelectedDownload() },
                    onPauseDownload = { InstallerService.pause(this) },
                    onToggleInstallTerminal = {
                        showInstallTerminal = !showInstallTerminal
                    },
                    onCloseInstall = {
                        if (installProgress?.cancellable == true) {
                            selectDestination(UdroidDestination.DISTROS)
                        } else {
                            val completed = installProgress?.stage == InstallStage.COMPLETE
                            app.installState.clear()
                            installProgress = null
                            showInstallTerminal = false
                            if (completed) {
                                closeOciRepository()
                                selectDestination(UdroidDestination.HOME)
                            } else {
                                selectDestination(UdroidDestination.DISTROS)
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

    override fun onSaveInstanceState(outState: Bundle) {
        selectedOciRepository?.name?.let {
            outState.putString(STATE_OCI_REPOSITORY, it)
        }
        super.onSaveInstanceState(outState)
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
        if (resolvedDestination == UdroidDestination.SYSTEM) {
            selectedSystemRootfsName?.let(::loadDesktopEnvironments)
        }
    }

    private fun selectPrimaryDestination(destination: UdroidDestination) {
        if (destination == UdroidDestination.DISTROS) {
            closeOciRepository()
        }
        selectDestination(destination)
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
        val previousPhase = snapshot.phase
        snapshot = app.runtimeState.current()
        installProgress = app.installState.current()
        updateState = app.updateState.current()
        installedRootfses = app.rootfsRegistry.all()
        installedRootfsName = app.rootfsRegistry.active()?.name
        resettableRootfsNames =
            installedRootfses
                .mapNotNull { rootfs ->
                    rootfs.name.takeIf {
                        runCatching {
                            app.rootfsInstallSources.loadOrRecover(
                                installationName = rootfs.name,
                                rootfs = rootfs.directory,
                            )
                        }.getOrNull() != null
                    }
                }.toSet()
        if (selectedSystemRootfsName !in installedRootfses.map(InstalledRootfs::name)) {
            selectedSystemRootfsName = installedRootfsName
        }
        selectedSystemRootfsName
            ?.takeIf {
                snapshot.rootfsName == it &&
                    snapshot.phase == RuntimePhase.RUNNING &&
                    previousPhase != RuntimePhase.RUNNING
            }?.let { rootfsName ->
                val configuration = mediaAccelerationConfigurationStore.load(rootfsName)
                mediaAccelerationConfiguration = configuration
                mediaAccelerationConfigurationMessage =
                    mediaAccelerationRuntimeMessage(rootfsName, configuration)
            }
        installProgress
            ?.takeIf { progress ->
                progress.stage == InstallStage.READY &&
                    installedRootfses.any { it.name == progress.installationName }
            }?.let {
                app.installState.clear()
                installProgress = null
            }
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
        pendingTerminalRootfsName?.let { rootfsName ->
            val session = runtimeService?.currentTerminalSession()
            if (
                session?.isRunning != true &&
                snapshot.phase != org.randomcoder.udroid.runtime.RuntimePhase.STARTING &&
                snapshot.phase != org.randomcoder.udroid.runtime.RuntimePhase.STOPPING
            ) {
                pendingTerminalRootfsName = null
                RuntimeSupervisorService.start(this, rootfsName)
            }
        }
        launchPendingLinuxApplication()
        launchPendingDesktop()
    }

    private fun loadCatalogue(forceOciRefresh: Boolean = false) {
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
        loadOciCatalogue(forceRefresh = forceOciRefresh)
    }

    private fun loadOciCatalogue(forceRefresh: Boolean) {
        val generation = ++ociCatalogueLoadGeneration
        ociCatalogueState = OciHubCatalogueState.Loading
        lifecycleScope.launch {
            val platform =
                runCatching {
                    OciPlatform.fromAndroidAbis(Build.SUPPORTED_ABIS.toList())
                }.getOrElse {
                    if (generation == ociCatalogueLoadGeneration) {
                        ociCatalogueState =
                            OciHubCatalogueState.Failed(
                                it.message ?: "This phone architecture is not supported",
                            )
                    }
                    return@launch
                }
            val result =
                runCatching {
                    withContext(Dispatchers.IO) {
                        ociHubCatalogueRepository.load(forceRefresh = forceRefresh)
                    }
                }
            if (generation != ociCatalogueLoadGeneration) return@launch
            ociCatalogueState =
                result.fold(
                    onSuccess = { OciHubCatalogueState.Ready(it, platform) },
                    onFailure = {
                        OciHubCatalogueState.Failed(
                            it.message ?: "Official image catalogue could not be read",
                        )
                    },
                )
            val restoreName = restoredOciRepositoryName
            if (restoreName != null && result.isSuccess) {
                restoredOciRepositoryName = null
                result.getOrThrow()
                    .repositories
                    .firstOrNull { it.name == restoreName }
                    ?.let(::selectOciRepository)
            }
        }
    }

    private fun selectDistro(distro: DistroVariant) {
        closeOciRepository()
        if (installedRootfses.any { it.name == distro.internalName }) {
            openSystemDetails(distro.internalName)
            return
        }
        showInstallTerminal = false
        installProgress = app.installState.save(InstallationSelection.initial(distro))
        selectDestination(UdroidDestination.INSTALL)
    }

    private fun selectOciRepository(
        repository: OciHubRepository,
        forceRefresh: Boolean = false,
    ) {
        val ready = ociCatalogueState as? OciHubCatalogueState.Ready ?: return
        val generation = ++ociTagsLoadGeneration
        selectedOciRepository = repository
        ociTagsState = OciHubTagsState.Loading
        selectDestination(UdroidDestination.DISTROS)
        lifecycleScope.launch {
            val result =
                runCatching {
                    withContext(Dispatchers.IO) {
                        ociHubTagRepository.load(
                            repository = repository.name,
                            platform = ready.platform,
                            forceRefresh = forceRefresh,
                        )
                    }
                }
            if (
                generation != ociTagsLoadGeneration ||
                selectedOciRepository?.name != repository.name
            ) {
                return@launch
            }
            ociTagsState =
                result.fold(
                    onSuccess = { OciHubTagsState.Ready(it) },
                    onFailure = {
                        OciHubTagsState.Failed(
                            it.message ?: "Compatible image versions could not be read",
                        )
                    },
                )
        }
    }

    private fun closeOciRepository() {
        ociTagsLoadGeneration++
        selectedOciRepository = null
        ociTagsState = OciHubTagsState.Idle
    }

    private fun selectOciTag(
        repository: OciHubRepository,
        tag: OciHubTagPlatform,
    ) {
        val installationName =
            OciInstallationSelection.installationName(repository.name, tag.tag)
        if (installedRootfses.any { it.name == installationName }) {
            openSystemDetails(installationName)
            return
        }
        val displayArchitecture =
            (catalogueState as? DistroCatalogState.Ready)
                ?.catalog
                ?.architecture
                ?: when (tag.platform.architecture) {
                    "arm64" -> "aarch64"
                    "arm" -> "armhf"
                    else -> tag.platform.architecture
                }
        showInstallTerminal = false
        installProgress =
            app.installState.save(
                OciInstallationSelection.initial(
                    repository = repository,
                    tag = tag,
                    displayArchitecture = displayArchitecture,
                ),
            )
        selectDestination(UdroidDestination.INSTALL)
    }

    private fun startSelectedDownload() {
        ensureNotificationPermission()
        installProgress?.work?.let { InstallerService.start(this, it) }
    }

    private fun loadLinuxApplications() {
        val generation = ++linuxApplicationsLoadGeneration
        linuxApplicationsState = LinuxApplicationsState.Loading
        lifecycleScope.launch {
            val loadedState =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val rootfs = app.rootfsRegistry.resolve()
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
            if (generation == linuxApplicationsLoadGeneration) {
                linuxApplicationsState = loadedState
                resolveShortcutApplication(loadedState)
            }
        }
    }

    private fun openSystemDetails(rootfsName: String) {
        if (installedRootfses.none { it.name == rootfsName }) return
        selectedSystemRootfsName = rootfsName
        rootfsMaintenanceMessage = null
        loadAudioConfiguration(rootfsName)
        loadMediaAccelerationConfiguration(rootfsName)
        selectDestination(UdroidDestination.SYSTEM)
    }

    private fun deleteRootfs(rootfsName: String) {
        maintainRootfs(rootfsName, resetWork = null)
    }

    private fun resetRootfs(
        rootfsName: String,
        fallbackDistro: DistroVariant?,
    ) {
        val previousWork =
            runCatching { app.rootfsInstallSources.load(rootfsName) }
                .getOrNull()
                ?: fallbackDistro
                    ?.takeIf { it.internalName == rootfsName }
                    ?.let {
                        InstallerWorkRequest.Archive(
                            distro = it,
                            operationId = UUID.randomUUID().toString(),
                        )
                    }
        if (previousWork == null) {
            rootfsMaintenanceMessage =
                "The original image source was not recorded for this legacy install. " +
                "Delete it and install the image again."
            return
        }
        maintainRootfs(
            rootfsName = rootfsName,
            resetWork = ResetInstallationSelection.initial(previousWork),
        )
    }

    private fun maintainRootfs(
        rootfsName: String,
        resetWork: InstallProgress?,
    ) {
        rootfsMaintenanceBlockReason(rootfsName)?.let {
            rootfsMaintenanceMessage = it
            return
        }
        if (installedRootfses.none { it.name == rootfsName }) {
            rootfsMaintenanceMessage = "Linux system $rootfsName is no longer installed"
            return
        }

        rootfsMaintenanceName = rootfsName
        rootfsMaintenanceMessage =
            if (resetWork == null) {
                "Deleting the Linux filesystem…"
            } else {
                "Removing the old filesystem before reinstalling…"
            }
        lifecycleScope.launch {
            val deletion =
                runCatching {
                    withContext(Dispatchers.IO) {
                        app.rootfsRegistry.delete(rootfsName)
                    }
                }
            if (deletion.isFailure) {
                rootfsMaintenanceName = null
                rootfsMaintenanceMessage =
                    deletion.exceptionOrNull()?.message
                        ?: "The Linux filesystem could not be removed"
                return@launch
            }

            val cleanupWarnings = mutableListOf<String>()
            runCatching { desktopConfigurationStore.remove(rootfsName) }
                .exceptionOrNull()
                ?.let { cleanupWarnings += it.message ?: "desktop settings" }
            runCatching { audioConfigurationStore.remove(rootfsName) }
                .exceptionOrNull()
                ?.let { cleanupWarnings += it.message ?: "audio settings" }
            runCatching { mediaAccelerationConfigurationStore.remove(rootfsName) }
                .exceptionOrNull()
                ?.let { cleanupWarnings += it.message ?: "media acceleration settings" }
            runCatching {
                LinuxApplicationShortcutPublisher(this@MainActivity)
                    .disableForRootfs(rootfsName)
            }.exceptionOrNull()
                ?.let { cleanupWarnings += it.message ?: "launcher shortcuts" }

            if (resetWork == null) {
                runCatching { app.rootfsInstallSources.remove(rootfsName) }
                    .exceptionOrNull()
                    ?.let { cleanupWarnings += it.message ?: "install source" }
                app.installState
                    .current()
                    ?.takeIf { it.installationName == rootfsName }
                    ?.let { app.installState.clear() }
            } else {
                app.installState.clear()
                app.rootfsInstallSources.save(resetWork.work)
                installProgress = app.installState.save(resetWork)
            }

            app.journal.append(
                component = "rootfs",
                severity = if (cleanupWarnings.isEmpty()) "info" else "warning",
                event = if (resetWork == null) "rootfs_deleted" else "rootfs_reset",
                message =
                    if (resetWork == null) {
                        "Linux filesystem deleted"
                    } else {
                        "Linux filesystem removed; reinstall started"
                    },
                bootId = resetWork?.operationId ?: UUID.randomUUID().toString(),
                fields =
                    buildMap {
                        put("installation", rootfsName)
                        put("cleanup_warnings", cleanupWarnings.joinToString("; "))
                    },
            )

            pendingLinuxApplication = null
            pendingDesktopStart = null
            pendingTerminalRootfsName = null
            linuxApplicationsLoadGeneration++
            desktopScanGeneration++
            rootfsMaintenanceName = null
            rootfsMaintenanceMessage = null
            selectedSystemRootfsName = null
            refreshFromDisk()
            if (resetWork != null) {
                selectDestination(UdroidDestination.INSTALL)
                ensureNotificationPermission()
                InstallerService.start(this@MainActivity, resetWork.work)
            } else {
                selectDestination(UdroidDestination.DISTROS)
            }
        }
    }

    private fun rootfsMaintenanceBlockReason(rootfsName: String): String? =
        when {
            rootfsMaintenanceName != null ->
                "Another filesystem operation is already running"
            installProgress?.cancellable == true ->
                "Wait for the current Linux installation to stop"
            runtimeService?.currentTerminalSession()?.let {
                it.isRunning && it.mSessionName == rootfsName
            } == true ->
                "Stop this Linux terminal before changing its filesystem"
            snapshot.rootfsName == rootfsName &&
                snapshot.phase in
                setOf(
                    RuntimePhase.STARTING,
                    RuntimePhase.RUNNING,
                    RuntimePhase.STOPPING,
                ) ->
                "Stop this Linux terminal before changing its filesystem"
            snapshot.desktop.rootfsName == rootfsName &&
                snapshot.desktop.phase in
                setOf(
                    DesktopSessionPhase.STARTING,
                    DesktopSessionPhase.RUNNING,
                    DesktopSessionPhase.STOPPING,
                ) ->
                "Stop this desktop session before changing its filesystem"
            else -> null
        }

    private fun loadDesktopEnvironments(rootfsName: String) {
        val generation = ++desktopScanGeneration
        desktopScanLoading = true
        desktopScanMessage = null
        lifecycleScope.launch {
            val result =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val rootfs =
                            app.rootfsRegistry
                                .all()
                                .firstOrNull { it.name == rootfsName }
                                ?.directory
                                ?: error("Linux system $rootfsName is no longer installed")
                        val environments = DesktopEnvironmentScanner().scan(rootfs)
                        environments to desktopConfigurationStore.load(rootfsName, environments)
                    }
                }
            if (generation != desktopScanGeneration) return@launch
            desktopScanLoading = false
            result.fold(
                onSuccess = { (environments, configuration) ->
                    desktopEnvironments = environments
                    desktopConfiguration = configuration
                    desktopScanMessage =
                        if (environments.isEmpty()) {
                            "No X11 session was found in /usr/share/xsessions"
                        } else {
                            "${environments.size} desktop session" +
                                if (environments.size == 1) " detected" else "s detected"
                        }
                },
                onFailure = {
                    desktopEnvironments = emptyList()
                    desktopConfiguration =
                        DesktopConfiguration(
                            environmentId = null,
                            compositingEnabled = false,
                            touchScaleEnabled = true,
                        )
                    desktopScanMessage = it.message ?: "Desktop detection failed"
                },
            )
        }
    }

    private fun selectDesktopEnvironment(environmentId: String) {
        val rootfsName = selectedSystemRootfsName ?: return
        val environment = desktopEnvironments.firstOrNull { it.id == environmentId } ?: return
        val compositing =
            when (environment.kind.compositorSupport) {
                DesktopCompositorSupport.REQUIRED -> true
                DesktopCompositorSupport.EXTERNAL_OR_NONE -> false
                else -> desktopConfiguration.compositingEnabled
            }
        saveDesktopConfiguration(
            rootfsName,
            desktopConfiguration.copy(
                environmentId = environmentId,
                compositingEnabled = compositing,
            ),
        )
    }

    private fun updateCompositing(enabled: Boolean) {
        val rootfsName = selectedSystemRootfsName ?: return
        val environment =
            desktopEnvironments.firstOrNull {
                it.id == desktopConfiguration.environmentId
            } ?: return
        if (environment.kind.compositorSupport != DesktopCompositorSupport.CONFIGURABLE) return
        saveDesktopConfiguration(
            rootfsName,
            desktopConfiguration.copy(compositingEnabled = enabled),
        )
    }

    private fun updateTouchScale(enabled: Boolean) {
        val rootfsName = selectedSystemRootfsName ?: return
        saveDesktopConfiguration(
            rootfsName,
            desktopConfiguration.copy(touchScaleEnabled = enabled),
        )
    }

    private fun loadAudioConfiguration(rootfsName: String) {
        runCatching { audioConfigurationStore.load(rootfsName) }
            .onSuccess {
                audioConfiguration = it
                audioConfigurationMessage = null
            }.onFailure {
                audioConfiguration = AudioConfiguration()
                audioConfigurationMessage = it.message ?: "Could not load audio settings"
            }
    }

    private fun updateAudioOutput(enabled: Boolean) {
        val rootfsName = selectedSystemRootfsName ?: return
        saveAudioConfiguration(
            rootfsName,
            audioConfiguration.copy(outputEnabled = enabled),
        )
    }

    private fun updateMicrophone(enabled: Boolean) {
        val rootfsName = selectedSystemRootfsName ?: return
        if (!enabled) {
            pendingMicrophoneRootfsName = null
            saveAudioConfiguration(
                rootfsName,
                audioConfiguration.copy(microphoneEnabled = false),
            )
            return
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            saveAudioConfiguration(
                rootfsName,
                audioConfiguration.copy(microphoneEnabled = true),
            )
            return
        }
        pendingMicrophoneRootfsName = rootfsName
        audioConfigurationMessage = "Allow microphone access to send input to Linux."
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun saveAudioConfiguration(
        rootfsName: String,
        configuration: AudioConfiguration,
    ) {
        runCatching { audioConfigurationStore.save(rootfsName, configuration) }
            .onSuccess { saved ->
                val service = runtimeService
                val runningThisRootfs =
                    service?.currentTerminalSession()?.isRunning == true &&
                        service.currentTerminalSession()?.mSessionName == rootfsName
                if (selectedSystemRootfsName == rootfsName) {
                    audioConfiguration = saved
                    audioConfigurationMessage =
                        if (runningThisRootfs) {
                            "Applying audio settings to the running Linux system…"
                        } else {
                            "Audio settings will apply when this Linux system starts."
                        }
                }
                if (runningThisRootfs) {
                    service.applyAudioConfiguration(rootfsName, saved) { result ->
                        if (selectedSystemRootfsName != rootfsName) return@applyAudioConfiguration
                        audioConfigurationMessage =
                            result.fold(
                                onSuccess = { it.message },
                                onFailure = {
                                    it.message ?: "Could not apply audio settings"
                                },
                            )
                    }
                }
            }.onFailure {
                audioConfigurationMessage = it.message ?: "Could not save audio settings"
            }
    }

    private fun loadMediaAccelerationConfiguration(rootfsName: String) {
        runCatching { mediaAccelerationConfigurationStore.load(rootfsName) }
            .onSuccess {
                mediaAccelerationConfiguration = it
                mediaAccelerationConfigurationMessage =
                    mediaAccelerationRuntimeMessage(rootfsName, it)
            }.onFailure {
                mediaAccelerationConfiguration = MediaAccelerationConfiguration()
                mediaAccelerationConfigurationMessage =
                    it.message ?: "Could not load media acceleration settings"
            }
    }

    private fun updateMediaAcceleration(enabled: Boolean) {
        val rootfsName = selectedSystemRootfsName ?: return
        val configuration = MediaAccelerationConfiguration(enabled = enabled)
        runCatching {
            mediaAccelerationConfigurationStore.save(rootfsName, configuration)
        }.onSuccess {
            mediaAccelerationConfiguration = it
            val runningThisRootfs =
                runtimeService?.currentTerminalSession()?.isRunning == true &&
                    runtimeService?.currentTerminalSession()?.mSessionName == rootfsName
            mediaAccelerationConfigurationMessage =
                if (runningThisRootfs) {
                    "Restart this Linux system to apply the change."
                } else if (enabled) {
                    "Android video decoding will start with this Linux system."
                } else {
                    "Android video decoding is off."
                }
        }.onFailure {
            mediaAccelerationConfigurationMessage =
                it.message ?: "Could not save media acceleration settings"
        }
    }

    private fun mediaAccelerationRuntimeMessage(
        rootfsName: String,
        configuration: MediaAccelerationConfiguration,
    ): String? {
        if (!configuration.enabled) return null
        val runtimeOwnsSystem =
            snapshot.rootfsName == rootfsName && snapshot.phase == RuntimePhase.RUNNING
        if (!runtimeOwnsSystem) return null
        val service = runtimeService ?: return "Android video decoding is enabled for this session."
        return if (service.currentMediaSession().running) {
            "Android video decoding is active."
        } else {
            "Android video decoding did not start. Check the supervisor log."
        }
    }

    private fun saveDesktopConfiguration(
        rootfsName: String,
        configuration: DesktopConfiguration,
    ) {
        runCatching { desktopConfigurationStore.save(rootfsName, configuration) }
            .onSuccess { desktopConfiguration = it }
            .onFailure { desktopScanMessage = it.message ?: "Could not save desktop settings" }
    }

    private fun startSelectedDesktop() {
        val rootfsName = selectedSystemRootfsName ?: return
        val environment =
            desktopEnvironments.firstOrNull {
                it.id == desktopConfiguration.environmentId
            } ?: desktopEnvironments.firstOrNull()
            ?: return
        setActiveRootfs(rootfsName)
        pendingDesktopStart =
            PendingDesktopStart(
                rootfsName = rootfsName,
                environment = environment,
                configuration =
                    desktopConfiguration.copy(environmentId = environment.id),
            )
        ensureNotificationPermission()
        val runningRootfs = runtimeService?.currentTerminalSession()?.mSessionName
        when {
            snapshot.phase == org.randomcoder.udroid.runtime.RuntimePhase.RUNNING &&
                runtimeService?.currentTerminalSession()?.isRunning == true &&
                runningRootfs == rootfsName -> launchPendingDesktop()
            runningRootfs != null && runningRootfs != rootfsName -> {
                pendingTerminalRootfsName = rootfsName
                RuntimeSupervisorService.stop(this)
            }
            else -> RuntimeSupervisorService.start(this, rootfsName)
        }
    }

    private fun launchPendingDesktop() {
        val pending = pendingDesktopStart ?: return
        val service = runtimeService ?: return
        if (
            snapshot.phase != org.randomcoder.udroid.runtime.RuntimePhase.RUNNING ||
            service.currentTerminalSession()?.isRunning != true ||
            service.currentTerminalSession()?.mSessionName != pending.rootfsName
        ) {
            return
        }
        pendingDesktopStart = null
        pendingTerminalRootfsName = null
        service.startDesktop(
            pending.rootfsName,
            pending.environment,
            pending.configuration,
        )
    }

    private fun restartSelectedDesktop() {
        val rootfsName = selectedSystemRootfsName ?: return
        val environment =
            desktopEnvironments.firstOrNull {
                it.id == desktopConfiguration.environmentId
            } ?: return
        runtimeService?.restartDesktop(rootfsName, environment, desktopConfiguration)
    }

    private fun launchLinuxApplication(application: LinuxApplication) {
        pendingLinuxApplicationId = null
        linuxApplicationMessage = "Preparing ${application.name}…"
        val rootfsName =
            (linuxApplicationsState as? LinuxApplicationsState.Ready)?.rootfsName
                ?: installedRootfsName
                ?: run {
                    linuxApplicationMessage = "Install Linux before launching an app"
                    return
                }
        setActiveRootfs(rootfsName)
        val runningRootfs = runtimeService?.currentTerminalSession()?.mSessionName
        if (
            snapshot.phase != org.randomcoder.udroid.runtime.RuntimePhase.RUNNING ||
            runtimeService?.currentTerminalSession()?.isRunning != true ||
            runningRootfs != rootfsName
        ) {
            pendingLinuxApplication = PendingLinuxApplication(application, rootfsName)
            ensureNotificationPermission()
            if (runningRootfs != null && runningRootfs != rootfsName) {
                pendingTerminalRootfsName = rootfsName
                RuntimeSupervisorService.stop(this)
            } else {
                RuntimeSupervisorService.start(this, rootfsName)
            }
            linuxApplicationMessage = "Starting Linux for ${application.name}…"
            return
        }
        launchWithRuntime(application, rootfsName)
    }

    private fun pinLinuxApplication(application: LinuxApplication) {
        val rootfsName =
            (linuxApplicationsState as? LinuxApplicationsState.Ready)?.rootfsName
                ?: return
        linuxApplicationMessage = "Preparing ${application.name} shortcut…"
        LinuxApplicationShortcutPublisher(this)
            .publishAndRequestPin(application, rootfsName)
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
        pendingShortcutRootfsName =
            intent
                .getStringExtra(LinuxApplicationShortcutContract.EXTRA_ROOTFS_NAME)
                ?.takeIf(String::isNotBlank)
        intent.removeExtra(LinuxApplicationShortcutContract.EXTRA_ROOTFS_NAME)
        pendingShortcutRootfsName?.let { rootfsName ->
            if (runCatching { app.rootfsRegistry.setActive(rootfsName) }.isFailure) {
                pendingShortcutRootfsName = null
                linuxApplicationMessage =
                    "The Linux system for this shortcut is no longer installed"
                selectDestination(UdroidDestination.APPS)
                return true
            }
        }
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
                if (
                    pendingShortcutRootfsName != null &&
                    state.rootfsName != pendingShortcutRootfsName
                ) {
                    loadLinuxApplications()
                    return
                }
                pendingLinuxApplicationId = null
                pendingShortcutRootfsName = null
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
                pendingShortcutRootfsName = null
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
        pendingLinuxApplication?.let { pending ->
            if (runtimeService?.currentTerminalSession()?.mSessionName != pending.rootfsName) {
                return
            }
            pendingLinuxApplication = null
            pendingTerminalRootfsName = null
            launchWithRuntime(pending.application, pending.rootfsName)
        }
    }

    private fun launchWithRuntime(
        application: LinuxApplication,
        rootfsName: String,
    ) {
        runtimeService?.launchLinuxApplication(application, rootfsName) { result ->
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

    private fun setActiveRootfs(rootfsName: String) {
        val selected = app.rootfsRegistry.setActive(rootfsName)
        installedRootfsName = selected.name
        installedRootfses = app.rootfsRegistry.all()
        linuxApplicationsLoadGeneration++
        linuxApplicationsState = LinuxApplicationsState.Loading
    }

    private fun openRootfsApps(rootfsName: String) {
        setActiveRootfs(rootfsName)
        selectDestination(UdroidDestination.APPS)
    }

    private fun openRootfsTerminal(rootfsName: String) {
        setActiveRootfs(rootfsName)
        ensureNotificationPermission()
        val runningSession = runtimeService?.currentTerminalSession()
        if (runningSession?.isRunning == true && runningSession.mSessionName != rootfsName) {
            pendingTerminalRootfsName = rootfsName
            RuntimeSupervisorService.stop(this)
        } else {
            pendingTerminalRootfsName = null
            RuntimeSupervisorService.start(this, rootfsName)
        }
        selectDestination(UdroidDestination.TERMINAL)
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
                        it.copy(message = "Android installer opened")
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
        const val STATE_OCI_REPOSITORY = "oci-repository"
    }
}

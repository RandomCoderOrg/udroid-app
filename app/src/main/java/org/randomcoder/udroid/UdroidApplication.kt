package org.randomcoder.udroid

import android.app.Application
import android.os.Build
import org.randomcoder.udroid.install.InstallStage
import org.randomcoder.udroid.install.InstallStateStore
import org.randomcoder.udroid.runtime.EventJournal
import org.randomcoder.udroid.runtime.InstalledRootfsRegistry
import org.randomcoder.udroid.runtime.RuntimeStateMachine
import org.randomcoder.udroid.runtime.RuntimeStateStore
import org.randomcoder.udroid.update.AppUpdateScheduler
import org.randomcoder.udroid.update.AppUpdateStateStore
import java.io.File

class UdroidApplication : Application() {
    lateinit var runtimeState: RuntimeStateStore
        private set

    lateinit var journal: EventJournal
        private set

    lateinit var installState: InstallStateStore
        private set

    lateinit var updateState: AppUpdateStateStore
        private set

    lateinit var rootfsRegistry: InstalledRootfsRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        runtimeState = RuntimeStateStore(this)
        journal = EventJournal(this)
        installState = InstallStateStore(this)
        updateState = AppUpdateStateStore(this)
        rootfsRegistry = InstalledRootfsRegistry(this)
        if (!isMainProcess()) return

        updateState.reconcileInstalledVersion(BuildConfig.VERSION_NAME)
        AppUpdateScheduler.ensureScheduled(this)
        installState.current()?.takeIf { it.cancellable }?.let { interrupted ->
            installState.save(
                interrupted.copy(
                    stage = InstallStage.PAUSED,
                    stageProgress = interrupted.overallProgress,
                    currentDetail =
                        if (interrupted.stage == InstallStage.EXTRACTING ||
                            interrupted.stage == InstallStage.CONFIGURING
                        ) {
                            "Verified archive saved; incomplete setup can restart"
                        } else {
                            "Previous operation stopped; saved data can resume"
                        },
                    terminalLines =
                        interrupted.terminalLines +
                            "[recover] previous installer process stopped cleanly",
                    cancellable = false,
                ),
            )
        }
        val persisted = runtimeState.current()
        val recovered = RuntimeStateMachine.recoverAfterApplicationCreate(persisted)
        if (recovered != persisted) {
            runtimeState.update { recovered }
        }
        journal.append(
            component = "android",
            severity = "info",
            event = "application_created",
            message = "Android application process created",
            bootId = runtimeState.current().bootId,
        )
    }

    private fun isMainProcess(): Boolean {
        val processName =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getProcessName()
            } else {
                runCatching {
                    File("/proc/self/cmdline")
                        .readText()
                        .trimEnd('\u0000')
                }.getOrNull()
            }
        return processName == packageName
    }
}

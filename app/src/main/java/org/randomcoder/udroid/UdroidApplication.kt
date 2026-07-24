package org.randomcoder.udroid

import android.app.Application
import org.randomcoder.udroid.install.InstallStateStore
import org.randomcoder.udroid.runtime.EventJournal
import org.randomcoder.udroid.runtime.RuntimeStateMachine
import org.randomcoder.udroid.runtime.RuntimeStateStore

class UdroidApplication : Application() {
    lateinit var runtimeState: RuntimeStateStore
        private set

    lateinit var journal: EventJournal
        private set

    lateinit var installState: InstallStateStore
        private set

    override fun onCreate() {
        super.onCreate()
        runtimeState = RuntimeStateStore(this)
        journal = EventJournal(this)
        installState = InstallStateStore(this)
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
}

package org.randomcoder.udroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** ADB-only entry point; the dev manifest requires the shell's DUMP permission. */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("command").orEmpty()
        if (!DebugCommandStore.isSupported(action)) {
            setResultCode(1)
            setResultData("Unsupported debug command: $action")
            return
        }
        val command =
            DebugCommand(
                action = action,
                rootfsName = intent.getStringExtra("rootfs"),
                environmentId = intent.getStringExtra("environment"),
            )
        if (!DebugCommandStore.enqueue(context, command)) {
            setResultCode(1)
            setResultData("A debug command is already pending")
            return
        }
        setResultCode(-1)
        setResultData("Queued $action")
    }
}

package org.randomcoder.udroid.x11

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.randomcoder.udroid.runtime.EventJournal
import java.io.File

class X11ServerController(
    private val context: Context,
    private val journal: EventJournal,
) {
    private val replyMessenger =
        Messenger(
            Handler(Looper.getMainLooper()) { message ->
                if (message.what != X11ServerProtocol.MESSAGE_STATUS) {
                    return@Handler false
                }
                recordStatus(message.data)
                true
            },
        )
    private var server: Messenger? = null
    private var bound = false
    private var pendingStart: StartRequest? = null

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                server = Messenger(binder)
                pendingStart?.let(::sendStart)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                server = null
                pendingStart?.let { request ->
                    journal.append(
                        component = "x11",
                        severity = "error",
                        event = "server_process_lost",
                        message = "Embedded X11 process disconnected",
                        bootId = request.bootId,
                    )
                }
            }

            override fun onBindingDied(name: ComponentName) {
                onServiceDisconnected(name)
            }
        }

    fun start(
        rootfs: File,
        bootId: String,
    ): File {
        val runtimeDirectory = X11RuntimePaths.runtimeDirectory(context)
        val socketDirectory = X11RuntimePaths.socketDirectory(context)
        check(socketDirectory.mkdirs() || socketDirectory.isDirectory) {
            "Could not prepare the private X11 socket directory"
        }
        val xkbRoot = File(rootfs, "usr/share/X11/xkb")
        val request = StartRequest(runtimeDirectory, xkbRoot, bootId)
        pendingStart = request

        journal.append(
            component = "x11",
            severity = "info",
            event = "server_start_requested",
            message = "Requesting embedded Termux:X11 display :0",
            bootId = bootId,
            fields =
                mapOf(
                    "protocol_version" to X11ServerProtocol.VERSION,
                    "socket" to X11RuntimePaths.displaySocket(context).absolutePath,
                ),
        )

        if (server != null) {
            sendStart(request)
        } else if (!bound) {
            bound =
                context.bindService(
                    Intent(context, X11ServerService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            check(bound) { "Android rejected the embedded X11 service binding" }
        }
        return socketDirectory
    }

    fun stop(bootId: String?) {
        server?.let { target ->
            runCatching {
                target.send(
                    Message.obtain(null, X11ServerProtocol.MESSAGE_STOP).apply {
                        arg1 = X11ServerProtocol.VERSION
                        replyTo = replyMessenger
                        data =
                            Bundle().apply {
                                putString(X11ServerProtocol.KEY_BOOT_ID, bootId)
                            }
                    },
                )
            }
        }
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        server = null
        pendingStart = null
    }

    private fun sendStart(request: StartRequest) {
        val target = server ?: return
        runCatching {
            target.send(
                Message.obtain(null, X11ServerProtocol.MESSAGE_START).apply {
                    arg1 = X11ServerProtocol.VERSION
                    replyTo = replyMessenger
                    data =
                        Bundle().apply {
                            putString(
                                X11ServerProtocol.KEY_RUNTIME_DIRECTORY,
                                request.runtimeDirectory.absolutePath,
                            )
                            putString(
                                X11ServerProtocol.KEY_XKB_ROOT,
                                request.xkbRoot.absolutePath,
                            )
                            putString(X11ServerProtocol.KEY_BOOT_ID, request.bootId)
                        }
                },
            )
        }.onFailure { error ->
            journal.append(
                component = "x11",
                severity = "error",
                event = "server_command_failed",
                message = error.message ?: "Could not command embedded X11",
                bootId = request.bootId,
                fields = mapOf("exception" to error.javaClass.name),
            )
        }
    }

    private fun recordStatus(data: Bundle) {
        val state = data.getString(X11ServerProtocol.KEY_STATE).orEmpty()
        val detail = data.getString(X11ServerProtocol.KEY_DETAIL).orEmpty()
        val bootId = data.getString(X11ServerProtocol.KEY_BOOT_ID)
        val fields =
            buildMap<String, Any?> {
                put("protocol_version", X11ServerProtocol.VERSION)
                put("server_pid", data.getInt(X11ServerProtocol.KEY_PID))
                data.getString(X11ServerProtocol.KEY_SOCKET_PATH)?.let {
                    put("socket", it)
                }
                if (data.containsKey(X11ServerProtocol.KEY_STARTUP_MILLIS)) {
                    put(
                        "startup_ms",
                        data.getLong(X11ServerProtocol.KEY_STARTUP_MILLIS),
                    )
                }
            }
        journal.append(
            component = "x11",
            severity = if (state == X11ServerProtocol.STATE_FAILED) "error" else "info",
            event = "server_$state",
            message = detail,
            bootId = bootId,
            fields = fields,
        )
        if (state == X11ServerProtocol.STATE_FAILED) {
            pendingStart = null
            if (bound) {
                runCatching { context.unbindService(connection) }
                bound = false
            }
            server = null
        }
    }

    private data class StartRequest(
        val runtimeDirectory: File,
        val xkbRoot: File,
        val bootId: String,
    )
}

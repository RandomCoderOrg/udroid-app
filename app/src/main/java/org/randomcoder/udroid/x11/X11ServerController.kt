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
import android.os.ParcelFileDescriptor
import android.system.Os
import org.randomcoder.udroid.runtime.EventJournal
import java.io.File

class X11ServerController(
    private val context: Context,
    private val journal: EventJournal,
) {
    private val replyMessenger =
        Messenger(
            Handler(Looper.getMainLooper()) { message ->
                when (message.what) {
                    X11ServerProtocol.MESSAGE_STATUS -> recordStatus(message.data)
                    X11ServerProtocol.MESSAGE_RENDERER -> receiveRenderer(message.data)
                    else -> return@Handler false
                }
                true
            },
        )
    private var server: Messenger? = null
    private var bound = false
    private var pendingStart: StartRequest? = null
    private var rendererCallback: ((ParcelFileDescriptor?) -> Unit)? = null
    private val readyCallbacks = mutableListOf<(X11DisplayEndpoint?) -> Unit>()
    private var rendererRequestInFlight = false
    private var ready = false

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
                rendererRequestInFlight = false
                rendererCallback?.invoke(null)
                rendererCallback = null
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
    ): X11DisplayEndpoint {
        val endpoint = selectEndpoint(rootfs, bootId)
        val xkbRoot = File(rootfs, "usr/share/X11/xkb")
        val request = StartRequest(endpoint, xkbRoot, bootId)
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
                    "socket" to File(endpoint.socketDirectory, "X0").absolutePath,
                    "transport" to endpoint.transport.journalValue,
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
        return endpoint
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
        ready = false
        rendererRequestInFlight = false
        rendererCallback?.invoke(null)
        rendererCallback = null
        readyCallbacks.toList().also(readyCallbacks::removeAll).forEach { it(null) }
    }

    fun requestRendererConnection(callback: (ParcelFileDescriptor?) -> Unit) {
        rendererCallback = callback
        if (ready && !rendererRequestInFlight) sendRendererRequest()
    }

    fun activeEndpoint(): X11DisplayEndpoint? =
        pendingStart
            ?.takeIf { ready }
            ?.endpoint
            ?.takeIf { it.socketDirectory.isDirectory }

    fun whenReady(callback: (X11DisplayEndpoint?) -> Unit) {
        val endpoint = activeEndpoint()
        if (endpoint != null) {
            callback(endpoint)
        } else {
            readyCallbacks += callback
        }
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
                                request.endpoint.runtimeDirectory.absolutePath,
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
                pendingStart?.endpoint?.transport?.journalValue?.let {
                    put("transport", it)
                }
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
        if (state == X11ServerProtocol.STATE_READY) {
            ready = true
            val endpoint = activeEndpoint()
            readyCallbacks.toList().also(readyCallbacks::removeAll).forEach {
                it(endpoint)
            }
            if (rendererCallback != null && !rendererRequestInFlight) {
                sendRendererRequest()
            }
        }
        if (state == X11ServerProtocol.STATE_FAILED) {
            ready = false
            rendererRequestInFlight = false
            rendererCallback?.invoke(null)
            rendererCallback = null
            readyCallbacks.toList().also(readyCallbacks::removeAll).forEach { it(null) }
            pendingStart = null
            if (bound) {
                runCatching { context.unbindService(connection) }
                bound = false
            }
            server = null
        }
    }

    private fun sendRendererRequest() {
        if (rendererRequestInFlight) return
        val target = server ?: return
        val request = pendingStart ?: return
        rendererRequestInFlight = true
        runCatching {
            target.send(
                Message.obtain(null, X11ServerProtocol.MESSAGE_GET_RENDERER).apply {
                    arg1 = X11ServerProtocol.VERSION
                    replyTo = replyMessenger
                    data =
                        Bundle().apply {
                            putString(X11ServerProtocol.KEY_BOOT_ID, request.bootId)
                        }
                },
            )
        }.onFailure {
            rendererRequestInFlight = false
            rendererCallback?.invoke(null)
            rendererCallback = null
        }
    }

    @Suppress("DEPRECATION")
    private fun receiveRenderer(data: Bundle) {
        rendererRequestInFlight = false
        val descriptor =
            data.getParcelable<ParcelFileDescriptor>(
                X11ServerProtocol.KEY_RENDERER_FD,
            )
        val callback = rendererCallback
        rendererCallback = null
        callback?.invoke(descriptor)
        journal.append(
            component = "x11",
            severity = if (descriptor == null) "error" else "info",
            event = if (descriptor == null) "renderer_connection_failed" else "renderer_connected",
            message =
                data.getString(X11ServerProtocol.KEY_DETAIL)
                    ?: "Renderer connection FD delivered to the Android UI",
            bootId = data.getString(X11ServerProtocol.KEY_BOOT_ID),
        )
    }

    private fun selectEndpoint(
        rootfs: File,
        bootId: String,
    ): X11DisplayEndpoint =
        runCatching { directRootfsEndpoint(rootfs) }
            .getOrElse { error ->
                journal.append(
                    component = "x11",
                    severity = "warning",
                    event = "direct_transport_unavailable",
                    message = error.message ?: "Could not prepare direct rootfs X11 transport",
                    bootId = bootId,
                    fields = mapOf("fallback" to X11GuestTransport.PRIVATE_BIND.journalValue),
                )
                privateBindEndpoint()
            }

    private fun directRootfsEndpoint(rootfs: File): X11DisplayEndpoint {
        val root = rootfs.canonicalFile
        val runtime = File(root, "tmp")
        check(runtime.mkdirs() || runtime.isDirectory) {
            "Could not prepare the rootfs X11 runtime directory"
        }
        val canonicalRuntime = runtime.canonicalFile
        check(canonicalRuntime.toPath().startsWith(root.toPath())) {
            "The rootfs X11 runtime directory escaped the installed image"
        }
        Os.chmod(canonicalRuntime.absolutePath, ROOTFS_TMP_MODE)
        val socketDirectory = File(canonicalRuntime, ".X11-unix")
        check(socketDirectory.mkdirs() || socketDirectory.isDirectory) {
            "Could not prepare the rootfs X11 socket directory"
        }
        val canonicalSocketDirectory = socketDirectory.canonicalFile
        check(canonicalSocketDirectory.toPath().startsWith(root.toPath())) {
            "The rootfs X11 socket directory escaped the installed image"
        }
        // PRoot can leave a mode-000 bind target behind after unmounting it.
        Os.chmod(canonicalSocketDirectory.absolutePath, ROOTFS_TMP_MODE)
        return X11DisplayEndpoint(
            runtimeDirectory = canonicalRuntime,
            socketDirectory = canonicalSocketDirectory,
            transport = X11GuestTransport.DIRECT_ROOTFS,
        )
    }

    private fun privateBindEndpoint(): X11DisplayEndpoint {
        val runtime = X11RuntimePaths.runtimeDirectory(context)
        val socketDirectory = X11RuntimePaths.socketDirectory(context)
        check(socketDirectory.mkdirs() || socketDirectory.isDirectory) {
            "Could not prepare the private X11 socket directory"
        }
        return X11DisplayEndpoint(
            runtimeDirectory = runtime,
            socketDirectory = socketDirectory,
            transport = X11GuestTransport.PRIVATE_BIND,
        )
    }

    private data class StartRequest(
        val endpoint: X11DisplayEndpoint,
        val xkbRoot: File,
        val bootId: String,
    )

    private companion object {
        const val ROOTFS_TMP_MODE = 0x3ff
    }
}

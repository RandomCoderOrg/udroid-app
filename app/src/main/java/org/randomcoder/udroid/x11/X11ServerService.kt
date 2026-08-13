package org.randomcoder.udroid.x11

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class X11ServerService : Service() {
    private val monitorExecutor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "udroid-x11-readiness").apply { isDaemon = true }
        }
    private val startAccepted = AtomicBoolean(false)
    private val serverReady = AtomicBoolean(false)
    @Volatile
    private var activeSocket: File? = null
    private val handler by lazy {
        Handler(mainLooper) { message ->
            when (message.what) {
                X11ServerProtocol.MESSAGE_START -> handleStart(message)
                X11ServerProtocol.MESSAGE_STOP -> handleStop(message)
                X11ServerProtocol.MESSAGE_GET_RENDERER -> handleRendererRequest(message)
                else -> return@Handler false
            }
            true
        }
    }
    private val messenger by lazy { Messenger(handler) }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        if (startAccepted.get()) terminateProcess()
        return false
    }

    override fun onDestroy() {
        monitorExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun handleStart(message: Message) {
        val client = message.replyTo ?: return
        val request = message.data
        val bootId = request.getString(X11ServerProtocol.KEY_BOOT_ID)
        if (message.arg1 != X11ServerProtocol.VERSION) {
            reply(
                client,
                X11ServerProtocol.STATE_FAILED,
                bootId,
                "Unsupported X11 Binder protocol ${message.arg1}",
            )
            return
        }
        if (!startAccepted.compareAndSet(false, true)) {
            val socket = activeSocket ?: X11RuntimePaths.displaySocket(this)
            val probe = X11ProtocolProbe.query(socket)
            reply(
                client,
                if (probe is X11ProtocolProbe.Result.Ready) {
                    X11ServerProtocol.STATE_READY
                } else {
                    X11ServerProtocol.STATE_STARTING
                },
                bootId,
                "X11 server generation already owns this process",
                socket,
            )
            return
        }

        runCatching {
            val runtimeDirectory =
                requireNotNull(request.getString(X11ServerProtocol.KEY_RUNTIME_DIRECTORY)) {
                    "Missing X11 runtime directory"
                }.let(::File)
            val xkbRoot =
                requireNotNull(request.getString(X11ServerProtocol.KEY_XKB_ROOT)) {
                    "Missing XKB data directory"
                }.let(::File)
            prepareRuntime(runtimeDirectory, xkbRoot)
            val socket = File(runtimeDirectory, ".X11-unix/X0")
            activeSocket = socket
            reply(
                client,
                X11ServerProtocol.STATE_STARTING,
                bootId,
                "Starting embedded Termux:X11",
                socket,
            )

            val startedAt = SystemClock.elapsedRealtime()
            check(X11NativeBridge.start(arrayOf(":0", "-nolisten", "tcp"))) {
                "libXlorie rejected the server configuration"
            }
            monitorExecutor.execute {
                monitorSocket(client, bootId, socket, startedAt)
            }
        }.onFailure { error ->
            Log.e(TAG, "Embedded X11 start failed", error)
            reply(
                client,
                X11ServerProtocol.STATE_FAILED,
                bootId,
                error.message ?: error.javaClass.simpleName,
            )
            handler.postDelayed(::terminateProcess, FAILURE_EXIT_DELAY_MILLIS)
        }
    }

    private fun prepareRuntime(
        runtimeDirectory: File,
        xkbRoot: File,
    ) {
        val filesRoot = filesDir.canonicalFile
        val runtime = runtimeDirectory.canonicalFile
        require(
            runtime.path == filesRoot.path ||
                runtime.path.startsWith(filesRoot.path + File.separator),
        ) {
            "X11 runtime directory must stay in app-private storage"
        }
        require(xkbRoot.isDirectory) {
            "XKB data is missing at ${xkbRoot.absolutePath}"
        }
        val socketDirectory = File(runtime, ".X11-unix")
        check(socketDirectory.mkdirs() || socketDirectory.isDirectory) {
            "Could not create the X11 socket directory"
        }
        val staleSocket = File(socketDirectory, "X0")
        check(!staleSocket.exists() || staleSocket.delete()) {
            "Could not remove the stale X11 display socket"
        }

        Os.setenv("TMPDIR", runtime.absolutePath, true)
        Os.setenv("XKB_CONFIG_ROOT", xkbRoot.canonicalPath, true)
        Os.setenv("HOME", filesRoot.absolutePath, true)
    }

    private fun monitorSocket(
        client: Messenger,
        bootId: String?,
        socket: File,
        startedAt: Long,
    ) {
        val deadline = startedAt + SOCKET_READY_TIMEOUT_MILLIS
        var lastFailure = "Display socket did not appear"
        while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline) {
            val probe = X11ProtocolProbe.query(socket)
            if (probe is X11ProtocolProbe.Result.Ready) {
                serverReady.set(true)
                reply(
                    client,
                    X11ServerProtocol.STATE_READY,
                    bootId,
                    "X11 display :0 completed protocol ${probe.protocolMajor}.${probe.protocolMinor} setup",
                    socket,
                    SystemClock.elapsedRealtime() - startedAt,
                )
                return
            }
            if (probe is X11ProtocolProbe.Result.NotReady) {
                lastFailure = probe.reason
            }
            SystemClock.sleep(SOCKET_POLL_MILLIS)
        }
        reply(
            client,
            X11ServerProtocol.STATE_FAILED,
            bootId,
            "X11 display :0 setup failed: $lastFailure",
            socket,
            SystemClock.elapsedRealtime() - startedAt,
        )
        handler.postDelayed(::terminateProcess, FAILURE_EXIT_DELAY_MILLIS)
    }

    private fun handleStop(message: Message) {
        reply(
            message.replyTo,
            X11ServerProtocol.STATE_STOPPING,
            message.data.getString(X11ServerProtocol.KEY_BOOT_ID),
            "Stopping embedded Termux:X11",
        )
        handler.postDelayed(::terminateProcess, STOP_REPLY_DELAY_MILLIS)
    }

    private fun handleRendererRequest(message: Message) {
        val client = message.replyTo ?: return
        val descriptor =
            if (serverReady.get()) {
                X11NativeBridge.getXConnection()
            } else {
                null
            }
        val response =
            Message.obtain(null, X11ServerProtocol.MESSAGE_RENDERER).apply {
                arg1 = X11ServerProtocol.VERSION
                data =
                    Bundle().apply {
                        putString(
                            X11ServerProtocol.KEY_BOOT_ID,
                            message.data.getString(X11ServerProtocol.KEY_BOOT_ID),
                        )
                        if (descriptor != null) {
                            putParcelable(
                                X11ServerProtocol.KEY_RENDERER_FD,
                                descriptor,
                            )
                        } else {
                            putString(
                                X11ServerProtocol.KEY_DETAIL,
                                if (serverReady.get()) {
                                    "X11 server could not allocate a renderer connection"
                                } else {
                                    "X11 server is not protocol-ready"
                                },
                            )
                        }
                    }
            }
        try {
            client.send(response)
        } catch (error: Exception) {
            Log.w(TAG, "Could not return the X11 renderer connection", error)
        } finally {
            runCatching { descriptor?.close() }
                .onFailure { error ->
                    Log.w(TAG, "Could not close the local renderer descriptor", error)
                }
        }
    }

    private fun reply(
        client: Messenger?,
        state: String,
        bootId: String?,
        detail: String,
        socket: File? = null,
        startupMillis: Long? = null,
    ) {
        if (client == null) return
        val data =
            Bundle().apply {
                putString(X11ServerProtocol.KEY_STATE, state)
                putString(X11ServerProtocol.KEY_BOOT_ID, bootId)
                putString(X11ServerProtocol.KEY_DETAIL, detail)
                putString(X11ServerProtocol.KEY_SOCKET_PATH, socket?.absolutePath)
                if (startupMillis != null) {
                    putLong(X11ServerProtocol.KEY_STARTUP_MILLIS, startupMillis)
                }
                putInt(X11ServerProtocol.KEY_PID, Process.myPid())
            }
        runCatching {
            client.send(
                Message.obtain(null, X11ServerProtocol.MESSAGE_STATUS).apply {
                    arg1 = X11ServerProtocol.VERSION
                    this.data = data
                },
            )
        }.onFailure { error ->
            Log.w(TAG, "Could not report embedded X11 state", error)
        }
    }

    private fun terminateProcess() {
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val TAG = "UdroidX11Server"
        const val SOCKET_READY_TIMEOUT_MILLIS = 8_000L
        const val SOCKET_POLL_MILLIS = 25L
        const val FAILURE_EXIT_DELAY_MILLIS = 250L
        const val STOP_REPLY_DELAY_MILLIS = 100L
    }
}

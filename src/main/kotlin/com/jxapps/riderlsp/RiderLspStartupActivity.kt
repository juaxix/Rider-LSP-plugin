// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RiderLspStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = LspSettings.getInstance()
        if (!settings.enabled) return

        val disposable = Disposer.newDisposable("RiderLspServer")
        Disposer.register(project as Disposable, disposable)

        val serverManager = LspServerManager(project, settings.port)
        serverManager.start()

        Disposer.register(disposable, Disposable { serverManager.stop() })
    }
}

class LspServerManager(
    private val project: Project,
    private val port: Int
) {
    private val log = Logger.getInstance(LspServerManager::class.java)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "RiderLspServer").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeClient: Socket? = null

    @Volatile
    private var activeLauncher: Future<Void>? = null

    @Volatile
    private var running = true

    fun start() {
        executor.submit {
            try {
                val bindAddressStr = LspSettings.getInstance().bindAddress
                val bindAddress = try {
                    InetAddress.getByName(bindAddressStr)
                } catch (e: Exception) {
                    log.warn("Invalid bind address: $bindAddressStr, falling back to 127.0.0.1")
                    InetAddress.getByName("127.0.0.1")
                }

                // Set SO_REUSEADDR to allow quick restart
                serverSocket = ServerSocket(port, 50, bindAddress).apply {
                    reuseAddress = true
                }
                log.info("Rider LSP server listening on $bindAddressStr:$port")

                while (running) {
                    acceptClient()
                }
            } catch (e: Exception) {
                if (running) {
                    log.error("LSP server error", e)
                }
            }
        }
    }

    private fun acceptClient() {
        val socket = try {
            val ss = serverSocket
            if (ss == null) {
                log.warn("ServerSocket is null, skipping accept")
                return
            }

            // Set timeout to allow checking 'running' flag periodically
            ss.soTimeout = 5000
            ss.accept()
        } catch (e: java.net.SocketTimeoutException) {
            // Timeout is expected, allows checking running flag
            return
        } catch (e: java.net.SocketException) {
            // Socket closed or other error
            if (running) log.info("Socket exception during accept: ${e.message}")
            return
        } catch (e: Exception) {
            if (running) log.warn("Accept failed", e)
            return
        }

        log.info("LSP client connected from ${socket.remoteSocketAddress}")

        // Disconnect previous client
        disconnectActiveClient()

        activeClient = socket

        try {
            val server = RiderLspServer(project)
            val launcher = Launcher.createLauncher(
                server,
                LanguageClient::class.java,
                socket.getInputStream(),
                socket.getOutputStream(),
                executor,
                null
            )
            server.connect(launcher.remoteProxy)
            activeLauncher = launcher.startListening()
            // Wait with timeout to prevent indefinite blocking
            try {
                activeLauncher?.get(30, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                log.info("LSP client timeout after 30 seconds")
            }
        } catch (e: Exception) {
            if (running) log.info("LSP client disconnected: ${e.message}")
        } finally {
            disconnectActiveClient()
        }
    }

    @Synchronized
    private fun disconnectActiveClient() {
        try {
            activeLauncher?.cancel(true)
            activeClient?.close()
        } catch (_: Exception) {
        }
        activeLauncher = null
        activeClient = null
    }

    fun stop() {
        running = false
        disconnectActiveClient()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        // Properly shutdown executor
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate within 5 seconds, forcing shutdown")
                executor.shutdownNow()
                executor.awaitTermination(2, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            log.warn("Interrupted while waiting for executor termination")
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        log.info("Rider LSP server stopped")
    }
}

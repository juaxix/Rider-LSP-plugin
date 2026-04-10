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
                val bindAddress = InetAddress.getByName("127.0.0.1")
                serverSocket = ServerSocket(port, 1, bindAddress)
                log.info("Rider LSP server listening on 127.0.0.1:$port")

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
            serverSocket?.accept() ?: return
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
            activeLauncher?.get() // blocks until client disconnects
        } catch (e: Exception) {
            if (running) log.info("LSP client disconnected: ${e.message}")
        } finally {
            disconnectActiveClient()
        }
    }

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
        executor.shutdownNow()
        log.info("Rider LSP server stopped")
    }
}

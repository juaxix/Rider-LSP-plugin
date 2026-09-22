// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.util.concurrent.CompletableFuture

/**
 * One LSP server instance per connected client. Registered as a child of the session
 * disposable so [dispose] runs when the connection goes away, whether or not the client
 * followed the protocol and sent `shutdown`.
 */
class RiderLspServer(
    private val project: Project,
    private val sessionDisposable: Disposable,
    private val isClientAlive: () -> Boolean,
    private val onExit: () -> Unit
) : LanguageServer, LanguageClientAware, Disposable {

    private val log = Logger.getInstance(RiderLspServer::class.java)

    @Volatile
    private var client: LanguageClient? = null

    @Volatile
    private var diagnosticsPublisher: DiagnosticsPublisher? = null

    private val textDocumentService = RiderLspTextDocumentService(project, sessionDisposable)
    private val workspaceService = RiderLspWorkspaceService(project, sessionDisposable)

    init {
        Disposer.register(sessionDisposable, this)
    }

    override fun connect(client: LanguageClient?) {
        requireNotNull(client) { "Client must not be null" }
        this.client = client
        diagnosticsPublisher = DiagnosticsPublisher(
            project = project,
            client = client,
            parentDisposable = sessionDisposable,
            isClientAlive = isClientAlive,
            clientFiles = { textDocumentService.trackedFiles() }
        )
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        log.info("LSP initialize from: ${params.clientInfo?.name ?: "unknown"}")

        val capabilities = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncOptions().apply {
                openClose = true
                change = TextDocumentSyncKind.None // read-only, no incremental sync
            })

            definitionProvider = Either.forLeft(true)
            declarationProvider = Either.forLeft(true)
            typeDefinitionProvider = Either.forLeft(true)
            implementationProvider = Either.forLeft(true)
            referencesProvider = Either.forLeft(true)
            hoverProvider = Either.forLeft(true)

            documentSymbolProvider = Either.forLeft(true)
            workspaceSymbolProvider = Either.forLeft(true)

            completionProvider = CompletionOptions().apply {
                triggerCharacters = listOf(".", ":", ">", "<")
                resolveProvider = true
            }
        }

        val serverInfo = ServerInfo("RiderLspServer", pluginVersion())
        return CompletableFuture.completedFuture(InitializeResult(capabilities, serverInfo))
    }

    override fun initialized(params: InitializedParams) {
        log.info("LSP client initialized")
        diagnosticsPublisher?.start()
    }

    override fun shutdown(): CompletableFuture<Any> {
        log.info("LSP shutdown requested")
        diagnosticsPublisher?.stop()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        log.info("LSP exit")
        onExit()
    }

    override fun dispose() {
        diagnosticsPublisher?.stop()
        diagnosticsPublisher = null
        workspaceService.dispose()
        textDocumentService.dispose()
        client = null
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    private fun pluginVersion(): String =
        try {
            PluginManagerCore.getPlugin(PluginId.getId("com.jxapps.riderlsp"))?.version ?: "unknown"
        } catch (e: Throwable) {
            "unknown"
        }
}

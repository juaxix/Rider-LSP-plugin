// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.util.concurrent.CompletableFuture

class RiderLspServer(
    private val project: Project
) : LanguageServer, LanguageClientAware {

    private val log = Logger.getInstance(RiderLspServer::class.java)

    @Volatile
    private var client: LanguageClient? = null

    @Volatile
    private var diagnosticsPublisher: DiagnosticsPublisher? = null

    private val textDocumentService = RiderLspTextDocumentService(project, this)
    private val workspaceService = RiderLspWorkspaceService(project)

    override fun connect(client: LanguageClient?) {
        require(client != null) { "Client must not be null" }
        this.client = client
        diagnosticsPublisher = DiagnosticsPublisher(project, client)
        log.info("LSP client connected and diagnostics publisher initialized")
    }

    fun getClient(): LanguageClient? = client

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        log.info("LSP initialize from: ${params.clientInfo?.name ?: "unknown"}")

        // Ensure client is connected before proceeding
        require(client != null) { "connect() must be called before initialize()" }

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

            signatureHelpProvider = null // deferred
        }

        val serverInfo = ServerInfo("RiderLspServer", "1.0.5")
        return CompletableFuture.completedFuture(InitializeResult(capabilities, serverInfo))
    }

    override fun initialized(params: InitializedParams) {
        log.info("LSP client initialized")
        val publisher = diagnosticsPublisher
        if (publisher != null) {
            publisher.start()
        } else {
            log.error("DiagnosticsPublisher not initialized - connect() may not have been called")
        }
    }

    override fun shutdown(): CompletableFuture<Any> {
        log.info("LSP shutdown requested")
        try {
            diagnosticsPublisher?.stop()
        } catch (e: Exception) {
            log.warn("Error stopping diagnostics publisher", e)
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        log.info("LSP exit")
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService
}

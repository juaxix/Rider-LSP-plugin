// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.MessageBusConnection
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pushes Rider's highlighting results to the connected LSP client whenever the daemon finishes.
 *
 * The message-bus subscription is bound to the client session's disposable, so a client that
 * vanishes without `shutdown` cannot leave a listener behind. Work is done on a pooled thread
 * (never on the EDT) and consecutive daemon passes are coalesced.
 */
class DiagnosticsPublisher(
    private val project: Project,
    private val client: LanguageClient,
    private val parentDisposable: Disposable,
    private val isClientAlive: () -> Boolean,
    private val clientFiles: () -> Collection<VirtualFile>
) {
    private val log = Logger.getInstance(DiagnosticsPublisher::class.java)

    @Volatile
    private var connection: MessageBusConnection? = null

    @Volatile
    private var stopped = false

    private val publishScheduled = AtomicBoolean(false)

    @Synchronized
    fun start() {
        if (stopped || connection != null) return
        if (project.isDisposed) return

        val bus = try {
            project.messageBus.connect(parentDisposable)
        } catch (e: Exception) {
            // Session already disposed
            log.debug("Cannot subscribe, session disposed: ${e.message}")
            return
        }
        connection = bus
        bus.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished() {
                schedulePublish()
            }
        })
        log.info("DiagnosticsPublisher started")
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            log.debug("Error disconnecting message bus: ${e.message}")
        } finally {
            connection = null
        }
        log.info("DiagnosticsPublisher stopped")
    }

    private fun schedulePublish() {
        if (stopped) return
        if (!publishScheduled.compareAndSet(false, true)) return
        try {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    publishAll()
                } finally {
                    publishScheduled.set(false)
                }
            }
        } catch (e: Exception) {
            publishScheduled.set(false)
        }
    }

    private fun publishAll() {
        if (stopped || project.isDisposed) return
        if (!isClientAlive()) {
            stop()
            return
        }

        val files = LinkedHashSet<VirtualFile>()
        try {
            files.addAll(FileEditorManager.getInstance(project).openFiles)
        } catch (e: Exception) {
            log.debug("Could not list open editors: ${e.message}")
        }
        files.addAll(clientFiles())

        for (file in files) {
            if (stopped || project.isDisposed || !isClientAlive()) return

            val params = try {
                runReadAction { buildParams(file) }
            } catch (_: ProcessCanceledException) {
                null
            } catch (e: Throwable) {
                log.debug("Error collecting diagnostics for ${file.path}: ${e.message}")
                null
            } ?: continue

            try {
                client.publishDiagnostics(params)
            } catch (e: Exception) {
                log.info("LSP client unreachable while publishing diagnostics, stopping publisher: ${e.message}")
                stop()
                return
            }
        }
    }

    private fun buildParams(file: VirtualFile): PublishDiagnosticsParams? {
        if (!file.isValid) return null
        // Only documents that are already loaded can have highlighting; never force-load one here.
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null

        @Suppress("UnstableApiUsage")
        val highlights: List<HighlightInfo> = try {
            DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.INFORMATION, project)
        } catch (e: Throwable) {
            // Internal API changed in this IDE build: disable diagnostics rather than spam the log.
            log.warn("Highlight access unavailable in this IDE build, diagnostics disabled: ${e.javaClass.simpleName}")
            stop()
            return null
        }

        val text = document.immutableCharSequence
        val diagnostics = highlights.mapNotNull { toDiagnostic(it, text) }
        return PublishDiagnosticsParams(LspTranslator.virtualFileToUri(file), diagnostics)
    }

    private fun toDiagnostic(info: HighlightInfo, text: CharSequence): Diagnostic? {
        val message = info.description ?: return null
        val startOffset = info.startOffset.coerceIn(0, text.length)
        val endOffset = info.endOffset.coerceIn(startOffset, text.length)

        val range = LspTranslator.offsetsToRange(text, startOffset, endOffset)

        val severity = when {
            info.severity >= HighlightSeverity.ERROR -> DiagnosticSeverity.Error
            info.severity >= HighlightSeverity.WARNING -> DiagnosticSeverity.Warning
            info.severity >= HighlightSeverity.WEAK_WARNING -> DiagnosticSeverity.Information
            else -> DiagnosticSeverity.Hint
        }

        return Diagnostic(range, message, severity, "rider")
    }
}

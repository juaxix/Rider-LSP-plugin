// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient

class DiagnosticsPublisher(
    private val project: Project,
    private val client: LanguageClient
) {
    private val log = Logger.getInstance(DiagnosticsPublisher::class.java)

    @Volatile
    private var connection: com.intellij.util.messages.MessageBusConnection? = null

    fun start() {
        val bus = project.messageBus.connect()
        connection = bus

        bus.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished() {
                publishAllOpenFiles()
            }
        })

        log.info("DiagnosticsPublisher started")
    }

    fun stop() {
        connection?.disconnect()
        connection = null
        log.info("DiagnosticsPublisher stopped")
    }

    private fun publishAllOpenFiles() {
        try {
            val editors = FileEditorManager.getInstance(project).openFiles
            for (file in editors) {
                ReadAction.run<Throwable> {
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@run
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@run

                    val highlights = DaemonCodeAnalyzerImpl.getHighlights(
                        document, HighlightSeverity.INFORMATION, project
                    )

                    val diagnostics = highlights.mapNotNull { info ->
                        toDiagnostic(info, document)
                    }

                    val params = PublishDiagnosticsParams(
                        LspTranslator.virtualFileToUri(file),
                        diagnostics
                    )

                    client.publishDiagnostics(params)
                }
            }
        } catch (e: Exception) {
            log.debug("Error publishing diagnostics: ${e.message}")
        }
    }

    private fun toDiagnostic(info: HighlightInfo, document: Document): Diagnostic? {
        val message = info.description ?: return null
        val startOffset = info.startOffset.coerceIn(0, document.textLength)
        val endOffset = info.endOffset.coerceIn(startOffset, document.textLength)

        val range = LspTranslator.textRangeToRange(document, TextRange(startOffset, endOffset))

        val severity = when {
            info.severity >= HighlightSeverity.ERROR -> DiagnosticSeverity.Error
            info.severity >= HighlightSeverity.WARNING -> DiagnosticSeverity.Warning
            info.severity >= HighlightSeverity.WEAK_WARNING -> DiagnosticSeverity.Information
            else -> DiagnosticSeverity.Hint
        }

        return Diagnostic(range, message, severity, "rider")
    }
}

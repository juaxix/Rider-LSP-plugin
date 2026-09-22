// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.codeInsight.completion.CompletionService
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.Consumer
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.Position

/**
 * Runs the IDE's completion machinery against a temporary, hidden editor.
 * Must be called from a background thread that does NOT hold a read action.
 */
@Suppress("UnstableApiUsage")
object CompletionBridge {

    private val log = Logger.getInstance(CompletionBridge::class.java)
    private const val MAX_ITEMS = 100

    fun getCompletions(project: Project, uri: String, position: Position): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()

        try {
            val virtualFile = LspTranslator.uriToVirtualFile(uri) ?: return items
            // Completion needs a real Document/Editor; the file being completed is normally open anyway.
            val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return items

            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait
                try {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@invokeAndWait
                    val offset = LspTranslator.positionToOffset(document.immutableCharSequence, position)

                    val editor = EditorFactory.getInstance().createEditor(document, project)
                    try {
                        editor.caretModel.moveToOffset(offset)

                        val psiElement = psiFile.findElementAt(offset)
                            ?: psiFile.findElementAt((offset - 1).coerceAtLeast(0))

                        if (psiElement != null) {
                            val params = createCompletionParameters(psiFile, psiElement, offset, editor)
                            if (params != null) {
                                performCompletion(params, items)
                            }
                        }
                    } finally {
                        EditorFactory.getInstance().releaseEditor(editor)
                    }
                } catch (e: Exception) {
                    log.debug("Completion error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            log.warn("CompletionBridge error", e)
        }

        return items.take(MAX_ITEMS)
    }

    private fun createCompletionParameters(
        psiFile: PsiFile,
        position: PsiElement,
        offset: Int,
        editor: Editor
    ): CompletionParameters? {
        // The 7-argument constructor (position, originalFile, type, offset, invocationCount, editor, process)
        // is not part of the stable API, so pick it by shape and go through reflection.
        return try {
            val ctor = CompletionParameters::class.java.declaredConstructors
                .firstOrNull { it.parameterCount == 7 } ?: return null
            ctor.isAccessible = true
            val process = object : CompletionProcess {
                override fun isAutopopupCompletion(): Boolean = false
            }
            ctor.newInstance(position, psiFile, CompletionType.BASIC, offset, 1, editor, process) as CompletionParameters
        } catch (e: Throwable) {
            log.debug("Failed to create CompletionParameters via reflection: ${e.message}")
            null
        }
    }

    private fun performCompletion(parameters: CompletionParameters, items: MutableList<CompletionItem>) {
        try {
            val service = CompletionService.getCompletionService() ?: return
            service.performCompletion(parameters, Consumer { result ->
                if (items.size < MAX_ITEMS) {
                    lookupToCompletionItem(result.lookupElement)?.let { items.add(it) }
                }
            })
        } catch (e: Exception) {
            log.debug("performCompletion failed: ${e.message}")
        }
    }

    private fun lookupToCompletionItem(element: LookupElement): CompletionItem? {
        val label = element.lookupString
        if (label.isBlank()) return null

        val presentation = LookupElementPresentation()
        element.renderElement(presentation)

        return CompletionItem(label).apply {
            kind = guessCompletionKind(presentation)
            detail = presentation.typeText
            insertText = label

            val tailText = presentation.tailText
            if (!tailText.isNullOrBlank()) {
                labelDetails = CompletionItemLabelDetails().apply {
                    this.detail = tailText
                }
            }
        }
    }

    private fun guessCompletionKind(presentation: LookupElementPresentation): CompletionItemKind {
        val typeText = presentation.typeText ?: ""
        val tailText = presentation.tailText ?: ""

        return when {
            typeText.contains("class", ignoreCase = true) -> CompletionItemKind.Class
            typeText.contains("struct", ignoreCase = true) -> CompletionItemKind.Struct
            typeText.contains("enum", ignoreCase = true) -> CompletionItemKind.Enum
            tailText.contains("(") -> CompletionItemKind.Function
            typeText.contains("namespace", ignoreCase = true) -> CompletionItemKind.Module
            else -> CompletionItemKind.Text
        }
    }
}

// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.codeInsight.completion.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("UnstableApiUsage")
object CompletionBridge {

    private val log = Logger.getInstance(CompletionBridge::class.java)

    fun getCompletions(project: Project, uri: String, position: Position): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()

        try {
            val virtualFile = LspTranslator.uriToVirtualFile(uri) ?: return items
            val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return items
            val offset = LspTranslator.positionToOffset(document, position)

            val latch = CountDownLatch(1)

            ApplicationManager.getApplication().invokeAndWait {
                try {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: run {
                        latch.countDown()
                        return@invokeAndWait
                    }

                    val editor = EditorFactory.getInstance().createEditor(document, project)
                    try {
                        editor.caretModel.moveToOffset(offset)

                        val psiElement = psiFile.findElementAt(offset)
                            ?: psiFile.findElementAt(offset - 1)

                        if (psiElement != null) {
                            val params = createCompletionParameters(
                                psiFile, psiElement, offset, editor
                            )
                            if (params != null) {
                                performCompletion(params, items)
                            }
                        }
                    } finally {
                        EditorFactory.getInstance().releaseEditor(editor)
                    }
                } catch (e: Exception) {
                    log.debug("Completion error: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }

            latch.await(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.warn("CompletionBridge error", e)
        }

        return items.take(100)
    }

    private fun createCompletionParameters(
        psiFile: PsiFile,
        position: PsiElement,
        offset: Int,
        editor: Editor
    ): CompletionParameters? {
        return try {
            // CompletionParameters constructor is package-private; use reflection
            val ctor = CompletionParameters::class.java.declaredConstructors.first()
            ctor.isAccessible = true
            // Constructor signature: (PsiElement position, PsiFile originalFile, CompletionType type, int offset, int invocationCount, Editor editor, CompletionProcess process)
            ctor.newInstance(
                position, psiFile, CompletionType.BASIC, offset, 1, editor, null
            ) as CompletionParameters
        } catch (e: Exception) {
            log.debug("Failed to create CompletionParameters via reflection: ${e.message}")
            null
        }
    }

    private fun performCompletion(parameters: CompletionParameters, items: MutableList<CompletionItem>) {
        try {
            val service = CompletionService.getCompletionService() ?: return
            service.performCompletion(parameters, Consumer { result ->
                val element = result.lookupElement
                val item = lookupToCompletionItem(element)
                if (item != null && items.size < 100) {
                    items.add(item)
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

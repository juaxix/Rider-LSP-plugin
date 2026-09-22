// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RiderLspTextDocumentService(
    private val project: Project,
    private val expireWith: Disposable
) : TextDocumentService {

    private val log = Logger.getInstance(RiderLspTextDocumentService::class.java)
    private val openDocuments = ConcurrentHashMap<String, VirtualFile>()

    /** Files the client has opened; used by the diagnostics publisher. */
    fun trackedFiles(): Collection<VirtualFile> = openDocuments.values.toList()

    fun dispose() {
        openDocuments.clear()
    }

    // --- Document tracking ---

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val file = LspTranslator.uriToVirtualFile(uri)
        if (file != null) {
            openDocuments[uri] = file
            log.debug("Opened: $uri")
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        openDocuments.remove(params.textDocument.uri)
        log.debug("Closed: ${params.textDocument.uri}")
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // Read-only server: changes are picked up through the VFS
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // No action needed
    }

    // --- Definition ---

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return computeInSmartMode("definition") {
            val locations = mutableListOf<Location>()
            val ref = findReferenceAt(params.textDocument.uri, params.position)
            val resolved = ref?.resolve()
            if (resolved != null) {
                LspTranslator.psiElementToLocation(resolved)?.let { locations.add(it) }
            } else {
                // Fallback: the element under the caret may itself be a declaration
                val element = findElementAt(params.textDocument.uri, params.position)
                val parent = element?.parent
                if (parent is PsiNamedElement) {
                    LspTranslator.psiElementToLocation(parent)?.let { locations.add(it) }
                }
            }
            Either.forLeft(locations)
        }
    }

    // --- Declaration ---

    override fun declaration(params: DeclarationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return computeInSmartMode("declaration") {
            val locations = mutableListOf<Location>()
            val resolved = findReferenceAt(params.textDocument.uri, params.position)?.resolve()
            if (resolved != null) {
                LspTranslator.psiElementToLocation(resolved)?.let { locations.add(it) }
            }
            Either.forLeft(locations)
        }
    }

    // --- Type Definition ---

    override fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return computeInSmartMode("typeDefinition") {
            val locations = mutableListOf<Location>()
            val ref = findReferenceAt(params.textDocument.uri, params.position)
            val element = ref?.resolve() ?: findElementAt(params.textDocument.uri, params.position)
            if (element != null) {
                LspTranslator.psiElementToLocation(element)?.let { locations.add(it) }
            }
            Either.forLeft(locations)
        }
    }

    // --- Implementation ---

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return computeInSmartMode("implementation") {
            val locations = mutableListOf<Location>()
            val resolved = findReferenceAt(params.textDocument.uri, params.position)?.resolve()
            if (resolved != null) {
                LspTranslator.psiElementToLocation(resolved)?.let { locations.add(it) }
            }
            Either.forLeft(locations)
        }
    }

    // --- References ---

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return computeInSmartMode("references") {
            val locations = mutableListOf<Location>()
            val element = resolveElementAt(params.textDocument.uri, params.position)
                ?: return@computeInSmartMode locations

            val scope = ProjectScope.getProjectScope(project)
            // Stream results and stop early instead of materialising every reference first.
            ReferencesSearch.search(element, scope).forEach(Processor { ref ->
                LspTranslator.psiElementToLocation(ref.element)?.let { locations.add(it) }
                locations.size < MAX_REFERENCES
            })
            locations
        }
    }

    // --- Hover ---

    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        return computeInSmartMode("hover") {
            val element = findElementAt(params.textDocument.uri, params.position)
            val named = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)

            if (named != null) {
                val docComment = findDocComment(named)
                val signature = extractSignature(named)

                val markdown = buildString {
                    append("```cpp\n")
                    append(signature)
                    append("\n```")
                    if (docComment.isNotBlank()) {
                        append("\n\n---\n\n")
                        append(docComment)
                    }
                }

                Hover(MarkupContent("markdown", markdown))
            } else {
                Hover(MarkupContent("markdown", ""))
            }
        }
    }

    /** First line of the element's text, without materialising the whole element text. */
    private fun extractSignature(element: PsiNamedElement): String {
        return try {
            val psiFile = element.containingFile ?: return element.name ?: "unknown"
            val text = LspTranslator.psiFileText(psiFile)
            val range = element.textRange ?: return element.name ?: "unknown"
            val start = range.startOffset.coerceIn(0, text.length)
            var end = start
            val limit = minOf(range.endOffset, start + MAX_SIGNATURE_LENGTH, text.length)
            while (end < limit && text[end] != '\n' && text[end] != '\r') end++
            val signature = text.subSequence(start, end).toString()
            if (end < range.endOffset && end - start >= MAX_SIGNATURE_LENGTH) "$signature..." else signature
        } catch (e: Exception) {
            element.name ?: "unknown"
        }
    }

    // --- Document Symbols ---

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        return computeInSmartMode("documentSymbol") {
            val result = mutableListOf<Either<SymbolInformation, DocumentSymbol>>()
            val psiFile = getPsiFile(params.textDocument.uri) ?: return@computeInSmartMode result
            val text = LspTranslator.psiFileText(psiFile)

            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (result.size >= MAX_DOCUMENT_SYMBOLS) return
                    if (element is PsiNamedElement) {
                        val name = element.name
                        if (!name.isNullOrEmpty()) {
                            val kind = LspTranslator.psiElementToSymbolKind(element)
                            val range = LspTranslator.textRangeToRange(text, element.textRange)
                            val nameRange = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.let {
                                LspTranslator.textRangeToRange(text, it.textRange)
                            } ?: range
                            result.add(Either.forRight(DocumentSymbol(name, kind, range, nameRange)))
                        }
                    }
                    super.visitElement(element)
                }
            })

            result
        }
    }

    // --- Completion ---

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val future = CompletableFuture<Either<List<CompletionItem>, CompletionList>>()
        // Completion needs the EDT (it creates a temporary editor). It must not be entered while this
        // thread holds a read action, otherwise invokeAndWait can deadlock against a pending write action.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                DumbService.getInstance(project).waitForSmartMode()
                val items = CompletionBridge.getCompletions(project, params.textDocument.uri, params.position)
                future.complete(Either.forRight(CompletionList(false, items)))
            } catch (e: Throwable) {
                log.warn("Error in completion", e)
                future.completeExceptionally(e)
            }
        }
        return future.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        return CompletableFuture.completedFuture(unresolved)
    }

    // --- Helpers ---

    private fun getPsiFile(uri: String): PsiFile? {
        val file = LspTranslator.uriToVirtualFile(uri) ?: return null
        return PsiManager.getInstance(project).findFile(file)
    }

    private fun findElementAt(uri: String, position: Position): PsiElement? {
        val psiFile = getPsiFile(uri) ?: return null
        val offset = LspTranslator.positionToOffset(LspTranslator.psiFileText(psiFile), position)
        return psiFile.findElementAt(offset)
    }

    private fun findReferenceAt(uri: String, position: Position): PsiReference? {
        val psiFile = getPsiFile(uri) ?: return null
        val offset = LspTranslator.positionToOffset(LspTranslator.psiFileText(psiFile), position)
        return psiFile.findReferenceAt(offset)
    }

    private fun resolveElementAt(uri: String, position: Position): PsiElement? {
        val ref = findReferenceAt(uri, position)
        if (ref != null) return ref.resolve()

        val element = findElementAt(uri, position)
        return PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)
    }

    private fun findDocComment(element: PsiElement): String {
        var prev = element.prevSibling
        while (prev != null && prev is PsiWhiteSpace) {
            prev = prev.prevSibling
        }
        if (prev is PsiComment) {
            return LspTranslator.htmlToMarkdown(prev.text)
        }
        return ""
    }

    private fun <T> computeInSmartMode(label: String, action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        try {
            // Blocks the lsp4j message thread until indexing is done, then runs [action] inside a read action.
            val result = ReadAction.nonBlocking(Callable { action() })
                .inSmartMode(project)
                .expireWith(expireWith)
                .executeSynchronously()
            future.complete(result)
        } catch (e: ProcessCanceledException) {
            future.completeExceptionally(e)
        } catch (e: Throwable) {
            log.warn("Error in $label", e)
            future.completeExceptionally(e)
        }
        return future.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 30L
        const val MAX_REFERENCES = 200
        const val MAX_DOCUMENT_SYMBOLS = 500
        const val MAX_SIGNATURE_LENGTH = 200
    }
}

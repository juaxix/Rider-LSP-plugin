// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RiderLspTextDocumentService(
    private val project: Project,
    private val server: RiderLspServer
) : TextDocumentService {

    private val log = Logger.getInstance(RiderLspTextDocumentService::class.java)
    private val openDocuments = mutableMapOf<String, VirtualFile>()

    // --- Document tracking ---

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val file = LspTranslator.uriToVirtualFile(uri)
        if (file != null) {
            synchronized(openDocuments) { openDocuments[uri] = file }
            log.info("Opened: $uri")
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        synchronized(openDocuments) { openDocuments.remove(uri) }
        log.info("Closed: $uri")
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // Read-only server — changes are picked up through VFS
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
                // Fallback: try navigation targets from the element itself
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
            val ref = findReferenceAt(params.textDocument.uri, params.position)
            val resolved = ref?.resolve()
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
            val element = findElementAt(params.textDocument.uri, params.position)
            val named = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
            if (named != null) {
                // Try to resolve the type of the element
                LspTranslator.psiElementToLocation(named)?.let { locations.add(it) }
            }
            Either.forLeft(locations)
        }
    }

    // --- Implementation ---

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return computeInSmartMode("implementation") {
            val locations = mutableListOf<Location>()
            val ref = findReferenceAt(params.textDocument.uri, params.position)
            val resolved = ref?.resolve()
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
            val element = resolveElementAt(params.textDocument.uri, params.position) ?: return@computeInSmartMode locations

            val scope = ProjectScope.getProjectScope(project)
            val refs = ReferencesSearch.search(element, scope).findAll()
            for (ref in refs) {
                val refElement = ref.element
                LspTranslator.psiElementToLocation(refElement)?.let { locations.add(it) }
                if (locations.size >= 200) break
            }
            locations
        }
    }

    // --- Hover ---

    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        return computeInSmartMode("hover") {
            val element = findElementAt(params.textDocument.uri, params.position)
            val named = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)

            if (named != null) {
                val docComment = findDocComment(named)
                val signature = named.text?.let { text ->
                    // Take first few lines as signature
                    text.lines().take(5).joinToString("\n")
                } ?: named.name ?: ""

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

    // --- Document Symbols ---

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        return computeInSmartMode("documentSymbol") {
            val result = mutableListOf<Either<SymbolInformation, DocumentSymbol>>()
            val file = LspTranslator.uriToVirtualFile(params.textDocument.uri) ?: return@computeInSmartMode result
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@computeInSmartMode result
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@computeInSmartMode result

            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is PsiNamedElement && element.name != null) {
                        val name = element.name ?: return
                        val kind = LspTranslator.psiElementToSymbolKind(element)
                        val range = LspTranslator.textRangeToRange(document, element.textRange)
                        val nameRange = if (element is PsiNameIdentifierOwner) {
                            element.nameIdentifier?.let {
                                LspTranslator.textRangeToRange(document, it.textRange)
                            } ?: range
                        } else {
                            range
                        }

                        val symbol = DocumentSymbol(name, kind, range, nameRange)
                        result.add(Either.forRight(symbol))
                    }
                    super.visitElement(element)
                }
            })

            result.take(500)
        }
    }

    // --- Completion ---

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        return computeInSmartMode("completion") {
            val items = CompletionBridge.getCompletions(project, params.textDocument.uri, params.position)
            Either.forRight(CompletionList(false, items))
        }
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        return CompletableFuture.completedFuture(unresolved)
    }

    // --- Helpers ---

    private fun getDocumentAndPsiFile(uri: String): Pair<Document, PsiFile>? {
        val file = LspTranslator.uriToVirtualFile(uri) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        return document to psiFile
    }

    private fun findElementAt(uri: String, position: Position): PsiElement? {
        val (document, psiFile) = getDocumentAndPsiFile(uri) ?: return null
        val offset = LspTranslator.positionToOffset(document, position)
        return psiFile.findElementAt(offset)
    }

    private fun findReferenceAt(uri: String, position: Position): PsiReference? {
        val (document, psiFile) = getDocumentAndPsiFile(uri) ?: return null
        val offset = LspTranslator.positionToOffset(document, position)
        return psiFile.findReferenceAt(offset)
    }

    private fun resolveElementAt(uri: String, position: Position): PsiElement? {
        val ref = findReferenceAt(uri, position)
        if (ref != null) return ref.resolve()

        val element = findElementAt(uri, position)
        return PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
    }

    private fun findDocComment(element: PsiElement): String {
        // Look for doc comments preceding the element
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

        DumbService.getInstance(project).runReadActionInSmartMode {
            try {
                val result = ReadAction.compute<T, Throwable> { action() }
                future.complete(result)
            } catch (e: Exception) {
                log.warn("Error in $label", e)
                future.completeExceptionally(e)
            }
        }

        return future.orTimeout(30, TimeUnit.SECONDS)
    }
}

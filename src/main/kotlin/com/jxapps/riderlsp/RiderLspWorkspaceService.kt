// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * `workspace/symbol` for one client session.
 *
 * Rider's C++ symbol index lives in the ReSharper backend and is reached through its
 * "goto" sessions. Those sessions are bound to [sessionDisposable]: they are shared for the
 * lifetime of the client connection (so repeated queries stay fast) and released the moment
 * the connection ends. Binding them to the project, as older versions did, kept every
 * result cache alive until the project closed.
 */
class RiderLspWorkspaceService(
    private val project: Project,
    private val sessionDisposable: Disposable
) : WorkspaceService {

    private val log = Logger.getInstance(RiderLspWorkspaceService::class.java)
    private val maxResults = 100

    private val cachedSessions = ConcurrentHashMap<String, Any>()

    @Volatile
    private var disposed = false

    fun dispose() {
        disposed = true
        cachedSessions.clear()
    }

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        val query = params.query
        if (query.isNullOrBlank()) {
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }

        val future = CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>>()

        // RD protocol calls MUST NOT run inside a ReadAction: they call back into the backend
        // and can deadlock if the read lock is held. Run on a pooled thread instead.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val results = collectSymbols(query.trim())
                log.info("workspace/symbol query='$query' returned ${results.size} results")
                future.complete(Either.forLeft(results))
            } catch (e: Exception) {
                log.warn("Error in workspace/symbol for query='$query'", e)
                future.complete(Either.forLeft(emptyList()))
            }
        }

        return future.orTimeout(120, TimeUnit.SECONDS)
    }

    private fun collectSymbols(query: String): List<SymbolInformation> {
        val symbols = mutableListOf<SymbolInformation>()
        val seen = mutableSetOf<String>()

        // Strategy 1: Rider backend goto sessions (types first, then all symbols)
        collectViaRdDotnetService(query, "GotoType", symbols, seen)
        if (symbols.size < maxResults) {
            collectViaRdDotnetService(query, "GotoSymbol", symbols, seen)
        }

        // Strategy 2: ChooseByNameContributorEx, catches symbols the backend session did not return
        if (symbols.size < maxResults) {
            collectViaProtocolContributorEx(query, symbols, seen)
        }

        // Strategy 3: header text search for class/struct declarations (engine types outside the index)
        if (symbols.size < maxResults && symbols.none { it.name == query }) {
            collectViaFileSearch(query, symbols, seen)
        }

        return symbols
    }

    // ------------------------------------------------------------------------------------------
    // Strategy 1: RdDotnetGotoService (reflection, degrades gracefully when Rider internals change)
    // ------------------------------------------------------------------------------------------

    private fun collectViaRdDotnetService(
        query: String,
        kindName: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        if (disposed || project.isDisposed) return

        try {
            val serviceClass = Class.forName("com.jetbrains.rider.globalNavigation.RdDotnetGotoService")
            val service = serviceClass.methods
                .firstOrNull { it.name == "getInstance" && it.parameterCount == 1 && java.lang.reflect.Modifier.isStatic(it.modifiers) }
                ?.invoke(null, project)
                ?: run {
                    val companion = serviceClass.getDeclaredField("Companion").get(null)
                    companion.javaClass.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 1 }
                        ?.invoke(companion, project)
                }
                ?: return

            val gotoKindClass = Class.forName("com.jetbrains.rd.ide.model.GotoKind")
            val gotoKind = gotoKindClass.getDeclaredField(kindName).get(null)

            val gotoKindEnumKeyClass = Class.forName("com.jetbrains.rd.ide.model.GotoKindEnumKey")
            val enumKeyCtor = gotoKindEnumKeyClass.declaredConstructors.firstOrNull { c ->
                c.parameterCount == 1 && c.parameterTypes[0].name.contains("GotoKind")
            } ?: run {
                log.debug("GotoKindEnumKey(GotoKind) constructor not found")
                return
            }
            enumKeyCtor.isAccessible = true
            val gotoKey = enumKeyCtor.newInstance(gotoKind)

            // getOrBindGotoSession(disposable, key): the disposable is a "ticket" that keeps the backend
            // session alive. Using the client session's disposable means the backend releases it when
            // the client disconnects.
            val session = cachedSessions.getOrPut(kindName) {
                val getSessionMethod = service.javaClass.methods.firstOrNull { m ->
                    m.name == "getOrBindGotoSession" && m.parameterCount == 2
                } ?: run {
                    log.debug("getOrBindGotoSession not found")
                    return
                }
                if (disposed) return
                getSessionMethod.invoke(service, sessionDisposable, gotoKey) ?: run {
                    log.debug("getOrBindGotoSession returned null")
                    return
                }
            }

            val requestNamesMethod = session.javaClass.methods.firstOrNull { m ->
                m.name == "requestNamesBlockingAndCacheGotoResults"
            } ?: return

            // Internally uses runBlockingCancellable, which needs a progress indicator in context.
            var result: Any? = null
            ProgressManager.getInstance().runProcess({
                result = when (requestNamesMethod.parameterCount) {
                    2 -> requestNamesMethod.invoke(session, query, true)
                    1 -> requestNamesMethod.invoke(session, query)
                    else -> null
                }
            }, EmptyProgressIndicator())

            val names: List<String> = when (val snapshot = result) {
                is Collection<*> -> snapshot.filterIsInstance<String>()
                is Array<*> -> snapshot.filterIsInstance<String>()
                else -> emptyList()
            }
            log.debug("$kindName: ${names.size} names for '$query'")

            val processMethod = session.javaClass.methods.firstOrNull { m ->
                m.name == "processBoundItemsWithNavItemsCacheLock"
            }

            if (processMethod != null) {
                // Resolving cached items into NavigationItems needs read access.
                runReadAction {
                    for (name in names) {
                        if (symbols.size >= maxResults) break
                        try {
                            processMethod.invoke(session, project, name, Processor<NavigationItem> { navItem ->
                                addNavigationItem(navItem, name, symbols, seen)
                                symbols.size < maxResults
                            })
                        } catch (e: Exception) {
                            log.debug("processBoundItems failed for '$name': ${e.message}")
                        }
                    }
                }
            } else {
                for (name in names) {
                    if (symbols.size >= maxResults) break
                    getItemsByShortName(session, name, symbols, seen)
                }
            }
        } catch (e: ClassNotFoundException) {
            log.debug("Rider goto service not available in this build: ${e.message}")
        } catch (e: Exception) {
            log.warn("RdDotnetGotoService search ($kindName) failed: ${e.javaClass.simpleName}: ${e.message}")
            e.cause?.let { log.warn("  Cause: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun getItemsByShortName(
        session: Any,
        name: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        try {
            val method = session.javaClass.methods.firstOrNull { m ->
                m.name == "getItemsByShortName" && m.parameterCount == 1
            } ?: return

            val items = method.invoke(session, name)
            if (items is List<*>) {
                for (item in items) {
                    if (symbols.size >= maxResults) break
                    if (item is NavigationItem) {
                        addNavigationItem(item, name, symbols, seen)
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("getItemsByShortName('$name') failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Strategy 2: ChooseByNameContributorEx
    // ------------------------------------------------------------------------------------------

    private fun collectViaProtocolContributorEx(
        query: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        val scope = GlobalSearchScope.allScope(project)
        val idFilter: IdFilter? = null

        for (epName in listOf(ChooseByNameContributor.CLASS_EP_NAME, ChooseByNameContributor.SYMBOL_EP_NAME)) {
            if (symbols.size >= maxResults) break

            for (contributor in epName.extensionList) {
                if (symbols.size >= maxResults) break
                if (contributor !is ChooseByNameContributorEx) continue

                try {
                    runReadAction {
                        val matchedNames = mutableListOf<String>()

                        contributor.processNames(Processor { name ->
                            if (name.contains(query, ignoreCase = true)) {
                                matchedNames.add(name)
                            }
                            matchedNames.size < maxResults
                        }, scope, idFilter)

                        val findParams = FindSymbolParameters.wrap(query, scope)
                        for (name in matchedNames) {
                            if (symbols.size >= maxResults) break
                            contributor.processElementsWithName(name, Processor { element ->
                                if (element is NavigationItem) {
                                    addNavigationItem(element, name, symbols, seen)
                                }
                                symbols.size < maxResults
                            }, findParams)
                        }
                    }
                } catch (e: Exception) {
                    log.debug("ContributorEx ${contributor.javaClass.simpleName} failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Strategy 3: header text search
    // ------------------------------------------------------------------------------------------

    /**
     * Finds class/struct declarations in header files by text. Uses FilenameIndex (UE naming
     * conventions) and the word index; reads file text without creating Documents.
     */
    private fun collectViaFileSearch(
        query: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        if (query.length < 2 || query.contains(" ")) return

        try {
            val found = runReadAction {
                val results = mutableListOf<SymbolInformation>()
                val localSeen = mutableSetOf<String>().apply { addAll(seen) }
                val scope = GlobalSearchScope.allScope(project)

                // Step 1: UE naming conventions: AActor -> Actor.h, FJsonValue -> JsonValue.h, UWorld -> World.h
                val possibleNames = mutableListOf("$query.h")
                if (query.length > 1 && query[0] in "AUFESIT" && query[1].isUpperCase()) {
                    possibleNames.add(0, "${query.substring(1)}.h")
                }

                for (fileName in possibleNames) {
                    if (results.size >= maxResults) break
                    for (file in FilenameIndex.getVirtualFilesByName(fileName, scope)) {
                        if (results.size >= maxResults) break
                        searchFileForDeclaration(file, query, results, localSeen)
                    }
                }

                // Step 2: word index -> candidate header files (VirtualFiles only, no PSI, no Documents)
                if (results.isEmpty()) {
                    try {
                        var scanned = 0
                        PsiSearchHelper.getInstance(project).processCandidateFilesForText(
                            scope,
                            UsageSearchContext.IN_CODE,
                            true,
                            query,
                            Processor<VirtualFile> { vf ->
                                val ext = vf.extension
                                if ((ext == "h" || ext == "hpp" || ext == "hxx") && scanned < MAX_HEADERS_TO_SCAN) {
                                    scanned++
                                    searchFileForDeclaration(vf, query, results, localSeen)
                                }
                                results.size < maxResults && scanned < MAX_HEADERS_TO_SCAN
                            }
                        )
                        log.debug("Header search scanned $scanned files, found ${results.size} results")
                    } catch (e: Exception) {
                        log.debug("Word-index header search failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }

                results
            }

            for (sym in found) {
                val key = "${sym.name}@${sym.location.uri}:${sym.location.range.start.line}"
                if (seen.add(key)) symbols.add(sym)
            }
        } catch (e: Exception) {
            log.warn("Header search failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Search one header for a class/struct declaration matching the query.
     * Matches `class ENGINE_API AActor : public UObject {`, `class UWorld final : ...`, `struct CORE_API FJsonValue {`
     * and skips forward declarations (`class AActor;`).
     */
    private fun searchFileForDeclaration(
        file: VirtualFile,
        query: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        if (!file.isValid || file.isDirectory || file.length > MAX_HEADER_BYTES) return
        val text = LspTranslator.fileText(file) ?: return
        val uri = LspTranslator.virtualFileToUri(file)

        val escapedQuery = Regex.escape(query)
        val pattern = Regex(
            """(?:class|struct)\s+(?:alignas\([^)]*\)\s+)?(?:\w+_API\s+)?$escapedQuery\s*(?:final\s*)?[:{]""",
            RegexOption.MULTILINE
        )

        for (match in pattern.findAll(text)) {
            if (symbols.size >= maxResults) break
            val nameIdx = StringUtil.indexOf(text, query, match.range.first)
            if (nameIdx >= 0) {
                val pos = LspTranslator.offsetToPosition(text, nameIdx)
                val key = "$query@$uri:${pos.line}"
                if (seen.add(key)) {
                    @Suppress("DEPRECATION")
                    symbols.add(SymbolInformation(query, SymbolKind.Class, Location(uri, Range(pos, pos))))
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // NavigationItem -> SymbolInformation
    // ------------------------------------------------------------------------------------------

    private fun extractLocationFromProtocolItem(item: NavigationItem): Location? {
        try {
            val itemClass = item.javaClass
            var vf: VirtualFile? = null

            // ProtocolNavigationItem.containingVirtualFile
            try {
                val field = itemClass.getDeclaredField("containingVirtualFile")
                field.isAccessible = true
                vf = field.get(item) as? VirtualFile
            } catch (_: Exception) {
            }
            if (vf == null) {
                try {
                    val vfMethod = itemClass.methods.firstOrNull { it.name == "getContainingVirtualFile" && it.parameterCount == 0 }
                        ?: itemClass.methods.firstOrNull { it.name == "getVirtualFile" && it.parameterCount == 0 }
                    vf = vfMethod?.invoke(item) as? VirtualFile
                } catch (_: Exception) {
                }
            }
            if (vf == null) {
                try {
                    vf = (item as? PsiElement)?.containingFile?.virtualFile
                } catch (_: Exception) {
                }
            }
            if (vf == null || !vf.isValid) return null

            val uri = LspTranslator.virtualFileToUri(vf)
            var pos = Position(0, 0)

            // The backend item carries no offset; locate the short name in the file text.
            val itemName = item.name ?: ""
            val searchName = if ("::" in itemName) {
                itemName.substringAfterLast("::").substringBefore("(").trim().removePrefix("~")
            } else {
                itemName.substringBefore("(").trim()
            }

            if (searchName.isNotEmpty() && vf.length <= MAX_HEADER_BYTES) {
                val text = LspTranslator.fileText(vf)
                if (text != null) {
                    val idx = findWholeWord(text, searchName)
                    if (idx >= 0) pos = LspTranslator.offsetToPosition(text, idx)
                }
            }

            return Location(uri, Range(pos, pos))
        } catch (e: Exception) {
            log.debug("extractLocationFromProtocolItem failed: ${e.message}")
            return null
        }
    }

    /** First whole-word occurrence of [word] in [text]; falls back to a plain substring match. */
    private fun findWholeWord(text: CharSequence, word: String): Int {
        var from = 0
        var fallback = -1
        while (true) {
            val idx = StringUtil.indexOf(text, word, from)
            if (idx < 0) break
            if (fallback < 0) fallback = idx
            val before = if (idx > 0) text[idx - 1] else ' '
            val afterIdx = idx + word.length
            val after = if (afterIdx < text.length) text[afterIdx] else ' '
            if (!isIdentifierChar(before) && !isIdentifierChar(after)) return idx
            from = idx + 1
        }
        return fallback
    }

    private fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun addNavigationItem(
        item: NavigationItem,
        fallbackName: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        if (symbols.size >= maxResults) return

        val itemClassName = item.javaClass.name
        // ProtocolNavigationItem extends FakePsiElement (a PsiElement) but its textRange is (0,0).
        val isFakeOrProtocol = itemClassName.contains("Protocol") ||
            itemClassName.contains("Fake") ||
            itemClassName.contains("RdDotnet")
        val psiElement = if (isFakeOrProtocol) null else item as? PsiElement
        val loc = if (psiElement == null) {
            extractLocationFromProtocolItem(item)
        } else {
            LspTranslator.psiElementToLocation(psiElement)
        }

        val itemName = (item as? PsiNamedElement)?.name ?: item.name ?: fallbackName

        if (loc != null) {
            val key = "$itemName@${loc.uri}:${loc.range.start.line}"
            if (!seen.add(key)) return

            val kind = if (psiElement is PsiNamedElement) {
                LspTranslator.psiElementToSymbolKind(psiElement)
            } else {
                SymbolKind.Class
            }

            @Suppress("DEPRECATION")
            symbols.add(SymbolInformation(itemName, kind, loc))
        } else {
            log.debug("Item without location: ${item.javaClass.simpleName} '$itemName'")
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {}

    private companion object {
        const val MAX_HEADERS_TO_SCAN = 50
        const val MAX_HEADER_BYTES = 4L * 1024 * 1024
    }
}

// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.PsiFile
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RiderLspWorkspaceService(
    private val project: Project
) : WorkspaceService {

    private val log = Logger.getInstance(RiderLspWorkspaceService::class.java)
    private val maxResults = 100

    // Cache goto sessions so the backend index survives across queries
    private val cachedSessions = mutableMapOf<String, Any>()
    private val sessionDisposables = mutableMapOf<String, com.intellij.openapi.Disposable>()

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        val query = params.query
        if (query.isNullOrBlank()) {
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }

        val future = CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>>()

        // RD protocol calls MUST NOT run inside ReadAction — they call back to the backend
        // and can deadlock if the read lock is held. Run on a pooled thread instead.
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val results = collectSymbols(query)
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

        // Strategy 1: Use RdDotnetGotoService with correct GotoKindEnumKey
        collectViaRdDotnetService(query, "GotoType", symbols, seen)
        if (symbols.size < maxResults) {
            collectViaRdDotnetService(query, "GotoSymbol", symbols, seen)
        }

        // Strategy 2: Use ChooseByNameContributorEx — always run to catch Engine symbols
        // that the RD backend may not return (e.g. AActor, FJsonValue, UWorld)
        if (symbols.size < maxResults) {
            collectViaProtocolContributorEx(query, symbols, seen)
        }

        // Strategy 3: File-based search — find class/struct declarations directly in headers.
        // Catches Engine types (AActor, FJsonValue, UWorld) that the RD backend doesn't index.
        if (symbols.size < maxResults && !symbols.any { it.name == query }) {
            collectViaFileSearch(query, symbols, seen)
        }

        return symbols
    }

    private fun collectViaRdDotnetService(
        query: String,
        kindName: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        try {
            // Get the service
            val serviceClass = Class.forName("com.jetbrains.rider.globalNavigation.RdDotnetGotoService")
            val companion = serviceClass.getDeclaredField("Companion").get(null)
            val getInstanceMethod = companion.javaClass.methods.find { m ->
                m.name == "getInstance" && m.parameterCount == 1
            } ?: return
            val service = getInstanceMethod.invoke(companion, project) ?: return

            // Create GotoKindEnumKey(GotoKind.GotoType) or GotoKindEnumKey(GotoKind.GotoSymbol)
            val gotoKindClass = Class.forName("com.jetbrains.rd.ide.model.GotoKind")
            val gotoKind = gotoKindClass.getDeclaredField(kindName).get(null)

            val gotoKindEnumKeyClass = Class.forName("com.jetbrains.rd.ide.model.GotoKindEnumKey")
            val enumKeyCtor = gotoKindEnumKeyClass.declaredConstructors.find { c ->
                c.parameterCount == 1 && c.parameterTypes[0].name.contains("GotoKind")
            }
            if (enumKeyCtor == null) {
                log.info("  GotoKindEnumKey(GotoKind) constructor not found")
                return
            }
            enumKeyCtor.isAccessible = true
            val gotoKey = enumKeyCtor.newInstance(gotoKind)

            log.info("  Created GotoKindEnumKey($kindName)")

            // Reuse cached session so the backend index survives across queries
            val session = cachedSessions.getOrPut(kindName) {
                val disposable = Disposer.newDisposable("LspGoto-$kindName")
                Disposer.register(project as com.intellij.openapi.Disposable, disposable)
                sessionDisposables[kindName] = disposable

                val getSessionMethod = service.javaClass.methods.find { m ->
                    m.name == "getOrBindGotoSession" && m.parameterCount == 2
                } ?: run {
                    log.info("  getOrBindGotoSession not found")
                    Disposer.dispose(disposable)
                    sessionDisposables.remove(kindName)
                    return
                }

                val s = getSessionMethod.invoke(service, disposable, gotoKey)
                if (s == null) {
                    log.info("  getOrBindGotoSession returned null")
                    Disposer.dispose(disposable)
                    sessionDisposables.remove(kindName)
                    return
                }
                log.info("  Created and cached session: ${s.javaClass.name}")
                s
            }

            log.info("  Using session: ${session.javaClass.name}")

            // requestNamesBlockingAndCacheGotoResults — blocks until backend responds
            val requestNamesMethod = session.javaClass.methods.find { m ->
                m.name == "requestNamesBlockingAndCacheGotoResults"
            }

            if (requestNamesMethod != null) {
                log.info("  Calling requestNamesBlockingAndCacheGotoResults('$query', false)...")

                // This method internally calls runBlockingCancellable which requires
                // a ProgressIndicator or coroutine Job in the thread context.
                var result: Any? = null
                ProgressManager.getInstance().runProcess(Runnable {
                    result = when (requestNamesMethod.parameterCount) {
                        2 -> requestNamesMethod.invoke(session, query, true)
                        1 -> requestNamesMethod.invoke(session, query)
                        else -> null
                    }
                }, EmptyProgressIndicator())

                val names = when (result) {
                    is Collection<*> -> result.filterIsInstance<String>()
                    is Array<*> -> result.filterIsInstance<String>()
                    else -> emptyList()
                }

                log.info("  Got ${names.size} names from requestNames")

                // Use processBoundItemsWithNavItemsCacheLock to get NavigationItems
                val processMethod = session.javaClass.methods.find { m ->
                    m.name == "processBoundItemsWithNavItemsCacheLock"
                }

                if (processMethod != null) {
                    log.info("  Using processBoundItemsWithNavItemsCacheLock to resolve items")
                    // processBoundItemsWithNavItemsCacheLock resolves cached items into
                    // PsiFile/NavigationItem objects, which requires read access.
                    ReadAction.run<Exception> {
                        for (name in names) {
                            if (symbols.size >= maxResults) break
                            try {
                                processMethod.invoke(session, project, name, Processor<NavigationItem> { navItem ->
                                    addNavigationItem(navItem, name, symbols, seen)
                                    symbols.size < maxResults
                                })
                            } catch (e: Exception) {
                                log.debug("  processBoundItems failed for '$name': ${e.message}")
                            }
                        }
                    }
                    log.info("  After processBoundItems: ${symbols.size} symbols")
                } else {
                    log.info("  processBoundItemsWithNavItemsCacheLock not found, falling back to getItemsByShortName")
                    for (name in names) {
                        if (symbols.size >= maxResults) break
                        getItemsByShortName(session, name, symbols, seen)
                    }
                }
            }

            // Sessions are cached — disposal happens when the project closes
        } catch (e: ClassNotFoundException) {
            log.info("  Class not found: ${e.message}")
        } catch (e: Exception) {
            log.warn("  RdDotnetService search ($kindName) failed: ${e.javaClass.simpleName}: ${e.message}")
            if (e.cause != null) {
                log.warn("    Cause: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
            }
        }
    }

    private fun getItemsByShortName(
        session: Any,
        name: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        try {
            val method = session.javaClass.methods.find { m ->
                m.name == "getItemsByShortName" && m.parameterCount == 1
            } ?: return

            val items = method.invoke(session, name)
            if (items is List<*>) {
                for (item in items) {
                    if (symbols.size >= maxResults) break
                    when (item) {
                        is NavigationItem -> addNavigationItem(item, name, symbols, seen)
                        null -> {}
                        else -> addRdGotoResult(item, name, symbols, seen)
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("  getItemsByShortName('$name') failed: ${e.message}")
        }
    }

    private fun addRdGotoResult(
        result: Any,
        fallbackName: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        try {
            val resultClass = result.javaClass
            // Log all getters on first encounter
            log.info("    RdGotoResult type: ${resultClass.name}")
            val getters = resultClass.methods.filter {
                it.name.startsWith("get") && it.parameterCount == 0 && it.returnType != Void.TYPE
            }
            for (g in getters) {
                try {
                    val value = g.invoke(result)
                    val valueStr = value?.toString()?.take(100) ?: "null"
                    log.info("      ${g.name}() -> $valueStr")
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            log.debug("    addRdGotoResult failed: ${e.message}")
        }
    }

    private fun collectViaProtocolContributorEx(
        query: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        // Use allScope to include Engine/library symbols, not just project source
        val scope = GlobalSearchScope.allScope(project)
        val idFilter: IdFilter? = null  // No filter — include all indexed files

        // Try both CLASS and SYMBOL extension points
        for ((epName, label) in listOf(
            ChooseByNameContributor.CLASS_EP_NAME to "CLASS",
            ChooseByNameContributor.SYMBOL_EP_NAME to "SYMBOL"
        )) {
            if (symbols.size >= maxResults) break
            val contributors = epName.extensionList

            for (contributor in contributors) {
                if (symbols.size >= maxResults) break
                if (contributor !is ChooseByNameContributorEx) continue

                log.info("  Trying $label ChooseByNameContributorEx: ${contributor.javaClass.name}")

                try {
                    // processNames and processElementsWithName access stub indices
                    // and PSI, which require read access.
                    ReadAction.run<Exception> {
                        val matchedNames = mutableListOf<String>()

                        contributor.processNames(Processor { name ->
                            if (name.contains(query, ignoreCase = true)) {
                                matchedNames.add(name)
                            }
                            matchedNames.size < maxResults
                        }, scope, idFilter)

                        log.info("    processNames found ${matchedNames.size} matching names")

                        val findParams = FindSymbolParameters(query, query, scope, null)
                        for (name in matchedNames) {
                            if (symbols.size >= maxResults) break
                            contributor.processElementsWithName(name, Processor { element ->
                                if (element is NavigationItem) {
                                    addNavigationItem(element, name, symbols, seen)
                                }
                                symbols.size < maxResults
                            }, findParams)
                        }

                        log.info("    After processElements: ${symbols.size} symbols total")
                    }
                } catch (e: Exception) {
                    log.warn("    ContributorEx failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * Strategy 3: File-based search for class/struct declarations in header files.
     * Uses FilenameIndex (UE naming conventions) and PsiSearchHelper (word index)
     * to find Engine types that the RD backend doesn't expose via GotoType/GotoSymbol.
     */
    private fun collectViaFileSearch(
        query: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        if (query.length < 2 || query.contains(" ")) return

        log.info("  Strategy 3: File-based search for '$query'")

        try {
            val found = ApplicationManager.getApplication().runReadAction(
                com.intellij.openapi.util.ThrowableComputable<List<SymbolInformation>, Exception> {
                    val results = mutableListOf<SymbolInformation>()
                    val localSeen = mutableSetOf<String>()
                    localSeen.addAll(seen)
                    val scope = GlobalSearchScope.allScope(project)

                    // Step 1: Derive possible header filenames from UE naming conventions.
                    // AActor -> Actor.h, FJsonValue -> JsonValue.h, UWorld -> World.h, etc.
                    val possibleNames = mutableListOf("$query.h")
                    if (query.length > 1 && query[0] in "AUFESIT" && query[1].isUpperCase()) {
                        possibleNames.add(0, "${query.substring(1)}.h")
                    }

                    log.info("    Step 1: Searching for files named: $possibleNames")
                    for (fileName in possibleNames) {
                        if (results.size >= maxResults) break
                        val files = FilenameIndex.getVirtualFilesByName(fileName, scope)
                        for (file in files) {
                            if (results.size >= maxResults) break
                            searchFileForDeclaration(file, query, results, localSeen)
                        }
                    }

                    // Step 2: If no exact match found via filename, use PsiSearchHelper's
                    // word index to efficiently find header files containing the query word.
                    if (results.isEmpty()) {
                        log.info("    Step 2: Using PsiSearchHelper word index for '$query'")
                        try {
                            var scanned = 0
                            PsiSearchHelper.getInstance(project).processAllFilesWithWord(
                                query,
                                scope,
                                Processor<PsiFile> { psiFile ->
                                    val vf = psiFile.virtualFile
                                    if (vf != null && (vf.extension == "h" || vf.extension == "hpp") && scanned < 50) {
                                        scanned++
                                        searchFileForDeclaration(vf, query, results, localSeen)
                                    }
                                    results.size < maxResults
                                },
                                true
                            )
                            log.info("    Step 2 scanned $scanned header files, found ${results.size} results")
                        } catch (e: Exception) {
                            log.info("    Step 2 PsiSearchHelper failed: ${e.javaClass.simpleName}: ${e.message}")
                            // Fallback: scan FilenameIndex
                            try {
                                val allHeaders = FilenameIndex.getAllFilesByExt(project, "h", scope)
                                val queryLower = query.lowercase()
                                var scanned = 0
                                for (file in allHeaders) {
                                    if (results.size >= maxResults || scanned >= 20) break
                                    if (file.nameWithoutExtension.lowercase().contains(queryLower)) {
                                        scanned++
                                        searchFileForDeclaration(file, query, results, localSeen)
                                    }
                                }
                                log.info("    Step 2 fallback scanned $scanned files, found ${results.size} results")
                            } catch (e2: Exception) {
                                log.info("    Step 2 fallback also failed: ${e2.message}")
                            }
                        }
                    }

                    log.info("    Strategy 3 found ${results.size} declarations")
                    results
                }
            )

            symbols.addAll(found)
            for (sym in found) {
                seen.add("${sym.name}@${sym.location.uri}:${sym.location.range.start.line}")
            }
        } catch (e: Exception) {
            log.warn("  Strategy 3 failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Search a single header file for class/struct declarations matching the query.
     * Matches patterns like: class ENGINE_API AActor : public UObject { ...
     * Skips forward declarations (class AActor;).
     */
    private fun searchFileForDeclaration(
        file: VirtualFile,
        query: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val text = document.text
        val uri = LspTranslator.virtualFileToUri(file)

        // Match: class/struct [alignment] [API_MACRO] ClassName [final] followed by : or { (not ;)
        // This skips forward declarations like "class AActor;"
        // Handles: class ENGINE_API AActor : public UObject
        //          class UWorld final : public UObject
        //          struct CORE_API FJsonValue {
        val escapedQuery = Regex.escape(query)
        val pattern = Regex(
            """(?:class|struct)\s+(?:alignas\([^)]*\)\s+)?(?:\w+_API\s+)?$escapedQuery\s+(?:final\s+)?(?:[:{])""",
            RegexOption.MULTILINE
        )

        for (match in pattern.findAll(text)) {
            if (symbols.size >= maxResults) break
            val nameIdx = text.indexOf(query, match.range.first)
            if (nameIdx >= 0) {
                val line = document.getLineNumber(nameIdx)
                val lineStart = document.getLineStartOffset(line)
                val col = nameIdx - lineStart
                val pos = Position(line, col)
                val loc = Location(uri, Range(pos, pos))
                val key = "$query@$uri:$line"
                if (seen.add(key)) {
                    @Suppress("DEPRECATION")
                    symbols.add(SymbolInformation(query, SymbolKind.Class, loc))
                    log.info("    Found: $query at ${file.path}:${line + 1}")
                }
            }
        }
    }

    private fun extractLocationFromProtocolItem(item: NavigationItem): Location? {
        try {
            val itemClass = item.javaClass
            // Extract file + line from ProtocolNavigationItem (FakePsiElement)

            // Get the VirtualFile — try multiple approaches
            var vf: VirtualFile? = null

            // Approach 1: containingVirtualFile field (most reliable for ProtocolNavigationItem)
            try {
                val field = itemClass.getDeclaredField("containingVirtualFile")
                field.isAccessible = true
                vf = field.get(item) as? VirtualFile
            } catch (_: Exception) {}

            // Approach 2: getVirtualFile()
            if (vf == null) {
                try {
                    val vfMethod = itemClass.methods.find {
                        it.name == "getVirtualFile" && it.parameterCount == 0
                    }
                    vf = vfMethod?.invoke(item) as? VirtualFile
                } catch (_: Exception) {}
            }

            // Approach 3: containingFile.virtualFile (PsiElement)
            if (vf == null) {
                try {
                    vf = (item as? PsiElement)?.containingFile?.virtualFile
                } catch (_: Exception) {}
            }

            if (vf == null) return null
            val uri = LspTranslator.virtualFileToUri(vf)

            // Convert offset -> line/col
            var line = 0
            var col = 0
            var startOffset = 0

            // The ProtocolNavigationItem doesn't carry offset data from the backend.
            // Resolve line number by searching for the symbol name in the file content.
            val itemName = item.name ?: ""
            // Extract the short name for matching (e.g. "APlayerController" from "APlayerController::APlayerController(...)")
            val searchName = if ("::" in itemName) {
                // For "Class::Method(...)", search for "Method" after the last "::"
                val afterColons = itemName.substringAfterLast("::")
                afterColons.substringBefore("(").trim().removePrefix("~")
            } else {
                itemName.substringBefore("(").trim()
            }

            if (searchName.isNotEmpty()) {
                val document = FileDocumentManager.getInstance().getDocument(vf)
                if (document != null) {
                    val text = document.text
                    // Search for the symbol name in the file
                    val idx = text.indexOf(searchName)
                    if (idx >= 0) {
                        line = document.getLineNumber(idx)
                        val lineStart = document.getLineStartOffset(line)
                        col = idx - lineStart
                    }
                }
            }


            if (startOffset > 0) {
                val document = FileDocumentManager.getInstance().getDocument(vf)
                if (document != null && startOffset <= document.textLength) {
                    line = document.getLineNumber(startOffset)
                    val lineStart = document.getLineStartOffset(line)
                    col = startOffset - lineStart
                }
            }

            val pos = Position(line, col)
            return Location(uri, Range(pos, pos))
        } catch (e: Exception) {
            log.debug("  extractLocationFromProtocolItem failed: ${e.message}")
            return null
        }
    }

    private fun addNavigationItem(
        item: NavigationItem,
        fallbackName: String,
        symbols: MutableList<SymbolInformation>,
        seen: MutableSet<String>
    ) {
        if (symbols.size >= maxResults) return

        val itemClassName = item.javaClass.name
        // ProtocolNavigationItem extends FakePsiElement (which IS a PsiElement),
        // but its textRange is (0,0). Check for protocol/fake items first.
        val isFakeOrProtocol = itemClassName.contains("Protocol") ||
            itemClassName.contains("Fake") ||
            itemClassName.contains("RdDotnet")
        val psiElement = if (isFakeOrProtocol) null else item as? PsiElement
        val loc = if (isFakeOrProtocol || psiElement == null) {
            extractLocationFromProtocolItem(item)
        } else {
            LspTranslator.psiElementToLocation(psiElement)
        }

        val itemName = (item as? PsiNamedElement)?.name ?: item.name ?: fallbackName

        if (loc != null) {
            val key = "${itemName}@${loc.uri}:${loc.range.start.line}"
            if (!seen.add(key)) return

            val kind = if (psiElement is PsiNamedElement) {
                LspTranslator.psiElementToSymbolKind(psiElement)
            } else {
                SymbolKind.Class
            }

            @Suppress("DEPRECATION")
            symbols.add(SymbolInformation(itemName, kind, loc))
        } else {
            log.debug("    Non-PSI item without location: ${item.javaClass.simpleName} '$itemName'")
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {}
}

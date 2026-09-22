// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import java.net.URI
import java.nio.file.Paths

/**
 * Conversions between IntelliJ (files, offsets, PSI) and LSP (URIs, positions, locations).
 *
 * All position math works on a [CharSequence] rather than a [com.intellij.openapi.editor.Document].
 * Creating documents for files that are not open in an editor makes the IDE cache them for a very
 * long time; on large engine code bases that alone can grow the heap by gigabytes.
 */
object LspTranslator {

    // --- URI / File conversion ---

    fun uriToVirtualFile(uri: String): VirtualFile? {
        val path = try {
            uriToPath(uri)
        } catch (e: Exception) {
            return null
        }
        return LocalFileSystem.getInstance().findFileByPath(path)
    }

    fun virtualFileToUri(file: VirtualFile): String {
        val path = file.path // forward slashes on the IntelliJ VFS
        return try {
            if (file.isInLocalFileSystem) Paths.get(path).toUri().toString() else "file:///$path"
        } catch (e: Exception) {
            "file:///$path"
        }
    }

    fun uriToPath(uri: String): String {
        val parsed = URI(uri)
        var path = parsed.path ?: uri.removePrefix("file://")
        // On Windows the URI path starts with /C:/ - strip the leading slash
        if (path.length >= 3 && path[0] == '/' && path[2] == ':') {
            path = path.substring(1)
        }
        return path
    }

    // --- Text access without creating Documents ---

    /** Text of a file: the live document when one is already loaded, otherwise read from the VFS. */
    fun fileText(file: VirtualFile): CharSequence? {
        FileDocumentManager.getInstance().getCachedDocument(file)?.let { return it.immutableCharSequence }
        return try {
            LoadTextUtil.loadText(file)
        } catch (e: Exception) {
            null
        }
    }

    /** Text backing a PSI file; consistent with the PSI element offsets. */
    fun psiFileText(psiFile: PsiFile): CharSequence = psiFile.viewProvider.contents

    // --- Offset / Position conversion ---

    fun positionToOffset(text: CharSequence, position: Position): Int {
        val length = text.length
        var line = 0
        var i = 0
        while (line < position.line && i < length) {
            if (text[i] == '\n') line++
            i++
        }
        val lineStart = i
        var lineEnd = lineStart
        while (lineEnd < length && text[lineEnd] != '\n') lineEnd++
        if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd--
        return (lineStart + position.character.coerceAtLeast(0)).coerceIn(lineStart, lineEnd)
    }

    fun offsetToPosition(text: CharSequence, offset: Int): Position {
        val safeOffset = offset.coerceIn(0, text.length)
        val lineColumn = StringUtil.offsetToLineColumn(text, safeOffset)
        return Position(lineColumn.line, lineColumn.column)
    }

    fun offsetsToRange(text: CharSequence, startOffset: Int, endOffset: Int): Range =
        Range(offsetToPosition(text, startOffset), offsetToPosition(text, endOffset))

    fun textRangeToRange(text: CharSequence, range: TextRange): Range =
        offsetsToRange(text, range.startOffset, range.endOffset)

    // --- PsiElement -> LSP Location ---

    fun psiElementToLocation(element: PsiElement): Location? {
        val psiFile = element.containingFile ?: return null
        val file = psiFile.virtualFile ?: return null
        val range = element.textRange ?: return null
        val text = psiFileText(psiFile)
        return Location(virtualFileToUri(file), textRangeToRange(text, range))
    }

    // --- Symbol kind mapping ---

    fun psiElementToSymbolKind(element: PsiElement): SymbolKind {
        val className = element.javaClass.simpleName.lowercase()
        return when {
            className.contains("class") -> SymbolKind.Class
            className.contains("struct") -> SymbolKind.Struct
            className.contains("enum") -> SymbolKind.Enum
            className.contains("function") || className.contains("method") -> SymbolKind.Function
            className.contains("field") || className.contains("variable") -> SymbolKind.Variable
            className.contains("namespace") -> SymbolKind.Namespace
            className.contains("typedef") || className.contains("alias") -> SymbolKind.TypeParameter
            className.contains("macro") || className.contains("define") -> SymbolKind.Constant
            className.contains("property") -> SymbolKind.Property
            className.contains("interface") -> SymbolKind.Interface
            else -> SymbolKind.Variable
        }
    }

    // --- HTML to markdown (for hover docs) ---

    fun htmlToMarkdown(html: String): String {
        if (html.isBlank()) return ""

        return html
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</?p>"), "\n")
            .replace(Regex("<b>(.*?)</b>"), "**$1**")
            .replace(Regex("<strong>(.*?)</strong>"), "**$1**")
            .replace(Regex("<i>(.*?)</i>"), "*$1*")
            .replace(Regex("<em>(.*?)</em>"), "*$1*")
            .replace(Regex("<code>(.*?)</code>"), "`$1`")
            .replace(Regex("<pre>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL), "```\n$1\n```")
            .replace(Regex("<a\\s+href=\"(.*?)\".*?>(.*?)</a>"), "[$2]($1)")
            .replace(Regex("</?\\w+[^>]*>"), "") // strip remaining tags
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}

// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import org.eclipse.lsp4j.*
import java.net.URI

object LspTranslator {

    // --- URI / File conversion ---

    fun uriToVirtualFile(uri: String): VirtualFile? {
        val path = uriToPath(uri)
        return LocalFileSystem.getInstance().findFileByPath(path)
    }

    fun virtualFileToUri(file: VirtualFile): String {
        // Produce file:///C:/path/to/file.cpp
        val path = file.path // already uses forward slashes on IntelliJ VFS
        return "file:///$path"
    }

    fun uriToPath(uri: String): String {
        val parsed = URI(uri)
        var path = parsed.path
        // On Windows, URI path starts with /C:/ — strip leading slash
        if (path.length >= 3 && path[0] == '/' && path[2] == ':') {
            path = path.substring(1)
        }
        return path
    }

    // --- Offset / Position conversion ---

    fun positionToOffset(document: Document, position: Position): Int {
        val line = position.line.coerceIn(0, document.lineCount - 1)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val col = position.character.coerceIn(0, lineEnd - lineStart)
        return lineStart + col
    }

    fun offsetToPosition(document: Document, offset: Int): Position {
        val safeOffset = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(safeOffset)
        val lineStart = document.getLineStartOffset(line)
        return Position(line, safeOffset - lineStart)
    }

    fun textRangeToRange(document: Document, range: TextRange): Range {
        return Range(
            offsetToPosition(document, range.startOffset),
            offsetToPosition(document, range.endOffset)
        )
    }

    // --- PsiElement -> LSP Location ---

    fun psiElementToLocation(element: PsiElement): Location? {
        val file = element.containingFile?.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(element.project)
            .getDocument(element.containingFile) ?: return null

        val range = textRangeToRange(document, element.textRange)
        return Location(virtualFileToUri(file), range)
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

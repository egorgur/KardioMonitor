package ru.kardiomonitor.architecture

internal data class FunctionSpan(val name: String, val firstLine: Int, val lastLine: Int, val isComposable: Boolean) {
    val length: Int get() = lastLine - firstLine + 1
}

internal data class TopLevelDeclaration(val name: String, val line: Int, val modifiers: List<String>) {
    val isPublic: Boolean get() = "private" !in modifiers && "internal" !in modifiers
}

internal fun KotlinSourceFile.functions(): List<FunctionSpan> =
    lines.indices.mapNotNull { index ->
        val match = FUNCTION.find(lines[index]) ?: return@mapNotNull null
        val last = bodyEnd(index, indent = match.groupValues[1].length)
        FunctionSpan(match.groupValues[2].substringAfterLast('.'), index + 1, last + 1, isComposable(index))
    }

internal fun KotlinSourceFile.topLevelDeclarations(): List<TopLevelDeclaration> =
    codeLines.mapNotNull { (number, line) ->
        val match = TOP_LEVEL.find(line) ?: return@mapNotNull null
        TopLevelDeclaration(match.groupValues[3], number, match.groupValues[1].split(' ').filter(String::isNotBlank))
    }

private fun KotlinSourceFile.bodyEnd(start: Int, indent: Int): Int {
    val (signatureEnd, rest) = signatureEnd(start)
    val tail = rest.substringBefore("//").trimEnd()
    return when {
        tail.endsWith("{") -> closingBrace(signatureEnd, indent)
        tail.contains("=") -> expressionEnd(signatureEnd, indent)
        else -> signatureEnd
    }
}

private fun KotlinSourceFile.signatureEnd(start: Int): Pair<Int, String> {
    var depth = 0
    var opened = false
    for (index in start until lines.size) {
        val line = lines[index]
        val from = if (index == start) line.indexOf("fun ") else 0
        for (column in from until line.length) {
            when (line[column]) {
                '(' -> { depth++; opened = true }
                ')' -> depth--
            }
            if (opened && depth == 0) return index to line.substring(column + 1)
        }
    }
    return start to ""
}

private fun KotlinSourceFile.closingBrace(from: Int, indent: Int): Int =
    (from + 1 until lines.size).firstOrNull { index ->
        val line = lines[index]
        line.trimStart().startsWith("}") && indentOf(line) == indent
    } ?: lines.lastIndex

private fun KotlinSourceFile.expressionEnd(from: Int, indent: Int): Int {
    var last = from
    for (index in from + 1 until lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        val continues = trimmed.isEmpty() || indentOf(line) > indent ||
            (indentOf(line) == indent && trimmed.first() in CLOSERS)
        if (!continues) break
        if (trimmed.isNotEmpty()) last = index
    }
    return last
}

private fun KotlinSourceFile.isComposable(funIndex: Int): Boolean =
    (funIndex - 1 downTo 0)
        .asSequence()
        .map { lines[it].trim() }
        .takeWhile { it.startsWith("@") }
        .any { it.startsWith("@Composable") } || lines[funIndex].contains("@Composable")

private fun indentOf(line: String): Int = line.length - line.trimStart().length

private const val CLOSERS = ")]}"
private const val MODIFIERS =
    "private|internal|public|protected|override|suspend|inline|operator|infix|tailrec|open|abstract|final"
private val FUNCTION = Regex("""^(\s*)(?:@\w+\s+)*(?:(?:$MODIFIERS)\s+)*fun\s+(?:<[^>]*>\s*)?([\w.]+)""")
private val TOP_LEVEL = Regex(
    """^(?:@\w+(?:\([^)]*\))?\s+)*((?:[a-z]+\s+)*?)(fun|class|interface|object|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w<>?, ]+\.)?(\w+)""",
)

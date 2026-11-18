package ru.kardiomonitor.architecture

import org.junit.Test

/** Общие правила кода для обоих приложений, протокола и самих архитектурных тестов. */
class CodeRulesTest {
    private val production = SourceModule.all.flatMap(SourceModule::productionFiles)
    private val tests = SourceModule.allWithArchitectureTests.flatMap(SourceModule::testFiles)

    @Test
    fun `в именах типов нет пустых суффиксов`() {
        val violations = production.flatMap { file ->
            val suffixes = if (file.feature != null) FEATURE_FORBIDDEN_SUFFIXES else FORBIDDEN_SUFFIXES
            declaredTypes(file)
                .filter { (name, _) -> suffixes.any(name::endsWith) }
                .map { (name, line) -> "${file.at(line)}: $name" }
        }
        assertNoViolations("имена из глоссария, без ${FEATURE_FORBIDDEN_SUFFIXES.joinToString()}", violations)
    }

    @Test
    fun `файл не длиннее 300 строк`() {
        val violations = (production + tests)
            .filter { it.lines.size > MAX_FILE_LINES }
            .map { "${it.path}: ${it.lines.size} строк — разделите по смыслу" }
        assertNoViolations("файл ≤ $MAX_FILE_LINES строк (coding-rules.md §3)", violations)
    }

    @Test
    fun `функция не длиннее 40 строк, composable — 80`() {
        val violations = production.flatMap { file ->
            file.functions()
                .filter { it.length > if (it.isComposable) MAX_COMPOSABLE_LINES else MAX_FUNCTION_LINES }
                .map { "${file.at(it.firstLine)}: ${it.name} — ${it.length} строк" }
        }
        assertNoViolations("функция ≤ 40 строк, @Composable ≤ 80 строк", violations)
    }

    @Test
    fun `runCatching не используется — он перехватывает отмену корутины`() {
        assertNoViolations(
            "вместо runCatching — try/catch конкретного исключения с пробросом CancellationException",
            findInCode(production, Regex("""\brunCatching\b""")),
        )
    }

    @Test
    fun `нет GlobalScope и оператора !! в production`() {
        assertNoViolations(
            "структурированная конкурентность и requireNotNull вместо !!",
            findInCode(production, Regex("""\bGlobalScope\b|!!""")),
        )
    }

    @Test
    fun `нет импортов со звёздочкой`() {
        val violations = (production + tests).flatMap { file ->
            file.imports.filter { it.endsWith(".*") }.map { "${file.path}: import $it" }
        }
        assertNoViolations("явные импорты (coding-rules.md §5)", violations)
    }

    @Test
    fun `цвета в контекстах берутся из темы`() {
        assertNoViolations(
            "цвета — из MaterialTheme и platform.ui (coding-rules.md §9)",
            findInCode(production.filter { it.feature != null }, Regex("""\bColor\(0x""")),
        )
    }

    @Test
    fun `имена тестов описывают поведение в обратных кавычках`() {
        val violations = tests.flatMap { file ->
            file.lines.withIndex()
                .filter { (_, line) -> line.trim().startsWith("@Test") }
                .mapNotNull { (index, _) -> testFunctionLine(file, index) }
                .filterNot { (_, line) -> line.contains("fun `") }
                .map { (number, line) -> "${file.at(number)}: ${line.trim()}" }
        }
        assertNoViolations("fun `поведение на русском`() (testing.md)", violations)
    }

    @Test
    fun `секреты не передаются в Log`() {
        val violations = production.flatMap { file ->
            logCalls(file)
                .filter { (_, call) -> SECRET_NAME.containsMatchIn(call) }
                .map { (number, call) -> "${file.at(number)}: ${call.lineSequence().first().trim()}" }
        }
        assertNoViolations("ключи, QR и облачные учётные данные не попадают в Log.*", violations)
    }

    private fun declaredTypes(file: KotlinSourceFile): List<Pair<String, Int>> =
        file.codeLines.flatMap { (number, line) ->
            TYPE_DECLARATION.findAll(line).map { it.groupValues[1] to number }.toList()
        }

    private fun findInCode(files: List<KotlinSourceFile>, pattern: Regex): List<String> =
        files.flatMap { file ->
            file.codeLines
                .filter { (_, line) -> pattern.containsMatchIn(line.substringBefore("//")) }
                .map { (number, line) -> "${file.at(number)}: ${line.trim()}" }
        }

    private fun testFunctionLine(file: KotlinSourceFile, annotationIndex: Int): Pair<Int, String>? =
        file.lines.withIndex()
            .drop(annotationIndex)
            .firstOrNull { (_, line) -> line.contains("fun ") }
            ?.let { (index, line) -> index + 1 to line }

    private fun logCalls(file: KotlinSourceFile): List<Pair<Int, String>> = buildList {
        file.lines.forEachIndexed { index, line ->
            if (!LOG_CALL.containsMatchIn(line)) return@forEachIndexed
            val call = StringBuilder()
            var depth = 0
            for (current in index until minOf(index + MAX_LOG_CALL_LINES, file.lines.size)) {
                val code = file.lines[current].substringBefore("//")
                call.appendLine(code)
                depth += code.count { it == '(' } - code.count { it == ')' }
                if (depth <= 0) break
            }
            add(index + 1 to call.toString())
        }
    }

    private companion object {
        const val MAX_FILE_LINES = 300
        const val MAX_FUNCTION_LINES = 40
        const val MAX_COMPOSABLE_LINES = 80
        const val MAX_LOG_CALL_LINES = 12
        val TYPE_DECLARATION = Regex("""\b(?:class|interface|object)\s+([A-Z]\w*)""")
        val FORBIDDEN_SUFFIXES = listOf("Manager", "Helper", "Util", "Utils")
        val FEATURE_FORBIDDEN_SUFFIXES = FORBIDDEN_SUFFIXES +
            listOf("Processor", "Handler", "Data", "Info", "Item")
        val LOG_CALL = Regex("""\bLog\.[vdiew]\s*\(""")
        val SECRET_NAME = Regex(
            """authKey|pairingCode|password|ssecurity|serviceToken|huamiToken|cookie|secret|credentials|\bkey\b|\btoken\b|\bcode\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}

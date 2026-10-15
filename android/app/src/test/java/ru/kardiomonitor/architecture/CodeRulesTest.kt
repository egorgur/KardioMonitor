package ru.kardiomonitor.architecture

import org.junit.Test

/** Проверяемые автоматически правила из docs/coding-rules.md и docs/testing.md. */
class CodeRulesTest {
    private val production = KotlinSourceFile.loadAll("src/main/java")
    private val tests = KotlinSourceFile.loadAll("src/test/java")

    @Test
    fun `в именах типов нет пустых суффиксов`() {
        val violations = production.flatMap { file ->
            val suffixes = if (file.feature != null) FEATURE_FORBIDDEN_SUFFIXES else FORBIDDEN_SUFFIXES
            declaredTypes(file)
                .filter { (name, _) -> name !in LEGACY_NAMES && suffixes.any(name::endsWith) }
                .map { (name, line) -> "${file.at(line)}: $name" }
        }
        assertNoViolations("имена из глоссария, без ${FEATURE_FORBIDDEN_SUFFIXES.joinToString()} (coding-rules.md §2)", violations)
    }

    @Test
    fun `файл не длиннее 300 строк`() {
        val violations = (production + tests)
            .filter { it.lines.size > MAX_FILE_LINES }
            .map { "${it.path}: ${it.lines.size} строк — разделите по смыслу" }
        assertNoViolations("файл ≤ $MAX_FILE_LINES строк (coding-rules.md §3)", violations)
    }

    @Test
    fun `runCatching не используется — он перехватывает отмену корутины`() {
        assertNoViolations(
            "вместо runCatching — try/catch конкретного исключения (IOException), CancellationException пробрасывается",
            findInCode(production, Regex("""\brunCatching\b""")),
        )
    }

    @Test
    fun `нет GlobalScope и оператора !!`() {
        assertNoViolations(
            "структурированная конкурентность и requireNotNull вместо !! (coding-rules.md §5, §8)",
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
        val inFeatures = production.filter { it.feature != null }
        assertNoViolations(
            "цвета — из MaterialTheme и platform.ui (coding-rules.md §9)",
            findInCode(inFeatures, Regex("""\bColor\(0x""")),
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
        assertNoViolations("fun `поведение на русском`() (testing.md, «Имена тестов»)", violations)
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

    /** Строка с `fun` после аннотации `@Test` (между ними могут быть другие аннотации). */
    private fun testFunctionLine(file: KotlinSourceFile, annotationIndex: Int): Pair<Int, String>? =
        file.lines.withIndex()
            .drop(annotationIndex)
            .firstOrNull { (_, line) -> line.contains("fun ") }
            ?.let { (index, line) -> index + 1 to line }

    private companion object {
        const val MAX_FILE_LINES = 300
        val TYPE_DECLARATION = Regex("""\b(?:class|interface|object)\s+([A-Z]\w*)""")
        val FORBIDDEN_SUFFIXES = listOf("Manager", "Helper", "Util", "Utils")
        val FEATURE_FORBIDDEN_SUFFIXES = FORBIDDEN_SUFFIXES + listOf("Processor", "Data", "Info", "Item")

        /** Прототип до правил. Удаляется отсюда тем же шагом миграции, что и класс (architecture.md). */
        val LEGACY_NAMES = emptySet<String>()
    }
}

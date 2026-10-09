package ru.kardiomonitor.architecture

import java.io.File

/**
 * Исходный файл Kotlin, разобранный ровно настолько, насколько нужно архитектурным тестам:
 * пакет, импорты, строки кода без комментариев. Без компилятора — см. ADR-0005.
 */
internal class KotlinSourceFile(file: File, root: File) {
    val path: String = file.relativeTo(root).path
    val lines: List<String> = file.readLines()
    val packageName: String = lines
        .firstOrNull { it.startsWith("package ") }
        ?.removePrefix("package ")
        ?.trim()
        .orEmpty()
    val imports: List<String> = lines
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").substringBefore(" as ").trim() }

    /** Место в целевой структуре `features.<контекст>.<часть>`; null — код вне `features`. */
    val feature: FeaturePart? = FeaturePart.of(packageName)

    /** Строки кода с номерами (с 1), без строк-комментариев и KDoc. */
    val codeLines: List<IndexedValue<String>> = lines
        .withIndex()
        .filterNot { isCommentLine(it.value) }
        .map { IndexedValue(it.index + 1, it.value) }

    fun at(lineNumber: Int): String = "$path:$lineNumber"

    companion object {
        /** Все `.kt` под [dir]; путь — относительно модуля `app`, где Gradle запускает тесты. */
        fun loadAll(dir: String): List<KotlinSourceFile> {
            val root = File(dir)
            check(root.isDirectory) { "нет каталога $dir: тесты запускаются не из модуля app" }
            return root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { KotlinSourceFile(it, root) }
                .toList()
                .also { check(it.isNotEmpty()) { "в $dir нет файлов Kotlin" } }
        }

        private fun isCommentLine(line: String): Boolean {
            val trimmed = line.trimStart()
            return trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
        }
    }
}

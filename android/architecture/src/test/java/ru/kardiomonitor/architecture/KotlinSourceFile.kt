package ru.kardiomonitor.architecture

import java.io.File

/** Исходный файл Kotlin, лексически разобранный без зависимости от компилятора (ADR-0005). */
internal class KotlinSourceFile(file: File, val module: SourceModule, buildRoot: File) {
    val path: String = file.relativeTo(buildRoot).path
    val lines: List<String> = file.readLines()
    val packageName: String = lines
        .firstOrNull { it.startsWith("package ") }
        ?.removePrefix("package ")
        ?.trim()
        .orEmpty()
    val imports: List<String> = lines
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").substringBefore(" as ").trim() }
    val feature: FeaturePart? = FeaturePart.of(module, packageName)
    val codeLines: List<IndexedValue<String>> = lines
        .withIndex()
        .filterNot { isCommentLine(it.value) }
        .map { IndexedValue(it.index + 1, it.value) }

    fun at(lineNumber: Int): String = "$path:$lineNumber"

    companion object {
        fun loadAll(module: SourceModule, root: File): List<KotlinSourceFile> {
            if (!root.isDirectory) return emptyList()
            val buildRoot = requireNotNull(module.projectDir.parentFile)
            return root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { KotlinSourceFile(it, module, buildRoot) }
                .toList()
        }

        private fun isCommentLine(line: String): Boolean {
            val trimmed = line.trimStart()
            return trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
        }
    }
}

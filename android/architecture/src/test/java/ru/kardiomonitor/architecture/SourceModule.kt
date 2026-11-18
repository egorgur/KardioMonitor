package ru.kardiomonitor.architecture

import java.io.File

/** Gradle-модуль, исходники которого проверяют архитектурные тесты. */
internal data class SourceModule(
    val name: String,
    val rootPackage: String,
    val projectDir: File,
) {
    fun productionFiles(): List<KotlinSourceFile> = sourceFiles("main", required = true)

    fun testFiles(): List<KotlinSourceFile> = sourceFiles("test", required = false)

    private fun sourceFiles(sourceSet: String, required: Boolean): List<KotlinSourceFile> {
        val files = SOURCE_LANGUAGES.flatMap { language ->
            KotlinSourceFile.loadAll(this, projectDir.resolve("src/$sourceSet/$language"))
        }
        check(!required || files.isNotEmpty()) { "в модуле $name нет production-файлов Kotlin" }
        return files
    }

    companion object {
        private val SOURCE_LANGUAGES = listOf("java", "kotlin")
        private val buildRoot = File(requireNotNull(System.getProperty("kardiomonitor.rootDir")))

        val app = SourceModule("app", "ru.kardiomonitor", buildRoot.resolve("app"))
        val protocol = SourceModule("band-protocol", "ru.kardiomonitor", buildRoot.resolve("band-protocol"))
        val staff = SourceModule("staff", "ru.kardiomonitor.staff", buildRoot.resolve("staff"))
        val architecture = SourceModule("architecture", "ru.kardiomonitor.architecture", buildRoot.resolve("architecture"))

        val applications: List<SourceModule> get() = listOfNotNull(app, staff.takeIf { it.projectDir.isDirectory })
        val all: List<SourceModule> get() = applications + protocol
        val allWithArchitectureTests: List<SourceModule> get() = all + architecture
    }
}

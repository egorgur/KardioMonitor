package ru.kardiomonitor.architecture

import org.junit.Test

/**
 * Правила зависимостей из docs/architecture.md («Правила зависимостей»). Код прототипа
 * вне `features.*` и `platform.*` (ble/, data/, ui/) под правила пока не попадает —
 * он переносится по плану миграции.
 */
class DependencyRulesTest {
    private val production = KotlinSourceFile.loadAll("src/main/java")

    @Test
    fun `код контекста лежит в его домене, api, data или слайсе`() {
        val violations = production
            .filter { it.feature?.part?.isEmpty() == true }
            .map { "${it.path}: пакет ${it.packageName} — добавьте часть: domain, api, data или слайс" }
        assertNoViolations("структура features.<контекст>.<часть>", violations)
    }

    @Test
    fun `домен не зависит от Android, Compose и платформы`() {
        val violations = production.flatMap { file ->
            val feature = file.feature?.takeIf { it.part == "domain" } ?: return@flatMap emptyList()
            val ownDomain = "ru.kardiomonitor.features.${feature.context}.domain"
            val allowed = DOMAIN_ALLOWED_IMPORTS + ownDomain
            file.imports
                .filterNot { import -> allowed.any { import.startsWith(it) } }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations("домен — чистый Kotlin: stdlib, kotlinx.coroutines.flow, java.time", violations)
    }

    @Test
    fun `слайс не импортирует другие слайсы и реализации data`() {
        val violations = production.flatMap { file ->
            val slice = file.feature?.takeIf { it.isSlice } ?: return@flatMap emptyList()
            file.imports
                .filter { import -> !isAllowedForSlice(import, slice) }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations(
            "слайс видит домен, features.*.api и platform.ui; связь с другими слайсами — через Flow репозиториев",
            violations,
        )
    }

    @Test
    fun `api контекста зависит только от своего домена`() {
        val violations = production.flatMap { file ->
            val feature = file.feature?.takeIf { it.part == "api" } ?: return@flatMap emptyList()
            val own = listOf("ru.kardiomonitor.features.${feature.context}.domain", "ru.kardiomonitor.features.${feature.context}.api")
            file.imports
                .filter { it.startsWith(PROJECT) || it.startsWith("android.") || it.startsWith("androidx.") }
                .filterNot { import -> own.any { import.startsWith(it) } }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations("api — порт на языке домена, без Android и реализаций", violations)
    }

    @Test
    fun `data контекста не знает о слайсах и других контекстах`() {
        val violations = production.flatMap { file ->
            val feature = file.feature?.takeIf { it.part == "data" } ?: return@flatMap emptyList()
            file.imports
                .filter { import -> FeaturePart.of(import)?.let { it != FeaturePart(feature.context, "domain") } ?: false }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations("data реализует репозитории своего домена", violations)
    }

    @Test
    fun `платформа не зависит от слайсов, доменные типы видит только ACL Huami`() {
        val violations = production
            .filter { it.packageName.startsWith("$PROJECT.platform") }
            .flatMap { file ->
                val isHuamiAcl = file.packageName.startsWith("$PROJECT.platform.huami")
                file.imports
                    .filter { import -> FeaturePart.of(import)?.let { !(isHuamiAcl && it.part == "domain") } ?: false }
                    .map { "${file.path}: import $it" }
            }
        assertNoViolations("platform — техника без бизнес-понятий; перевод байтов в домен — только в platform.huami", violations)
    }

    private fun isAllowedForSlice(import: String, slice: FeaturePart): Boolean {
        val target = FeaturePart.of(import)
        return when {
            target != null -> target == slice || target.part == "domain" || target.part == "api"
            import.startsWith("$PROJECT.platform.") -> import.startsWith("$PROJECT.platform.ui")
            else -> true
        }
    }

    private companion object {
        const val PROJECT = "ru.kardiomonitor"
        val DOMAIN_ALLOWED_IMPORTS = listOf("kotlin.", "kotlinx.coroutines.flow.", "java.time.")
    }
}

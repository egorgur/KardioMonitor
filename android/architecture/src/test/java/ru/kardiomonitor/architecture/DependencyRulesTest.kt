package ru.kardiomonitor.architecture

import org.junit.Test

/** Правила VSA/DDD приложений и границы общего протокольного модуля. */
class DependencyRulesTest {
    private val applications = SourceModule.applications.associateWith(SourceModule::productionFiles)
    private val protocol = SourceModule.protocol.productionFiles()

    @Test
    fun `код контекста лежит в его домене, api, data или слайсе`() {
        val violations = applicationFiles()
            .filter { it.feature?.part?.isEmpty() == true }
            .map { "${it.path}: пакет ${it.packageName} — добавьте domain, api, data или слайс" }
        assertNoViolations("структура features.<контекст>.<часть>", violations)
    }

    @Test
    fun `домен не зависит от Android, Compose и платформы`() {
        val violations = applicationFiles().flatMap { file ->
            val feature = file.feature?.takeIf { it.part == "domain" } ?: return@flatMap emptyList()
            val ownDomain = "${file.module.rootPackage}.features.${feature.context}.domain"
            val shared = "${file.module.rootPackage}.shared."
            file.imports
                .filterNot { import -> (DOMAIN_ALLOWED_IMPORTS + ownDomain + shared).any(import::startsWith) }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations("домен — чистый Kotlin: stdlib, Flow и java.time", violations)
    }

    @Test
    fun `слайс не импортирует другие слайсы и реализации data`() {
        val violations = applicationFiles().flatMap { file ->
            val slice = file.feature?.takeIf { it.isSlice } ?: return@flatMap emptyList()
            file.imports
                .filterNot { import -> isAllowedForSlice(file.module, import, slice) }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations("слайс видит домен, features.*.api и platform.ui", violations)
    }

    @Test
    fun `публична в слайсе только используемая точка входа`() {
        val violations = applications.flatMap { (module, files) ->
            files.flatMap { file ->
                val slice = file.feature?.takeIf { it.isSlice } ?: return@flatMap emptyList()
                val outside = files.filter { it.feature != slice }
                file.topLevelDeclarations()
                    .filter { it.isPublic }
                    .filterNot { declaration -> outside.any { it.mentions(declaration.name) } }
                    .map { "${file.at(it.line)}: ${it.name} — сделайте internal или private" }
            }
        }
        assertNoViolations("наружу слайс отдаёт только точки входа", violations)
    }

    @Test
    fun `api контекста зависит только от своего домена`() {
        val violations = applicationFiles().flatMap { file ->
            val feature = file.feature?.takeIf { it.part == "api" } ?: return@flatMap emptyList()
            val prefix = file.module.rootPackage
            val own = listOf("$prefix.features.${feature.context}.domain", "$prefix.features.${feature.context}.api")
            file.imports
                .filter { it.startsWith(prefix) || it.startsWith("android.") || it.startsWith("androidx.") }
                .filterNot { import -> own.any(import::startsWith) }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations("api — порт на языке домена, без Android и реализаций", violations)
    }

    @Test
    fun `data контекста не знает о слайсах и других контекстах`() {
        val violations = applicationFiles().flatMap { file ->
            val feature = file.feature?.takeIf { it.part == "data" } ?: return@flatMap emptyList()
            file.imports
                .mapNotNull { import -> FeaturePart.of(file.module, import)?.let { import to it } }
                .filter { (_, target) -> target != FeaturePart(file.module.name, feature.context, "domain") }
                .map { (import, _) -> "${file.path}: import $import" }
        }
        assertNoViolations("data реализует репозитории своего домена", violations)
    }

    @Test
    fun `платформа приложения не зависит от слайсов`() {
        val violations = applicationFiles()
            .filter { it.packageName.startsWith("${it.module.rootPackage}.platform") }
            .flatMap { file ->
                file.imports
                    .mapNotNull { import -> FeaturePart.of(file.module, import)?.let { import to it } }
                    .filter { (_, feature) -> !isAllowedPlatformFeature(file, feature) }
                    .map { (import, _) -> "${file.path}: import $import" }
            }
        assertNoViolations("platform — техника; доменные типы видят только объявленные ACL", violations)
    }

    @Test
    fun `приложения не импортируют друг друга`() {
        val staffPrefix = "${SourceModule.staff.rootPackage}."
        val appOnlyPrefixes = listOf("ru.kardiomonitor.app.", "ru.kardiomonitor.features.")
        val violations = applicationFiles().flatMap { file ->
            file.imports
                .filter { import ->
                    (file.module == SourceModule.app && import.startsWith(staffPrefix)) ||
                        (file.module == SourceModule.staff && appOnlyPrefixes.any(import::startsWith))
                }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations(":app и :staff независимы и связаны только через :band-protocol", violations)
    }

    @Test
    fun `staff импортирует протокол только через platform band`() {
        val staffFiles = applications[SourceModule.staff].orEmpty()
        val violations = staffFiles.flatMap { file ->
            file.imports
                .filter(::isProtocolImport)
                .filterNot { file.packageName.startsWith("${SourceModule.staff.rootPackage}.platform.band") }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations("только staff.platform.band зависит от :band-protocol", violations)
    }

    @Test
    fun `протокол не зависит от приложений и их домена`() {
        val forbidden = listOf("ru.kardiomonitor.app.", "ru.kardiomonitor.features.", "ru.kardiomonitor.staff.")
        val violations = protocol.flatMap { file ->
            file.imports
                .filter { import -> forbidden.any(import::startsWith) }
                .map { "${file.path}: import $it" }
        }
        assertNoViolations(":band-protocol содержит транспорт и протоколы, но не домен приложений", violations)
    }

    private fun applicationFiles(): List<KotlinSourceFile> = applications.values.flatten()

    private fun KotlinSourceFile.mentions(name: String): Boolean {
        val word = Regex("""\b$name\b""")
        return codeLines.any { (_, line) -> word.containsMatchIn(line) }
    }

    private fun isAllowedForSlice(module: SourceModule, import: String, slice: FeaturePart): Boolean {
        val target = FeaturePart.of(module, import)
        return when {
            target != null -> target == slice || target.part == "domain" || target.part == "api"
            import.startsWith("${module.rootPackage}.platform.") ->
                import.startsWith("${module.rootPackage}.platform.ui")
            else -> true
        }
    }

    private fun isAllowedPlatformFeature(file: KotlinSourceFile, feature: FeaturePart): Boolean {
        if (feature.part != "domain") return false
        if (file.module == SourceModule.app) {
            val aclPackages = listOf("platform.huami", "platform.xiaomi", "platform.band")
            return aclPackages.any { file.packageName.startsWith("${file.module.rootPackage}.$it") } &&
                (feature.context == "band" || feature.context == "monitoring")
        }
        return when {
            file.packageName.startsWith("${file.module.rootPackage}.platform.band") ->
                feature.context == "bandcheck" || feature.context == "inventory"
            file.packageName.startsWith("${file.module.rootPackage}.platform.xiaomi") ->
                feature.context == "inventory" || feature.context == "account"
            else -> false
        }
    }

    private fun isProtocolImport(import: String): Boolean =
        import.startsWith("ru.kardiomonitor.platform.ble.") || import.startsWith("ru.kardiomonitor.platform.huami.") ||
            import.startsWith("ru.kardiomonitor.bandprotocol.xiaomi.")

    private companion object {
        val DOMAIN_ALLOWED_IMPORTS = listOf("kotlin.", "kotlinx.coroutines.flow.", "java.time.")
    }
}

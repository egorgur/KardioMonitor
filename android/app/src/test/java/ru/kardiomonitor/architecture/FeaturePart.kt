package ru.kardiomonitor.architecture

/**
 * Часть ограниченного контекста `features.<context>.<part>`: общая часть контекста
 * (domain, api, data, fakes) или слайс. См. docs/architecture.md.
 */
internal data class FeaturePart(val context: String, val part: String) {
    val isSlice: Boolean get() = part !in SHARED_PARTS

    override fun toString(): String = "features.$context.$part"

    companion object {
        private const val FEATURES = "ru.kardiomonitor.features."
        private val SHARED_PARTS = setOf("domain", "api", "data", "fakes")

        /** Разбирает имя пакета или импорта; null — если это не `features.*`. */
        fun of(qualifiedName: String): FeaturePart? {
            if (!qualifiedName.startsWith(FEATURES)) return null
            val segments = qualifiedName.removePrefix(FEATURES).split('.')
            return FeaturePart(segments[0], segments.getOrElse(1) { "" })
        }
    }
}

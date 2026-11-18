package ru.kardiomonitor.architecture

/** Часть ограниченного контекста `features.<context>.<part>` в конкретном приложении. */
internal data class FeaturePart(val moduleName: String, val context: String, val part: String) {
    val isSlice: Boolean get() = part !in SHARED_PARTS

    override fun toString(): String = "$moduleName:features.$context.$part"

    companion object {
        private val SHARED_PARTS = setOf("domain", "api", "data", "fakes")

        /** Разбирает имя пакета или импорта относительно корневого пакета приложения. */
        fun of(module: SourceModule, qualifiedName: String): FeaturePart? {
            val prefix = "${module.rootPackage}.features."
            if (!qualifiedName.startsWith(prefix)) return null
            val segments = qualifiedName.removePrefix(prefix).split('.')
            return FeaturePart(module.name, segments[0], segments.getOrElse(1) { "" })
        }
    }
}

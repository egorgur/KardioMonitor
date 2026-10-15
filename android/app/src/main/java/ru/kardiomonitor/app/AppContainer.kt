package ru.kardiomonitor.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.kardiomonitor.BandManager

/**
 * Композиционный корень (ADR-0004): создаёт инфраструктуру один раз на процесс и передаёт её
 * через конструкторы в ViewModel-фабрики и Worker. Глобальных синглтонов кроме него нет.
 */
class AppContainer(context: Context) {
    /** Scope приложения: живёт, пока жив процесс; сбой одной задачи не отменяет остальные. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val bandManager: BandManager = BandManager(context, scope)
}

package ru.kardiomonitor.features.band.api

import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.band.domain.ConnectionProblem

/**
 * Сеанс связи с браслетом — порт контекста «Браслет». **Один на всё приложение**: браслет
 * держит одно соединение (ADR-0003), поэтому все сценарии работают только через него и
 * не создают своё соединение.
 */
interface BandConnection {
    val state: StateFlow<ConnectionState>

    /** Подключение по кнопке: соединение держится, пока пользователь не отключит его сам. */
    fun connect()

    fun disconnect()

    /** Приложение свёрнуто: закрыть соединение, если его не держит [holdOpen]. */
    fun closeIfIdle()

    fun dismissProblem()

    /**
     * Выполняет [block] в сеансе связи — по очереди с другими сценариями. Открытое соединение
     * используется как есть; иначе соединение открывается и после [block] закрывается, если
     * пользователь за это время не подключился сам.
     *
     * @throws SessionUnavailableException браслет недоступен; причина — в исключении.
     */
    suspend fun <T> withSession(isBackground: Boolean, block: suspend () -> T): T

    /**
     * Выполняет [block] в уже открытом сеансе — по очереди с другими сценариями; соединение не
     * открывает (действие пользователя на экране подключённого браслета).
     *
     * @throws SessionUnavailableException соединение закрыто ([ConnectionProblem.NotConnected]).
     */
    suspend fun <T> withOpenSession(block: suspend () -> T): T

    /**
     * Не даёт [closeIfIdle] закрыть соединение, пока выполняется [block] (например, «пульс сейчас»);
     * если приложение свернули за это время, соединение закрывается, когда [block] закончится.
     */
    suspend fun <T> holdOpen(block: suspend () -> T): T
}

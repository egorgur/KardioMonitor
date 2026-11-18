package ru.kardiomonitor.features.monitoring.api

/** Короткая фоновая попытка получить свежие минуты с браслета перед локальными проверками. */
fun interface ActivityHistoryRefresh {
    suspend fun refresh(): Boolean
}

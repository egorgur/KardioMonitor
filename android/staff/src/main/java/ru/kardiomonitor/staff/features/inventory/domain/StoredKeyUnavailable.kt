package ru.kardiomonitor.staff.features.inventory.domain

/** Шифротекст сохраняется для восстановления; ошибка не содержит секрет или cause. */
class StoredKeyUnavailable : Exception("Ключ недоступен в защищённом хранилище")

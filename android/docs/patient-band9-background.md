# Band 9: ограниченная фоновая проверка пациента

Поддержка пациента остаётся read-only: авторизация проверенной модели/прошивки,
заряд и GET настроек. Клиническая история, суточные шаги, live HR и SET **не включены**.
Непроверенные значения не подменяются нулём; проверка не создаёт минуты,
исторические watermark или события загрузки в клинику.

## Жизненный цикл

- `BackgroundSyncSchedule` восстанавливает расписание из сохранённого протокола:
  Band 6 — прежний `history-sync`, Band 9 — `band-read-only-status`. Переключение
  отменяет несовместимую работу (включая прежний `band-sync`). Выключение фона
  и отвязка отменяют обе периодические работы.
- Перед запуском Band 9 повторно проверяются привязка, включённый фон, Bluetooth
  permissions и CDM-ассоциация именно текущего адреса. В WorkManager передаётся
  только несекретная идентичность привязки, не ключ. Старая работа после новой
  выдачи не подключается; условие проверяется повторно под замком общего сеанса.
- Короткий foreground Worker использует тип `connectedDevice`, отдельное
  уведомление «Только заряд и настройки» и предел 60 секунд. Отказ Android в
  foreground-запуске не обходит ограничения; следующая периодическая попытка
  остаётся за WorkManager. Интервал 30 минут не гарантирует точное время при Doze.
- `SingleBandConnection` остаётся единственным владельцем канала. Сворачивание
  не обрывает занятый сеанс; после завершения он закрывается. Отмена во время
  авторизации/GET освобождает транспорт. Привязка перепроверяется до публикации
  заряда/настроек; маршрутизатор не передаёт команды в канал прежних credentials.
- Утерянный Keystore alias не генерируется заново при чтении. Привязка становится
  недоступной без fallback на Band 6, ciphertext не удаляется. Legacy-привязка
  без protocol по-прежнему означает только Band 6 и мигрирует ключ в Keystore.

## Проверка

JVM: `BandSessionLifecycleTest`, `BandStatusRefreshTest`,
`BackgroundSyncScheduleTest`, `ProtocolBandRouterTest`,
`SingleBandConnectionTest`, `SyncHistoryTest`.

Instrumentation: `BandBindingStorageTest` использует синтетический адрес/ключ,
случайный отдельный alias и namespaced preferences; проверяет повторное создание
репозитория, legacy Band 6, неизвестный protocol и потерю ключа.
Нельзя запускать путём переустановки рабочего приложения пациента. Для storage-проверки
есть отдельная сборка `./gradlew -PpatientValidation=true :app:assembleDebug :app:assembleDebugAndroidTest`:
applicationId `ru.kardiomonitor.patient.validation`, testApplicationId
`ru.kardiomonitor.patient.validation.test`, выходные APK в `app/build-validation/`.
В ней Application заменён на штатный `android.app.Application`: production composition
root/расписания/серверные подписки не стартуют. Эта сборка только для instrumentation
storage, не для запуска UI/worker; обычная сборка и её output не заменяются.
Тест-класс: `ru.kardiomonitor.features.band.data.BandBindingStorageTest`.
2026-11-13 на SM-S731B / Android 17 прошли **3/3** storage instrumentation теста
в отдельном validation-пакете. Рабочее `ru.kardiomonitor` не переустанавливалось.
Первый запуск выявил отсутствие runtime linkage SDK runner: в androidTest manifest
добавлены `uses-library` для `android.test.runner` и `android.test.base`, повтор PASS.
Общая Android проверка: 363 JVM-теста (пациент 224), lint и обе обычные debug-сборки PASS.

Настоящие process death/CDM/Doze/foreground-тесты требуют отдельного стенда с полным
composition root и фиктивной привязкой; vanilla Application их не проверяет.

Аппаратно ещё требуется подтвердить CDM/permissions на поддерживаемых Android,
foreground start/stop/cancel, Doze, перезапуск процесса и конкуренцию с Mi Fitness.
Этот файл не подтверждает аппаратный фон или клиническую пригодность Band 9.

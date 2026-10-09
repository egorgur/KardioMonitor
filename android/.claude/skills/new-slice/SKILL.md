---
name: new-slice
description: Создать вертикальный слайс KardioMonitor (features.<контекст>.<слайс>) — доменные типы, сценарий, ViewModel, секция Compose, фейки и тесты. Использовать, когда задача добавляет новый пользовательский сценарий или фоновую задачу.
argument-hint: <контекст>/<слайс>, например monitoring/restingheartrate
---

# Новый слайс: $ARGUMENTS

Правила — в документах, здесь только порядок. Прочитать перед началом:
`docs/architecture.md` (разделы «Анатомия слайса», «Правила зависимостей»),
глоссарий контекста в `docs/domain-model.md`.

## Шаги

1. **Термины.** Каждое новое понятие найти в глоссарии. Нет — добавить строку
   (термин, имя в коде, тип, правила) до кода. Имя слайса — из глоссария, без
   `Manager`/`Helper`/`Util`/`Data`/`Info`/`Item`.
2. **Правила предметной области — в домене, тест первым.** Валидация, расчёты,
   «что считать валидным» → `features/<контекст>/domain/` (value class с `init`,
   фабрика `of(...)`). Сначала падающий тест в `src/test/.../features/<контекст>/domain/`.
3. **Файлы слайса** — начать с минимума (architecture.md, «Начинайте просто»):

   ```
   features/<контекст>/<слайс>/
     <Слайс>Section.kt     # единственное public: @Composable-точка входа
     <Слайс>Content.kt     # internal stateless: (state, onAction) -> UI, с @Preview
     <Слайс>UiState.kt     # неизменяемый data class + sealed-события
     <Слайс>ViewModel.kt   # internal; StateFlow<UiState>; зависимости — в конструкторе
     <Глагол><Объект>.kt   # сценарий — только если нужен без UI или из Worker
   ```

4. **Зависимости — только разрешённые.** Браслет — через `features.band.api.BandConnection`
   (не `GattClient`, не `BandManager` из нового кода). История — через `ActivityHistory`.
   Реакция на другой слайс — подписка на Flow репозитория, не импорт слайса.
   Реализации из `features.*.data` создаёт композиционный корень.
5. **Сборка.** ViewModel-фабрика и Worker получают зависимости из `AppContainer`
   (до шага 3 миграции — из `MainActivity`). Никаких `getInstance()` и синглтонов.
6. **Фейки портов** — `src/test/.../features/<контекст>/fakes/Fake<Порт>.kt`, рукописные,
   на `MutableList`/`MutableStateFlow`. Моки не использовать.
7. **Тесты слайса** — через точку входа (сценарий или ViewModel) с фейками; проверять
   `UiState` и содержимое фейка, не вызовы. Имена: `` fun `поведение по-русски`() ``.
8. **Проверка** — показать вывод, не пересказ:
   ```bash
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```
   Упал `DependencyRulesTest`/`CodeRulesTest` — исправлять код, не тест.
9. **Документы.** Глоссарий; дерево пакетов в `docs/architecture.md`, если слайс новый;
   ADR, если принято значимое решение.
10. **Ревью** — подагент `architecture-reviewer` по диффу.

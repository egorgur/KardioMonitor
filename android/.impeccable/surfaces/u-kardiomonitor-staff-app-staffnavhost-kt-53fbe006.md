---
version: 1
slug: "u-kardiomonitor-staff-app-staffnavhost-kt-53fbe006"
primary_target: "staff/src/main/java/ru/kardiomonitor/staff/app/StaffNavHost.kt"
related_targets: []
---

# Staff utility

Mode: Operate

Audience: сотрудник клиники на одном защищённом телефоне-станции. Job: найти браслет по наклейке, проверить физическое устройство, показать QR и закрыть возврат без работы с техническими инструментами.

Task and states: список и поиск, пустой инвентарь, ручной импорт, метка, проверка с готовностью/предупреждением/отказом, защищённый QR, открытая выдача, возврат и журнал. Облачный вход отсутствует до отдельного spike.

## Direction contract

THESIS: интерфейс ведёт браслет через клиническую передачу из рук в руки. Он отказывается от dashboard-сетки и декоративных медицинских метафор: основа — спокойный нативный список, один следующий шаг и видимый статус готовности.

OWN-WORLD: холодный бело-голубой фон рабочих листов, глубокий хвойный shell, морская зелень готовности, янтарный только для предупреждений и красный только для отказов. Material 3 controls с радиусом 14 dp; цифры, MAC и измерения выровнены таблично, остальной текст — системная типографика.

STORY: сотрудник видит, какие браслеты доступны и на руках, добавляет или выбирает один, последовательно подтверждает связь, прошивку, заряд и минутный интервал, затем показывает пациенту QR. Возврат закрывает выдачу и оставляет ясное напоминание об удалении пациентского приложения.

FIRST VIEWPORT: compact Android phone opens on top app bar “Браслеты”, search field, a narrow handoff summary strip and a full-width list. Each row leads with the physical label, then MAC/model; readiness and issuance status occupy one aligned trailing zone. A single extended FAB adds a band. Root destinations use Material NavigationBar; detail and task screens use system Back.

FORM: candidate 7, “clinical specimen handoff labels”, seed `546e9c7f`. The world contributes restrained clinical palette, compact label hierarchy and one signature move: a segmented readiness strip whose cells resolve in order during inspection; navigation and controls remain standard Material 3.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

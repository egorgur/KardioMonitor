import type { PatientOverview, ProgramDayStatus } from "./api";
import { api } from "./api";
import { PROGRAM_DAY_LABELS, programPercent } from "./progress";
import { formatDay, shiftDay } from "./time";
import { navigate } from "./useHashRoute";
import { usePolling } from "./usePolling";

const PROGRESS_POLL_MS = 60_000;

export function RehabProgressCard({ patient, selectedDay }: { patient: PatientOverview; selectedDay: string }) {
  const result = usePolling(
    () => api.patientRehabProgress(patient.patient_id),
    PROGRESS_POLL_MS,
    [patient.patient_id],
  );
  if (!result.data) {
    return (
      <article className="card rehab-progress rehab-progress-state">
        <h2>Прогресс реабилитации</h2>
        <p className={result.error ? "form-error" : "muted"} role={result.error ? "alert" : undefined}>
          {result.error ? `Не удалось загрузить прогресс: ${result.error.message}` : "Загрузка прогресса…"}
        </p>
      </article>
    );
  }

  const progress = result.data;
  const elapsedDays = progress.is_finished ? progress.duration_days : (progress.program_day ?? 0);
  const percent = programPercent(progress.program_day, progress.duration_days, progress.is_finished);
  const statusByProgramDay = new Map(progress.days.map((day) => [day.program_day, day]));
  const headline = progress.is_finished
    ? `Программа завершена: норма выполнена в ${progress.met_days} днях из ${progress.completed_days}`
    : progress.program_day === null
      ? `Программа начнётся ${formatDay(progress.start_date)}`
      : `День ${progress.program_day} из ${progress.duration_days}`;

  return (
    <article className="card rehab-progress" aria-labelledby="rehab-progress-title">
      <div className="rehab-progress-head">
        <div>
          <h2 id="rehab-progress-title">Прогресс реабилитации</h2>
          <p>{headline}</p>
        </div>
        {progress.current_stage && (
          <span className="current-stage">
            Этап {progress.current_stage.number}: дни {progress.current_stage.first_day}–{progress.current_stage.last_day}
          </span>
        )}
      </div>

      <div className="program-progress-summary">
        <div
          className="program-progress-track"
          role="progressbar"
          aria-label="Пройдено дней программы"
          aria-valuemin={0}
          aria-valuemax={progress.duration_days}
          aria-valuenow={elapsedDays}
        >
          <span style={{ width: `${percent}%` }} />
        </div>
        <span>{elapsedDays} из {progress.duration_days} дней</span>
        <strong>Норма выполнена: {progress.met_days} из {progress.completed_days} завершённых</strong>
      </div>

      <div className="program-days" aria-label="Дни программы">
        {Array.from({ length: progress.duration_days }, (_, index) => {
          const programDay = index + 1;
          const date = shiftDay(progress.start_date, index);
          const day = statusByProgramDay.get(programDay);
          if (!day) {
            return (
              <span
                className="program-day is-future"
                aria-label={`День ${programDay}, ${formatDay(date)}: ещё не наступил`}
                key={programDay}
              >
                {programDay}
              </span>
            );
          }
          return (
            <button
              type="button"
              className={`program-day is-${day.status}${selectedDay === day.day ? " is-selected" : ""}`}
              aria-label={`День ${programDay}, ${formatDay(day.day)}: ${PROGRAM_DAY_LABELS[day.status]}`}
              aria-current={selectedDay === day.day ? "date" : undefined}
              title={`${formatDay(day.day)} · ${PROGRAM_DAY_LABELS[day.status]}`}
              key={programDay}
              onClick={() => navigate(patient.patient_id, day.day)}
            >
              {programDay}
            </button>
          );
        })}
      </div>
      <DayLegend />
      {result.error && <p className="data-stale">Не удалось обновить прогресс; показано последнее полученное значение.</p>}
    </article>
  );
}

function DayLegend() {
  const items: Array<[ProgramDayStatus | "future", string]> = [
    ["met", "Выполнено"],
    ["not_met", "Не выполнено"],
    ["no_data", "Нет данных"],
    ["in_progress", "Сегодня"],
    ["future", "Впереди"],
  ];
  return (
    <div className="program-day-legend" aria-label="Обозначения дней">
      {items.map(([status, label]) => (
        <span key={status}><i className={`is-${status}`} aria-hidden />{label}</span>
      ))}
    </div>
  );
}

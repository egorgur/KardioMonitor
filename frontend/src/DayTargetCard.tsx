import type { DayTargetProgress } from "./api";
import { boundedPercent, dayVerdict } from "./progress";

export function DayTargetCard({
  target,
  error,
}: {
  target: DayTargetProgress | null;
  error: Error | null;
}) {
  if (!target) {
    return (
      <article className="card day-target day-target-state">
        <h3>Норма дня</h3>
        <p className={error ? "form-error" : "muted"} role={error ? "alert" : undefined}>
          {error ? `Не удалось загрузить норму: ${error.message}` : "Загрузка нормы дня…"}
        </p>
      </article>
    );
  }

  const progress = target.progress;
  const verdict = dayVerdict(progress);
  if (!progress) {
    return (
      <article className="card day-target">
        <div className="day-target-head">
          <div><h3>Норма дня</h3><p>Дата не входит в программу реабилитации</p></div>
          <span className="day-verdict is-neutral">{verdict.label}</span>
        </div>
        {error && <p className="data-stale">Не удалось обновить данные; показано последнее полученное значение.</p>}
      </article>
    );
  }

  const withoutHeartRatePercent = progress.walking_minutes === 0
    ? 0
    : Math.round((progress.walking_minutes_without_heart_rate / progress.walking_minutes) * 100);
  const zone = progress.targets.training_zone;
  return (
    <article className="card day-target" aria-labelledby="day-target-title">
      <div className="day-target-head">
        <div>
          <h3 id="day-target-title">Норма дня</h3>
          <p>
            День {target.program_day} · целевой пульс {zone.lower_bpm}–{zone.upper_bpm} уд/мин
          </p>
        </div>
        <span className={`day-verdict is-${verdict.tone}`}>{verdict.label}</span>
      </div>

      <div className="day-target-metrics">
        <TargetMetric
          label="Тренировочные минуты"
          value={progress.training_minutes}
          target={progress.targets.training_minutes}
          unit="мин"
        />
        <TargetMetric label="Все шаги дня" value={progress.steps} target={progress.targets.steps} unit="шагов" />
      </div>

      <div className="day-target-foot">
        <span>Ходьба: {progress.walking_minutes} мин</span>
        <span>
          Без пульса: {progress.walking_minutes_without_heart_rate} мин
          {progress.walking_minutes > 0 ? ` (${withoutHeartRatePercent} % ходьбы)` : ""}
        </span>
      </div>
      {progress.walking_minutes_without_heart_rate > 0 && (
        <p className="day-target-hint">
          Тренировочных минут может быть больше: включите на браслете измерение пульса раз в минуту.
        </p>
      )}
      {!progress.is_final && <p className="day-target-hint">День ещё не закончился — итог предварительный.</p>}
      {error && <p className="data-stale">Не удалось обновить данные; показано последнее полученное значение.</p>}
    </article>
  );
}

function TargetMetric({
  label,
  value,
  target,
  unit,
}: {
  label: string;
  value: number;
  target: number;
  unit: string;
}) {
  const percent = boundedPercent(value, target);
  const ariaMaximum = Math.max(1, target);
  const ariaValue = target === 0 ? 1 : Math.min(value, target);
  return (
    <div className="target-metric">
      <div className="target-metric-head">
        <span>{label}</span>
        <strong>{value.toLocaleString("ru-RU")} из {target.toLocaleString("ru-RU")} {unit}</strong>
      </div>
      <div
        className="target-progress-track"
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={ariaMaximum}
        aria-valuenow={ariaValue}
      >
        <span style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

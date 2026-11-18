import { type FormEvent, useEffect, useId, useState } from "react";
import {
  ApiError,
  type PatientOverview,
  type RehabPlan,
  type RehabPlanUpdate,
  type RehabStage,
  api,
} from "./api";
import {
  addRehabStage,
  alertTimeInZone,
  cloneRehabPlanUpdate,
  rehabPlanPayload,
  removeRehabStage,
  toRehabPlanUpdate,
  validateRehabPlan,
} from "./rehabPlan";
import { formatDay, todayIn } from "./time";

export function RehabPlanEditor({ patient }: { patient: PatientOverview }) {
  const [plan, setPlan] = useState<RehabPlan | null>(null);
  const [draft, setDraft] = useState<RehabPlanUpdate | null>(null);
  const [isEditing, setEditing] = useState(false);
  const [isSaving, setSaving] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let isCurrent = true;
    setPlan(null);
    setLoadError(null);
    api.rehabPlan(patient.patient_id).then(
      (loaded) => {
        if (!isCurrent) return;
        setPlan(loaded);
        setDraft(toRehabPlanUpdate(loaded));
      },
      (failure: Error) => {
        if (isCurrent) setLoadError(failure.message || "Не удалось загрузить программу");
      },
    );
    return () => {
      isCurrent = false;
    };
  }, [patient.patient_id, reload]);

  function beginEditing() {
    if (!plan) return;
    setDraft(toRehabPlanUpdate(plan));
    setErrors([]);
    setSavedMessage(null);
    setEditing(true);
  }

  function cancelEditing() {
    if (plan) setDraft(toRehabPlanUpdate(plan));
    setErrors([]);
    setEditing(false);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!draft) return;
    const validationErrors = validateRehabPlan(draft);
    if (validationErrors.length > 0) {
      setErrors(validationErrors);
      return;
    }
    setSaving(true);
    setErrors([]);
    try {
      const saved = await api.updateRehabPlan(patient.patient_id, rehabPlanPayload(draft));
      setPlan(saved);
      setDraft(toRehabPlanUpdate(saved));
      setEditing(false);
      setSavedMessage(`Сохранено. Новые нормы действуют с ${formatDay(saved.effective_from)}.`);
    } catch (failure) {
      setErrors([failure instanceof ApiError ? failure.message : "Сервер недоступен. Попробуйте сохранить ещё раз."]);
    } finally {
      setSaving(false);
    }
  }

  if (loadError) {
    return (
      <article className="card rehab-plan rehab-plan-state">
        <h2>Программа и нормы</h2>
        <p className="form-error" role="alert">{loadError}</p>
        <button type="button" className="secondary-button" onClick={() => setReload((value) => value + 1)}>
          Повторить загрузку
        </button>
      </article>
    );
  }
  if (!plan || !draft) {
    return <p className="muted rehab-plan-loading">Загрузка программы реабилитации…</p>;
  }

  return (
    <article className="card rehab-plan" aria-labelledby="rehab-plan-title">
      <div className="rehab-plan-head">
        <div>
          <h2 id="rehab-plan-title">Программа и нормы</h2>
          <p>
            {plan.is_default ? "План по умолчанию" : `Текущая версия с ${formatDay(plan.effective_from)}`}
            {` · ${plan.time_zone}`}
          </p>
        </div>
        {!isEditing && (
          <button type="button" className="secondary-button" onClick={beginEditing}>
            Изменить программу
          </button>
        )}
      </div>

      {savedMessage && !isEditing && <p className="form-success" role="status">{savedMessage}</p>}

      {isEditing ? (
        <RehabPlanForm
          draft={draft}
          timeZone={plan.time_zone}
          errors={errors}
          isSaving={isSaving}
          onChange={setDraft}
          onCancel={cancelEditing}
          onSubmit={submit}
        />
      ) : (
        <RehabPlanSummary plan={plan} />
      )}
    </article>
  );
}

function RehabPlanSummary({ plan }: { plan: RehabPlan }) {
  const localAlertTime = alertTimeInZone(
    plan.monitoring_settings.daily_alert_time_utc,
    todayIn(plan.time_zone),
    plan.time_zone,
  );
  return (
    <div className="rehab-plan-summary">
      <dl className="rehab-plan-meta">
        <div><dt>Начало</dt><dd>{formatDay(plan.program.start_date)}</dd></div>
        <div><dt>Длительность</dt><dd>{plan.program.duration_days} дней</dd></div>
      </dl>
      <div className="rehab-stage-summary" aria-label="Этапы программы">
        {plan.program.stages.map((stage, index) => (
          <div className="rehab-stage-summary-row" key={`${stage.first_day}-${stage.last_day}`}>
            <strong>Этап {index + 1}</strong>
            <span>дни {stage.first_day}–{stage.last_day}</span>
            <span>{stage.targets.training_zone.lower_bpm}–{stage.targets.training_zone.upper_bpm} уд/мин</span>
            <span>{stage.targets.training_minutes} мин</span>
            <span>{stage.targets.steps.toLocaleString("ru-RU")} шагов</span>
          </div>
        ))}
      </div>
      <dl className="monitoring-summary">
        <div><dt>Порог ходьбы</dt><dd>{plan.monitoring_settings.walking_steps_threshold} шагов/мин</dd></div>
        <div><dt>Высокий пульс</dt><dd>выше {plan.monitoring_settings.high_heart_rate_threshold} уд/мин</dd></div>
        <div><dt>Почти не ходит</dt><dd>меньше {plan.monitoring_settings.low_activity_steps.toLocaleString("ru-RU")} шагов/день</dd></div>
        <div>
          <dt>Верхний порог тренировочных минут</dt>
          <dd>{plan.monitoring_settings.training_minutes_upper_threshold == null
            ? "Проверка отключена"
            : `${plan.monitoring_settings.training_minutes_upper_threshold} мин/сутки · оповещение при превышении`}</dd>
        </div>
        <div>
          <dt>Дневные оповещения</dt>
          <dd>{plan.monitoring_settings.daily_alert_time_utc.slice(0, 5)} UTC{localAlertTime ? ` · ${localAlertTime} у пациента` : ""}</dd>
        </div>
      </dl>
    </div>
  );
}

function RehabPlanForm({
  draft,
  timeZone,
  errors,
  isSaving,
  onChange,
  onCancel,
  onSubmit,
}: {
  draft: RehabPlanUpdate;
  timeZone: string;
  errors: string[];
  isSaving: boolean;
  onChange: (draft: RehabPlanUpdate) => void;
  onCancel: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const localAlertTime = alertTimeInZone(
    draft.monitoring_settings.daily_alert_time_utc,
    todayIn(timeZone),
    timeZone,
  );
  const updateProgram = (changes: Partial<RehabPlanUpdate["program"]>) =>
    onChange({ ...draft, program: { ...draft.program, ...changes } });
  const updateSettings = (changes: Partial<RehabPlanUpdate["monitoring_settings"]>) =>
    onChange({ ...draft, monitoring_settings: { ...draft.monitoring_settings, ...changes } });
  const updateStage = (index: number, update: (stage: RehabStage) => void) => {
    const next = cloneRehabPlanUpdate(draft);
    const stage = next.program.stages[index];
    if (stage) update(stage);
    onChange(next);
  };

  return (
    <form className="rehab-plan-form" onSubmit={onSubmit} noValidate>
      <div className="rehab-program-fields">
        <label className="form-field">
          Дата начала
          <input
            type="date"
            value={draft.program.start_date}
            onChange={(event) => updateProgram({ start_date: event.target.value })}
            required
          />
        </label>
        <NumberField
          label="Длительность, дней"
          value={draft.program.duration_days}
          min={1}
          max={365}
          onChange={(duration_days) => updateProgram({ duration_days })}
        />
      </div>

      <div className="rehab-section-head">
        <div>
          <h3>Этапы программы</h3>
          <p>Дни должны идти подряд и покрывать всю программу.</p>
        </div>
        <button
          type="button"
          className="secondary-button compact-button"
          disabled={draft.program.stages.every((stage) => stage.first_day === stage.last_day)}
          onClick={() => onChange(addRehabStage(draft))}
        >
          Добавить этап
        </button>
      </div>

      <div className="rehab-stages-editor">
        {draft.program.stages.map((stage, index) => (
          <fieldset className="rehab-stage-editor" key={index}>
            <legend>Этап {index + 1}</legend>
            <div className="rehab-stage-fields">
              <RangeField
                label="Дни"
                firstLabel={`Первый день этапа ${index + 1}`}
                secondLabel={`Последний день этапа ${index + 1}`}
                first={stage.first_day}
                second={stage.last_day}
                min={1}
                max={365}
                onFirst={(first_day) => updateStage(index, (item) => { item.first_day = first_day; })}
                onSecond={(last_day) => updateStage(index, (item) => { item.last_day = last_day; })}
              />
              <RangeField
                label="Зона пульса, уд/мин"
                firstLabel={`Нижняя граница пульса этапа ${index + 1}`}
                secondLabel={`Верхняя граница пульса этапа ${index + 1}`}
                first={stage.targets.training_zone.lower_bpm}
                second={stage.targets.training_zone.upper_bpm}
                min={40}
                max={220}
                onFirst={(lower_bpm) => updateStage(index, (item) => { item.targets.training_zone.lower_bpm = lower_bpm; })}
                onSecond={(upper_bpm) => updateStage(index, (item) => { item.targets.training_zone.upper_bpm = upper_bpm; })}
              />
              <NumberField
                label="Тренировочных минут"
                value={stage.targets.training_minutes}
                min={1}
                max={1440}
                onChange={(training_minutes) => updateStage(index, (item) => { item.targets.training_minutes = training_minutes; })}
              />
              <NumberField
                label="Шагов в день"
                value={stage.targets.steps}
                min={0}
                max={100000}
                onChange={(steps) => updateStage(index, (item) => { item.targets.steps = steps; })}
              />
              <button
                type="button"
                className="link-button rehab-remove-stage"
                disabled={draft.program.stages.length === 1}
                onClick={() => onChange(removeRehabStage(draft, index))}
              >
                Удалить этап
              </button>
            </div>
          </fieldset>
        ))}
      </div>

      <div className="rehab-section-head monitoring-head">
        <div>
          <h3>Параметры наблюдения</h3>
          <p>Пороговые значения управляют техническими оповещениями, а не медицинским заключением.</p>
        </div>
      </div>
      <div className="monitoring-fields">
        <NumberField
          label="Порог ходьбы, шагов/мин"
          hint="Больше порога — минута ходьбы."
          value={draft.monitoring_settings.walking_steps_threshold}
          min={1}
          max={250}
          onChange={(walking_steps_threshold) => updateSettings({ walking_steps_threshold })}
        />
        <NumberField
          label="Порог высокого пульса, уд/мин"
          hint="Выше порога — оповещение врачу."
          value={draft.monitoring_settings.high_heart_rate_threshold}
          min={60}
          max={250}
          onChange={(high_heart_rate_threshold) => updateSettings({ high_heart_rate_threshold })}
        />
        <NumberField
          label="Порог «почти не ходит», шагов/день"
          value={draft.monitoring_settings.low_activity_steps}
          min={0}
          max={100000}
          onChange={(low_activity_steps) => updateSettings({ low_activity_steps })}
        />
        <TrainingMinuteThresholdField
          value={draft.monitoring_settings.training_minutes_upper_threshold}
          onChange={(training_minutes_upper_threshold) => updateSettings({ training_minutes_upper_threshold })}
        />
        <label className="form-field">
          Время дневных оповещений, UTC
          <input
            type="time"
            value={draft.monitoring_settings.daily_alert_time_utc.slice(0, 5)}
            onChange={(event) => updateSettings({ daily_alert_time_utc: event.target.value })}
            required
          />
          <span className="field-hint">
            {localAlertTime ? `${localAlertTime} у пациента (${timeZone}) сегодня` : "Укажите время"}
          </span>
        </label>
      </div>

      {errors.length > 0 && (
        <div className="rehab-errors" role="alert">
          <strong>План не сохранён</strong>
          <ul>{errors.map((error) => <li key={error}>{error}</li>)}</ul>
        </div>
      )}
      <p className="rehab-effective-note">
        Изменения действуют с сегодняшнего дня пациента. Прошедшие дни остаются оценены по прежним нормам.
      </p>
      <div className="form-actions">
        <button type="submit" className="primary-button" disabled={isSaving}>
          {isSaving ? "Сохранение…" : "Сохранить программу"}
        </button>
        <button type="button" className="link-button" disabled={isSaving} onClick={onCancel}>
          Отменить изменения
        </button>
      </div>
    </form>
  );
}

function TrainingMinuteThresholdField({ value, onChange }: {
  value: number | null;
  onChange: (value: number | null) => void;
}) {
  const id = useId();
  const isInvalid = value !== null && (!Number.isInteger(value) || value < 1 || value > 1440);
  return (
    <label className="form-field" htmlFor={id}>
      Верхний порог тренировочных минут, мин/сутки
      <input
        id={id}
        type="number"
        inputMode="numeric"
        value={value === null || Number.isNaN(value) ? "" : value}
        min={1}
        max={1440}
        step={1}
        placeholder="Не задан"
        aria-label="Верхний порог тренировочных минут, мин/сутки"
        aria-describedby={`${id}-hint`}
        aria-invalid={isInvalid}
        onChange={(event) => onChange(event.target.value === "" ? null : Number(event.target.value))}
      />
      <span id={`${id}-hint`} className="field-hint">
        Пустое поле отключает проверку. Оповещение — только выше порога, независимо от нормы этапа и времени дневных оповещений.
      </span>
    </label>
  );
}

function NumberField({
  label,
  hint,
  value,
  onChange,
  min,
  max,
}: {
  label: string;
  hint?: string;
  value: number;
  onChange: (value: number) => void;
  min: number;
  max: number;
}) {
  return (
    <label className="form-field">
      {label}
      <input
        type="number"
        inputMode="numeric"
        value={Number.isNaN(value) ? "" : value}
        min={min}
        max={max}
        step={1}
        onChange={(event) => onChange(event.target.value === "" ? Number.NaN : Number(event.target.value))}
      />
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

function RangeField({
  label,
  firstLabel,
  secondLabel,
  first,
  second,
  min,
  max,
  onFirst,
  onSecond,
}: {
  label: string;
  firstLabel: string;
  secondLabel: string;
  first: number;
  second: number;
  min: number;
  max: number;
  onFirst: (value: number) => void;
  onSecond: (value: number) => void;
}) {
  const value = (input: HTMLInputElement) => input.value === "" ? Number.NaN : Number(input.value);
  return (
    <label className="form-field">
      {label}
      <span className="range-inputs">
        <input type="number" aria-label={firstLabel} value={Number.isNaN(first) ? "" : first} min={min} max={max} onChange={(event) => onFirst(value(event.target))} />
        <span aria-hidden>–</span>
        <input type="number" aria-label={secondLabel} value={Number.isNaN(second) ? "" : second} min={min} max={max} onChange={(event) => onSecond(value(event.target))} />
      </span>
    </label>
  );
}

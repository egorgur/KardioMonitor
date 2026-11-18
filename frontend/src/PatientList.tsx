import type { PatientOverview, PatientProgress } from "./api";
import { alertCountLabel } from "./alerts";
import { programPercent } from "./progress";
import { formatAgo, isFresh } from "./time";

interface Props {
  patients: PatientOverview[];
  progress: Map<string, PatientProgress>;
  alertCounts: Map<string, number>;
  totalAlerts: number;
  search: string;
  onSearch: (search: string) => void;
  onAdmit: () => void;
  selectedId: string | null;
  isAlertsSelected: boolean;
  onAlerts: () => void;
  onSelect: (id: string) => void;
  isLoading: boolean;
}

export function PatientList({
  patients,
  progress,
  alertCounts,
  totalAlerts,
  search,
  onSearch,
  onAdmit,
  selectedId,
  isAlertsSelected,
  onAlerts,
  onSelect,
  isLoading,
}: Props) {
  return (
    <nav className="patients" aria-label="Пациенты">
      <div className="patient-nav-head">
        <h2 className="section-label">Пациенты</h2>
        <button type="button" className="alerts-nav-button" aria-current={isAlertsSelected} onClick={onAlerts}>
          Оповещения
          {totalAlerts > 0 && <AlertBadge count={totalAlerts} />}
        </button>
      </div>
      <input
        className="search"
        type="search"
        placeholder="Поиск по ФИО"
        value={search}
        onChange={(event) => onSearch(event.target.value)}
      />
      <button type="button" className="link-button" onClick={onAdmit}>
        + Новый пациент
      </button>
      {isLoading && <p className="muted">Загрузка…</p>}
      {!isLoading && patients.length === 0 && <p className="muted">Никого не нашлось</p>}
      <ul>
        {patients.map((patient) => {
          const isLive = isFresh(patient.live_heart_rate_measured_at);
          const alertCount = alertCounts.get(patient.patient_id) ?? 0;
          return (
            <li key={patient.patient_id}>
              <button
                type="button"
                className="patient-row"
                aria-current={patient.patient_id === selectedId}
                onClick={() => onSelect(patient.patient_id)}
              >
                <span className="patient-name-line">
                  <span className="patient-name">{patient.name}</span>
                  {alertCount > 0 && <AlertBadge count={alertCount} />}
                </span>
                <span className="patient-meta">
                  {patient.last_minute_started_at
                    ? `история до ${formatAgo(patient.last_minute_started_at, patient.time_zone)}`
                    : "истории ещё нет"}
                </span>
                <ProgressLine progress={progress.get(patient.patient_id)} />
                {isLive && patient.live_heart_rate !== null && (
                  <span className="live-badge" title="Пульс сейчас">
                    <span className="live-dot" aria-hidden />
                    {patient.live_heart_rate}
                  </span>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

function AlertBadge({ count }: { count: number }) {
  return <span className="alert-count" aria-label={alertCountLabel(count)}>{count > 99 ? "99+" : count}</span>;
}

/** «День 12 из 42 · норма в 9 из 11 · сегодня 15/30 мин» (ТР-05, ТР-03). */
function ProgressLine({ progress }: { progress: PatientProgress | undefined }) {
  if (!progress) return null;
  const percent = programPercent(progress.program_day, progress.duration_days, progress.is_finished);
  const day = progress.is_finished
    ? `программа завершена · норма в ${progress.met_days} из ${progress.completed_days}`
    : progress.program_day === null
      ? "программа не началась"
      : `день ${progress.program_day} из ${progress.duration_days} · норма в ${progress.met_days} из ${progress.completed_days}`;
  const today = progress.today;
  return (
    <span className="patient-progress">
      <span className="patient-meta">{day}</span>
      <span
        className="patient-program-track"
        aria-hidden
      >
        <span style={{ width: `${percent}%` }} />
      </span>
      {today && (
        <span className="patient-meta patient-today">
          Сегодня: {today.training_minutes}/{today.target_training_minutes} мин · {today.steps.toLocaleString("ru-RU")}/{today.target_steps.toLocaleString("ru-RU")} шагов
          {today.is_met ? " · норма выполнена" : ""}
        </span>
      )}
    </span>
  );
}

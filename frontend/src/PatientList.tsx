import type { PatientOverview } from "./api";
import { formatAgo, isFresh } from "./time";

interface Props {
  patients: PatientOverview[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  isLoading: boolean;
}

export function PatientList({ patients, selectedId, onSelect, isLoading }: Props) {
  return (
    <nav className="patients" aria-label="Пациенты">
      <h2 className="section-label">Пациенты</h2>
      {isLoading && <p className="muted">Загрузка…</p>}
      <ul>
        {patients.map((patient) => {
          const isLive = isFresh(patient.live_heart_rate_measured_at);
          return (
            <li key={patient.patient_id}>
              <button
                type="button"
                className="patient-row"
                aria-current={patient.patient_id === selectedId}
                onClick={() => onSelect(patient.patient_id)}
              >
                <span className="patient-name">{patient.name}</span>
                <span className="patient-meta">
                  {patient.last_minute_started_at
                    ? `история до ${formatAgo(patient.last_minute_started_at, patient.time_zone)}`
                    : "истории ещё нет"}
                </span>
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

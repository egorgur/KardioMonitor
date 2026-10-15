import type { PatientOverview } from "./api";
import { DayView } from "./DayView";
import { LiveHeartRate } from "./LiveHeartRate";
import { formatDay, shiftDay, todayIn } from "./time";
import { navigate } from "./useHashRoute";

interface Props {
  patient: PatientOverview;
  /** День из адреса; null — сегодня в часовом поясе пациента. */
  day: string | null;
}

export function PatientDetail({ patient, day: requestedDay }: Props) {
  const today = todayIn(patient.time_zone);
  const day = requestedDay && requestedDay <= today ? requestedDay : today;
  const show = (target: string) => navigate(patient.patient_id, target === today ? undefined : target);

  return (
    <section className="detail">
      <div className="detail-head">
        <h1>{patient.name}</h1>
        <span className="muted">часовой пояс {patient.time_zone}</span>
      </div>

      <LiveHeartRate patient={patient} />

      <div className="day-bar">
        <button type="button" className="icon-button" onClick={() => show(shiftDay(day, -1))} aria-label="Предыдущий день">
          ‹
        </button>
        <span className="day-title">
          {day === today ? "Сегодня, " : ""}
          {formatDay(day)}
        </span>
        <button
          type="button"
          className="icon-button"
          onClick={() => show(shiftDay(day, 1))}
          disabled={day >= today}
          aria-label="Следующий день"
        >
          ›
        </button>
      </div>

      <DayView patient={patient} day={day} isToday={day === today} />
    </section>
  );
}

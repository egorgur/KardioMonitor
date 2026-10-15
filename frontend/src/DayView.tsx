import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { type DailySummary, type DayMinute, type PatientOverview, api } from "./api";
import { HEART_RATE_DOMAIN, hourIn } from "./time";
import { useChartColors } from "./useChartColors";
import { usePolling } from "./usePolling";

const TODAY_POLL_MS = 15_000;
const PAST_DAY_POLL_MS = 120_000;
const HOUR_TICKS = [0, 3, 6, 9, 12, 15, 18, 21, 24];

interface Props {
  patient: PatientOverview;
  day: string;
  isToday: boolean;
}

/** Итоги дня и графики: пульс по минутам, шаги по часам — в часовом поясе пациента. */
export function DayView({ patient, day, isToday }: Props) {
  const result = usePolling(
    () => api.patientDay(patient.patient_id, day),
    isToday ? TODAY_POLL_MS : PAST_DAY_POLL_MS,
    [patient.patient_id, day],
  );
  if (!result.data) return <p className="muted">{result.error ? result.error.message : "Загрузка дня…"}</p>;
  const { summary, minutes } = result.data;
  if (summary.recorded_minutes === 0) {
    return (
      <article className="card empty">
        За этот день телефон ещё не присылал историю. Синхронизируйте браслет в приложении — минуты
        появятся здесь автоматически.
      </article>
    );
  }
  return (
    <>
      <SummaryTiles summary={summary} />
      <HeartRateChart minutes={minutes} timeZone={patient.time_zone} />
      <StepsChart minutes={minutes} timeZone={patient.time_zone} />
    </>
  );
}

function SummaryTiles({ summary }: { summary: DailySummary }) {
  const tiles: [string, string][] = [
    ["Шаги", summary.steps.toLocaleString("ru-RU")],
    ["Средний пульс", summary.average_heart_rate === null ? "—" : `${summary.average_heart_rate}`],
    [
      "Пульс мин–макс",
      summary.min_heart_rate === null ? "—" : `${summary.min_heart_rate}–${summary.max_heart_rate}`,
    ],
    ["Покрытие пульсом", `${summary.heart_rate_coverage_percent} %`],
    ["Без браслета", `${summary.not_worn_minutes} мин`],
    ["Записано", `${summary.recorded_minutes} мин`],
  ];
  return (
    <div className="tiles">
      {tiles.map(([label, value]) => (
        <div className="tile" key={label}>
          <span className="tile-label">{label}</span>
          <span className="tile-value">{value}</span>
        </div>
      ))}
    </div>
  );
}

function minuteOfDay(iso: string, timeZone: string): number {
  const parts = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone })
    .formatToParts(new Date(iso));
  const value = (type: string) => Number(parts.find((part) => part.type === type)?.value ?? 0);
  return value("hour") * 60 + value("minute");
}

const hourLabel = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, "0")}:00`;

function HeartRateChart({ minutes, timeZone }: { minutes: DayMinute[]; timeZone: string }) {
  const colors = useChartColors();
  // null — замера не было: линия прерывается, а не соединяет соседние замеры
  const points = minutes.map((minute) => ({ t: minuteOfDay(minute.started_at, timeZone), bpm: minute.heart_rate }));
  return (
    <article className="card">
      <h3 className="chart-title">Пульс по минутам, уд/мин</h3>
      <div className="chart">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={points} margin={{ top: 8, right: 12, bottom: 0, left: -12 }}>
            <CartesianGrid stroke={colors.grid} vertical={false} />
            <XAxis dataKey="t" type="number" domain={[0, 1440]} ticks={HOUR_TICKS.map((h) => h * 60)} tickFormatter={hourLabel} stroke={colors.axis} fontSize={12} tickLine={false} />
            <YAxis domain={HEART_RATE_DOMAIN} stroke={colors.axis} fontSize={12} tickLine={false} axisLine={false} allowDecimals={false} />
            <Tooltip
              formatter={(value) => [`${value} уд/мин`, "Пульс"]}
              labelFormatter={(t) => {
                const total = Number(t);
                return `${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
              }}
            />
            <Line dataKey="bpm" stroke={colors.heart} strokeWidth={2} dot={false} connectNulls={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </article>
  );
}

function StepsChart({ minutes, timeZone }: { minutes: DayMinute[]; timeZone: string }) {
  const colors = useChartColors();
  const byHour = Array.from({ length: 24 }, (_, hour) => ({ hour, steps: 0 }));
  for (const minute of minutes) {
    const bucket = byHour[hourIn(minute.started_at, timeZone)];
    if (bucket) bucket.steps += minute.steps;
  }
  return (
    <article className="card">
      <h3 className="chart-title">Шаги по часам</h3>
      <div className="chart">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={byHour} margin={{ top: 8, right: 12, bottom: 0, left: -12 }}>
            <CartesianGrid stroke={colors.grid} vertical={false} />
            <XAxis dataKey="hour" tickFormatter={(hour: number) => `${hour}`} stroke={colors.axis} fontSize={12} tickLine={false} interval={2} />
            <YAxis stroke={colors.axis} fontSize={12} tickLine={false} axisLine={false} allowDecimals={false} />
            <Tooltip formatter={(value) => [`${value}`, "Шаги"]} labelFormatter={(hour) => `${hour}:00–${Number(hour) + 1}:00`} />
            <Bar dataKey="steps" fill={colors.steps} radius={[3, 3, 0, 0]} isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </article>
  );
}

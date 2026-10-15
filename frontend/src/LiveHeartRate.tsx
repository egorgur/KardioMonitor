import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { type PatientOverview, api } from "./api";
import { HEART_RATE_DOMAIN, formatAgo, formatClock, isFresh } from "./time";
import { useChartColors } from "./useChartColors";
import { usePolling } from "./usePolling";

const LIVE_POLL_MS = 2000;
const WINDOW_SECONDS = 300;

/** «Пульс сейчас»: крупное значение и график последних 5 минут; обновляется каждые 2 с. */
export function LiveHeartRate({ patient }: { patient: PatientOverview }) {
  const live = usePolling(() => api.liveHeartRate(patient.patient_id, WINDOW_SECONDS), LIVE_POLL_MS, [patient.patient_id]);
  const colors = useChartColors();
  const readings = live.data?.readings ?? [];
  const latest = readings.at(-1) ?? null;
  const isLive = latest !== null && isFresh(latest.measured_at);
  const points = readings.map((reading) => ({ at: new Date(reading.measured_at).getTime(), bpm: reading.heart_rate }));
  const now = Date.now();

  return (
    <article className={`card live ${isLive ? "is-live" : ""}`}>
      <div className="live-value">
        <span className="section-label">Пульс сейчас</span>
        <div className="big-number">
          <span className="heart" aria-hidden>
            ♥
          </span>
          {isLive ? latest.heart_rate : "—"}
          <span className="unit">уд/мин</span>
        </div>
        <span className="muted">
          {isLive
            ? `обновлено ${formatAgo(latest.measured_at, patient.time_zone)}`
            : latest
              ? `последнее значение ${formatAgo(latest.measured_at, patient.time_zone)}`
              : "Включите «Пульс сейчас» в приложении на телефоне"}
        </span>
      </div>
      <div className="live-chart" aria-label="Пульс за последние 5 минут">
        {points.length > 1 && (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
              <XAxis
                dataKey="at"
                type="number"
                domain={[now - WINDOW_SECONDS * 1000, now]}
                tickFormatter={(at: number) => formatClock(new Date(at).toISOString(), patient.time_zone)}
                stroke={colors.axis}
                tickLine={false}
                fontSize={12}
                tickCount={4}
              />
              <YAxis domain={HEART_RATE_DOMAIN} tickCount={4} stroke={colors.axis} tickLine={false} axisLine={false} fontSize={12} allowDecimals={false} />
              <Tooltip
                formatter={(value) => [`${value} уд/мин`, "Пульс"]}
                labelFormatter={(at) => formatClock(new Date(Number(at)).toISOString(), patient.time_zone)}
              />
              <Line dataKey="bpm" stroke={colors.heart} strokeWidth={2} dot={false} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </article>
  );
}

import { type FormEvent, useEffect, useState } from "react";
import {
  type ClinicianLiveHeartRateRequest,
  type PatientOverview,
  api,
  isSignedOut,
} from "./api";
import {
  DEFAULT_LIVE_HEART_RATE_DURATION,
  MAX_LIVE_HEART_RATE_DURATION,
  MIN_LIVE_HEART_RATE_DURATION,
  isActiveLiveHeartRateRequest,
  liveHeartRateRequestDescription,
  parseLiveHeartRateDuration,
} from "./liveHeartRateRequest";
import { formatAgo } from "./time";
import { usePolling } from "./usePolling";

const REQUEST_POLL_MS = 2000;

export function LiveHeartRateRequestControl({
  patient,
  onSignedOut,
}: {
  patient: PatientOverview;
  onSignedOut: () => void;
}) {
  const [duration, setDuration] = useState(String(DEFAULT_LIVE_HEART_RATE_DURATION));
  const [created, setCreated] = useState<ClinicianLiveHeartRateRequest | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const latest = usePolling(
    () => api.latestLiveHeartRateRequest(patient.patient_id),
    REQUEST_POLL_MS,
    [patient.patient_id, refresh],
  );
  const request = latest.data?.request ?? created;
  const parsedDuration = parseLiveHeartRateDuration(duration);
  const active = request !== null && isActiveLiveHeartRateRequest(request.status);

  useEffect(() => {
    setCreated(null);
    setSubmitError(null);
  }, [patient.patient_id]);

  useEffect(() => {
    if (isSignedOut(latest.error)) onSignedOut();
  }, [latest.error, onSignedOut]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (parsedDuration === null || active) return;
    setIsSubmitting(true);
    setSubmitError(null);
    try {
      const next = await api.requestLiveHeartRate(patient.patient_id, parsedDuration);
      setCreated(next);
      setRefresh((value) => value + 1);
    } catch (failure) {
      if (isSignedOut(failure)) {
        onSignedOut();
      } else {
        setSubmitError(failure instanceof Error ? failure.message : "Не удалось отправить запрос");
        setRefresh((value) => value + 1);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  const isInitialLoading = latest.data === null && latest.error === null && created === null;

  return (
    <section className="live-request" aria-labelledby="live-request-title">
      <div className="live-request-head">
        <div>
          <h2 id="live-request-title">Запросить непрерывный пульс</h2>
          <p>Телефон запустит измерение с браслета на заданное время.</p>
        </div>
        {request && (
          <div className={`live-request-status is-${request.status}`} role="status">
            <span aria-hidden />
            {liveHeartRateRequestDescription(request)}
          </div>
        )}
      </div>

      {request?.status === "waiting_for_phone" && (
        <p className="live-request-notice">
          Телефон получит запрос при следующей синхронизации, обычно в течение 30 минут.
        </p>
      )}

      {request && (
        <p className="live-request-meta">
          Запрошено {formatAgo(request.requested_at, patient.time_zone)}
          {request.finished_at ? ` · завершено ${formatAgo(request.finished_at, patient.time_zone)}` : ""}
        </p>
      )}

      {!active && !isInitialLoading && (
        <form className="live-request-form" onSubmit={submit}>
          <label className="live-request-duration">
            Длительность
            <span>
              <input
                type="number"
                inputMode="numeric"
                min={MIN_LIVE_HEART_RATE_DURATION}
                max={MAX_LIVE_HEART_RATE_DURATION}
                step="1"
                value={duration}
                onChange={(event) => setDuration(event.target.value)}
                aria-describedby="live-request-duration-hint"
                aria-invalid={parsedDuration === null}
              />
              мин
            </span>
          </label>
          <button className="primary-button" type="submit" disabled={parsedDuration === null || isSubmitting}>
            {isSubmitting ? "Отправляем…" : "Запросить пульс"}
          </button>
          <span id="live-request-duration-hint" className={parsedDuration === null ? "form-error" : "field-hint"}>
            {parsedDuration === null ? "Укажите целое число от 1 до 60" : "От 1 до 60 минут"}
          </span>
        </form>
      )}

      {isInitialLoading && <p className="live-request-meta">Проверяем последний запрос…</p>}
      {latest.error && latest.data === null && created === null && (
        <p className="form-error">Не удалось проверить запрос: {latest.error.message}</p>
      )}
      {latest.error && (latest.data !== null || created !== null) && (
        <p className="data-stale">Состояние временно не обновляется. Показаны последние данные.</p>
      )}
      {submitError && <p className="form-error">{submitError}</p>}
    </section>
  );
}
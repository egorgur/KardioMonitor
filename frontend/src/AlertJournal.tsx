import { useEffect, useMemo, useState } from "react";
import { ALERT_KIND_LABELS, alertCountLabel, alertCreatedLabel, alertDescription, alertEventLabel } from "./alerts";
import { type AlertKind, type ClinicianAlert, type PatientOverview, api, isSignedOut } from "./api";
import { usePolling } from "./usePolling";

const ALERTS_POLL_MS = 30_000;
const ALL_KINDS = "all";

type JournalMode =
  | { kind: "global"; patients: PatientOverview[]; onOpenPatient: (patientId: string) => void }
  | { kind: "patient"; patient: PatientOverview };

export function AlertJournal({
  mode,
  onViewed,
  onSignedOut,
}: {
  mode: JournalMode;
  onViewed: () => void;
  onSignedOut: () => void;
}) {
  const [kind, setKind] = useState<AlertKind | typeof ALL_KINDS>(ALL_KINDS);
  const [showViewed, setShowViewed] = useState(true);
  const [viewedLocally, setViewedLocally] = useState<Set<string>>(() => new Set());
  const [markError, setMarkError] = useState<string | null>(null);
  const [markingId, setMarkingId] = useState<string | null>(null);
  const selectedKind = kind === ALL_KINDS ? undefined : kind;
  const scopeKey = mode.kind === "global" ? "global" : mode.patient.patient_id;
  const result = usePolling(
    () => mode.kind === "global" ? api.alerts(selectedKind) : api.patientAlerts(mode.patient.patient_id, selectedKind),
    ALERTS_POLL_MS,
    [scopeKey, kind],
  );

  useEffect(() => {
    if (isSignedOut(result.error)) onSignedOut();
  }, [result.error, onSignedOut]);

  useEffect(() => {
    if (!result.data || viewedLocally.size === 0) return;
    const confirmed = new Set(result.data.filter((alert) => alert.is_viewed).map((alert) => alert.alert_id));
    if (![...viewedLocally].some((id) => confirmed.has(id))) return;
    setViewedLocally((current) => new Set([...current].filter((id) => !confirmed.has(id))));
  }, [result.data, viewedLocally]);

  const directory = useMemo(() => {
    const patients = mode.kind === "global" ? mode.patients : [mode.patient];
    return new Map(patients.map((patient) => [patient.patient_id, patient]));
  }, [mode]);
  const alerts = (result.data ?? []).map((alert) => ({
    alert,
    isViewed: alert.is_viewed || viewedLocally.has(alert.alert_id),
  }));
  const visible = showViewed ? alerts : alerts.filter((item) => !item.isViewed);
  const unviewed = alerts.filter((item) => !item.isViewed).length;

  async function markViewed(alert: ClinicianAlert) {
    setMarkingId(alert.alert_id);
    setMarkError(null);
    try {
      await api.markAlertViewed(alert.patient_id, alert.alert_id);
      setViewedLocally((current) => new Set(current).add(alert.alert_id));
      onViewed();
    } catch (failure) {
      if (isSignedOut(failure)) onSignedOut();
      setMarkError(failure instanceof Error ? failure.message : "Не удалось отметить оповещение");
    } finally {
      setMarkingId(null);
    }
  }

  return (
    <section className="card alert-journal" aria-labelledby={`${scopeKey}-alerts-title`}>
      <div className="alert-journal-head">
        <div>
          <h2 id={`${scopeKey}-alerts-title`}>
            {mode.kind === "global" ? "Оповещения" : "Оповещения пациента"}
          </h2>
          <p>{alertCountLabel(unviewed)} · в журнале {Math.min(500, result.data?.length ?? 0)}</p>
        </div>
        <div className="alert-filters">
          <label>
            Вид
            <select value={kind} onChange={(event) => setKind(event.target.value as AlertKind | typeof ALL_KINDS)}>
              <option value={ALL_KINDS}>Все виды</option>
              {Object.entries(ALERT_KIND_LABELS).map(([value, label]) => (
                <option value={value} key={value}>{label}</option>
              ))}
            </select>
          </label>
          <label className="alert-viewed-toggle">
            <input type="checkbox" checked={showViewed} onChange={(event) => setShowViewed(event.target.checked)} />
            Показывать просмотренные
          </label>
        </div>
      </div>

      {!result.data && !result.error && <p className="muted">Загрузка оповещений…</p>}
      {result.error && !result.data && <p className="form-error" role="alert">{result.error.message}</p>}
      {result.error && result.data && <p className="data-stale">Не удалось обновить журнал; показаны последние полученные данные.</p>}
      {markError && <p className="form-error" role="alert">{markError}</p>}

      {result.data && visible.length === 0 && (
        <p className="alert-empty">
          {alerts.length === 0 ? "Оповещений этого вида нет." : "Непросмотренных оповещений этого вида нет."}
        </p>
      )}
      {visible.length > 0 && (
        <div className="alert-feed">
          {visible.map(({ alert, isViewed }) => {
            const patient = directory.get(alert.patient_id);
            const timeZone = patient?.time_zone ?? "UTC";
            return (
              <article className={`alert-entry${isViewed ? " is-viewed" : ""}`} key={alert.alert_id}>
                <span className={`alert-kind-marker is-${alert.kind}`} aria-hidden />
                <div className="alert-entry-body">
                  <div className="alert-entry-head">
                    <strong>{ALERT_KIND_LABELS[alert.kind]}</strong>
                    <span>{alertEventLabel(alert, timeZone)}</span>
                  </div>
                  {mode.kind === "global" && (
                    <button type="button" className="alert-patient-link" onClick={() => mode.onOpenPatient(alert.patient_id)}>
                      {patient?.name ?? "Пациент"}
                    </button>
                  )}
                  <p>{alertDescription(alert)}</p>
                  <div className="alert-entry-meta">
                    <span>Создано {alertCreatedLabel(alert, timeZone)}</span>
                    {isViewed ? (
                      <span>Просмотрено{alert.viewed_at ? ` ${alertCreatedLabel({ ...alert, created_at: alert.viewed_at }, timeZone)}` : ""}</span>
                    ) : (
                      <button
                        type="button"
                        className="secondary-button compact-button"
                        disabled={markingId === alert.alert_id}
                        onClick={() => void markViewed(alert)}
                      >
                        {markingId === alert.alert_id ? "Отмечаем…" : "Отметить просмотренным"}
                      </button>
                    )}
                  </div>
                </div>
              </article>
            );
          })}
        </div>
      )}

      <p className="alert-disclaimer">
        Оповещения приходят с задержкой синхронизации до 30–60 минут и не заменяют экстренную помощь.
        Данные браслета не являются медицинским заключением.
      </p>
    </section>
  );
}

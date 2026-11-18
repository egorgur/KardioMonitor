import { type ReactNode, useEffect, useState } from "react";
import { AdmitPatient, EnrollmentCodeNotice } from "./AdmitPatient";
import { AlertJournal } from "./AlertJournal";
import { type AdmittedPatient, type Clinician, api, isSignedOut } from "./api";
import { PatientDetail } from "./PatientDetail";
import { PatientList } from "./PatientList";
import { SignIn } from "./SignIn";
import { navigate, navigateToAlerts, useHashRoute } from "./useHashRoute";
import { usePolling } from "./usePolling";

const PATIENTS_POLL_MS = 5000;
const PROGRESS_POLL_MS = 60_000;
const ALERT_COUNTS_POLL_MS = 30_000;

/** `undefined` — ещё не знаем, вошёл ли врач; `null` — не вошёл. */
type Session = Clinician | null | undefined;

export function App() {
  const [clinician, setClinician] = useState<Session>(undefined);

  useEffect(() => {
    api.me().then(setClinician, () => setClinician(null));
  }, []);

  async function signOut() {
    await api.signOut().catch(() => undefined);
    setClinician(null);
  }

  return (
    <div className="page">
      <header className="header">
        <div className="brand">
          <span className="brand-mark" aria-hidden>
            ♥
          </span>
          KardioMonitor
        </div>
        <span className="header-note">Панель клиники · данные браслета через телефон пациента</span>
        {clinician && (
          <span className="header-user">
            {clinician.name}
            <button type="button" className="link-button" onClick={signOut}>
              Выйти
            </button>
          </span>
        )}
      </header>

      {clinician === null && <SignIn onSignedIn={setClinician} />}
      {clinician && <Patients onSignedOut={() => setClinician(null)} />}
    </div>
  );
}

function Patients({ onSignedOut }: { onSignedOut: () => void }) {
  const [search, setSearch] = useState("");
  const [isAdmitting, setAdmitting] = useState(false);
  const [admitted, setAdmitted] = useState<AdmittedPatient | null>(null);
  const [alertRevision, setAlertRevision] = useState(0);
  const patients = usePolling(() => api.patients(search), PATIENTS_POLL_MS, [search]);
  const patientDirectory = usePolling(() => api.patients(), PROGRESS_POLL_MS, [admitted]);
  const progress = usePolling(api.rehabProgress, PROGRESS_POLL_MS, [admitted]);
  const alertCounts = usePolling(api.unviewedAlertCounts, ALERT_COUNTS_POLL_MS, [alertRevision, admitted]);
  const route = useHashRoute();
  const list = patients.data ?? [];
  const directory = patientDirectory.data ?? list;
  const selected = route.view === "patients"
    ? list.find((patient) => patient.patient_id === route.patientId)
      ?? directory.find((patient) => patient.patient_id === route.patientId)
      ?? list[0]
      ?? null
    : null;
  const progressById = new Map((progress.data ?? []).map((row) => [row.patient_id, row]));
  const alertCountById = new Map((alertCounts.data ?? []).map((row) => [row.patient_id, row.unviewed_alerts]));
  const totalAlerts = [...alertCountById.values()].reduce((total, count) => total + count, 0);

  useEffect(() => {
    if ([patients.error, patientDirectory.error, progress.error, alertCounts.error].some(isSignedOut)) onSignedOut();
  }, [patients.error, patientDirectory.error, progress.error, alertCounts.error, onSignedOut]);

  if (patients.error && !patients.data) {
    return <Notice title="Сервер недоступен">{patients.error.message}</Notice>;
  }
  const admit = () => {
    setAdmitted(null);
    setAdmitting(true);
  };
  return (
    <main className="layout">
      <PatientList
        patients={list}
        progress={progressById}
        alertCounts={alertCountById}
        totalAlerts={totalAlerts}
        search={search}
        onSearch={setSearch}
        onAdmit={admit}
        selectedId={isAdmitting || route.view === "alerts" ? null : (selected?.patient_id ?? null)}
        isAlertsSelected={route.view === "alerts"}
        onAlerts={() => {
          setAdmitting(false);
          setAdmitted(null);
          navigateToAlerts();
        }}
        onSelect={(patientId) => {
          setAdmitting(false);
          setAdmitted(null);
          navigate(patientId);
        }}
        isLoading={!patients.data}
      />
      {route.view === "alerts" ? (
        <AlertJournal
          mode={{
            kind: "global",
            patients: directory,
            onOpenPatient: (patientId) => {
              setSearch("");
              navigate(patientId);
            },
          }}
          onViewed={() => setAlertRevision((value) => value + 1)}
          onSignedOut={onSignedOut}
        />
      ) : isAdmitting ? (
        <AdmitPatient
          onCancel={() => setAdmitting(false)}
          onAdmitted={(patient) => {
            setAdmitting(false);
            setAdmitted(patient);
            navigate(patient.patient_id);
          }}
        />
      ) : (
        <div>
          {admitted && <EnrollmentCodeNotice name={admitted.name} code={admitted} />}
          {selected ? (
            <PatientDetail
              key={selected.patient_id}
              patient={selected}
              day={route.patientId === selected.patient_id ? route.day : null}
              onAlertViewed={() => setAlertRevision((value) => value + 1)}
              onSignedOut={onSignedOut}
            />
          ) : (
            patients.data &&
            !search && (
              <Notice title="Пациентов пока нет">
                Создайте пациента кнопкой «Новый пациент». Пациента, зарегистрированного с телефона по имени, назначает
                врачу администратор сервера: <code>uv run python -m kardiomonitor.app.admin assign</code>.
              </Notice>
            )
          )}
        </div>
      )}
    </main>
  );
}

function Notice({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="notice">
      <h2>{title}</h2>
      <p>{children}</p>
    </section>
  );
}

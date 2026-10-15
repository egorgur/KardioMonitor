import type { ReactNode } from "react";
import { ApiError, api } from "./api";
import { PatientDetail } from "./PatientDetail";
import { PatientList } from "./PatientList";
import { navigate, useHashRoute } from "./useHashRoute";
import { usePolling } from "./usePolling";

const PATIENTS_POLL_MS = 5000;

export function App() {
  const patients = usePolling(api.patients, PATIENTS_POLL_MS, []);
  const route = useHashRoute();
  const list = patients.data ?? [];
  const selected = list.find((patient) => patient.patient_id === route.patientId) ?? list[0] ?? null;

  return (
    <div className="page">
      <header className="header">
        <div className="brand">
          <span className="brand-mark" aria-hidden>
            ♥
          </span>
          KardioMonitor
        </div>
        <span className="header-note">Панель клиники · демо: данные браслета через телефон пациента</span>
      </header>

      {patients.error instanceof ApiError && patients.error.status === 401 ? (
        <Notice title="Нужен ключ панели">
          Откройте ссылку вида <code>https://…ngrok…/?key=&lt;ключ&gt;</code> — ключ задан в
          <code> KARDIO_DASHBOARD_KEY</code> на сервере.
        </Notice>
      ) : patients.error && !patients.data ? (
        <Notice title="Сервер недоступен">{patients.error.message}</Notice>
      ) : patients.data && list.length === 0 ? (
        <Notice title="Пациентов пока нет">
          Откройте приложение KardioMonitor на телефоне, укажите адрес сервера и имя пациента — он
          появится здесь через несколько секунд.
        </Notice>
      ) : (
        <main className="layout">
          <PatientList
            patients={list}
            selectedId={selected?.patient_id ?? null}
            onSelect={(patientId) => navigate(patientId)}
            isLoading={!patients.data}
          />
          {selected && (
            <PatientDetail
              key={selected.patient_id}
              patient={selected}
              day={route.patientId === selected.patient_id ? route.day : null}
            />
          )}
        </main>
      )}
    </div>
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

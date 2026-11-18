import { useEffect, useState } from "react";

/** Пациент и день — в адресе (`#/patients/<id>/<YYYY-MM-DD>`): ссылку можно открыть на проекторе. */
export interface HashRoute {
  view: "patients" | "alerts";
  patientId: string | null;
  day: string | null;
}

const ROUTE = /^#\/patients\/([\w-]+)(?:\/(\d{4}-\d{2}-\d{2}))?$/;

export function parseHashRoute(hash: string): HashRoute {
  if (hash === "#/alerts") return { view: "alerts", patientId: null, day: null };
  const match = hash.match(ROUTE);
  return { view: "patients", patientId: match?.[1] ?? null, day: match?.[2] ?? null };
}

function readRoute(): HashRoute {
  return parseHashRoute(window.location.hash);
}

export function navigate(patientId: string, day?: string): void {
  window.location.hash = day ? `#/patients/${patientId}/${day}` : `#/patients/${patientId}`;
}

export function navigateToAlerts(): void {
  window.location.hash = "#/alerts";
}

export function useHashRoute(): HashRoute {
  const [route, setRoute] = useState(readRoute);
  useEffect(() => {
    const onHashChange = () => setRoute(readRoute());
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);
  return route;
}

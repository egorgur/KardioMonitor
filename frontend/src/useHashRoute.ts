import { useEffect, useState } from "react";

/** Пациент и день — в адресе (`#/patients/<id>/<YYYY-MM-DD>`): ссылку можно открыть на проекторе. */
export interface HashRoute {
  patientId: string | null;
  day: string | null;
}

const ROUTE = /^#\/patients\/([\w-]+)(?:\/(\d{4}-\d{2}-\d{2}))?/;

function readRoute(): HashRoute {
  const match = window.location.hash.match(ROUTE);
  return { patientId: match?.[1] ?? null, day: match?.[2] ?? null };
}

export function navigate(patientId: string, day?: string): void {
  window.location.hash = day ? `#/patients/${patientId}/${day}` : `#/patients/${patientId}`;
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

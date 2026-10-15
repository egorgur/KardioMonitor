// Клиент API панели клиники. Контракт — backend/docs/api-contract.md и /docs сервера.

export interface PatientOverview {
  patient_id: string;
  name: string;
  time_zone: string;
  registered_at: string;
  last_minute_started_at: string | null;
  live_heart_rate: number | null;
  live_heart_rate_measured_at: string | null;
}

export interface DailySummary {
  recorded_minutes: number;
  steps: number;
  average_heart_rate: number | null;
  min_heart_rate: number | null;
  max_heart_rate: number | null;
  heart_rate_coverage_percent: number;
  not_worn_minutes: number;
}

export type ActivityKind = "not_worn" | "charging" | "sleep" | "other";

export interface DayMinute {
  started_at: string;
  steps: number;
  heart_rate: number | null;
  kind: ActivityKind;
}

export interface PatientDay {
  day: string;
  time_zone: string;
  summary: DailySummary;
  minutes: DayMinute[];
}

export interface LiveHeartRateReading {
  measured_at: string;
  heart_rate: number;
}

export interface LiveHeartRateWindow {
  window_seconds: number;
  readings: LiveHeartRateReading[];
}

/** Ошибка API в формате problem+json: показываем `title`, решаем по `code`. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, title: string) {
    super(title);
    this.status = status;
    this.code = code;
  }
}

const KEY_STORAGE = "kardio-dashboard-key";

/** Ключ панели: из ссылки `?key=…` (запоминается на вкладку) или сохранённый ранее. */
function dashboardKey(): string | null {
  const fromUrl = new URLSearchParams(window.location.search).get("key");
  try {
    if (fromUrl) sessionStorage.setItem(KEY_STORAGE, fromUrl);
    return fromUrl ?? sessionStorage.getItem(KEY_STORAGE);
  } catch {
    return fromUrl;
  }
}

async function getJson<T>(path: string): Promise<T> {
  const key = dashboardKey();
  const response = await fetch(path, { headers: key ? { "X-Dashboard-Key": key } : {} });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as { code?: string; title?: string };
    throw new ApiError(response.status, problem.code ?? "http_error", problem.title ?? response.statusText);
  }
  return (await response.json()) as T;
}

export const api = {
  patients: () => getJson<PatientOverview[]>("/api/v1/patients"),
  patientDay: (patientId: string, day: string) =>
    getJson<PatientDay>(`/api/v1/patients/${patientId}/days/${day}`),
  liveHeartRate: (patientId: string, windowSeconds = 300) =>
    getJson<LiveHeartRateWindow>(`/api/v1/patients/${patientId}/live-heart-rate?window_seconds=${windowSeconds}`),
};

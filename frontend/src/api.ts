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

export interface HeartRateBand {
  from_bpm: number | null;
  to_bpm: number | null;
  minutes: number;
}

export interface PatientDay {
  day: string;
  time_zone: string;
  summary: DailySummary;
  heart_rate_distribution: HeartRateBand[];
  minutes: DayMinute[];
}

export interface TrainingHeartRateZone {
  lower_bpm: number;
  upper_bpm: number;
}

export interface DailyTargets {
  training_zone: TrainingHeartRateZone;
  training_minutes: number;
  steps: number;
}

export interface RehabStage {
  first_day: number;
  last_day: number;
  targets: DailyTargets;
}

export interface RehabProgram {
  start_date: string;
  duration_days: number;
  stages: RehabStage[];
}

export interface MonitoringSettings {
  walking_steps_threshold: number;
  high_heart_rate_threshold: number;
  low_activity_steps: number;
  daily_alert_time_utc: string;
  training_minutes_upper_threshold: number | null;
}

export interface RehabPlanUpdate {
  program: RehabProgram;
  monitoring_settings: MonitoringSettings;
}

export interface RehabPlan extends RehabPlanUpdate {
  time_zone: string;
  is_default: boolean;
  effective_from: string;
}

export interface DayTargetProgress {
  day: string;
  program_day: number | null;
  progress: {
    targets: {
      training_zone: TrainingHeartRateZone;
      training_minutes: number;
      steps: number;
    };
    training_minutes: number;
    steps: number;
    walking_minutes: number;
    walking_minutes_without_heart_rate: number;
    has_data: boolean;
    is_final: boolean;
    is_met: boolean;
  } | null;
}

export interface LiveHeartRateReading {
  measured_at: string;
  heart_rate: number;
}

export interface LiveHeartRateWindow {
  window_seconds: number;
  readings: LiveHeartRateReading[];
}

export type LiveHeartRateRequestStatus =
  | "waiting_for_phone"
  | "running"
  | "completed"
  | "stopped_by_patient"
  | "failed";

export type LiveHeartRateFailureReason =
  | "expired"
  | "band_unavailable"
  | "notification_permission_denied"
  | "companion_association_missing"
  | "measurement_failed";

export interface ClinicianLiveHeartRateRequest {
  request_id: string;
  patient_id: string;
  duration_minutes: number;
  requested_at: string;
  expires_at: string;
  status: LiveHeartRateRequestStatus;
  started_at: string | null;
  finished_at: string | null;
  failure_reason: LiveHeartRateFailureReason | null;
}

export interface ClinicianLiveHeartRateRequestEnvelope {
  request: ClinicianLiveHeartRateRequest | null;
}

/** Прогресс реабилитации пациента для строки списка (ТР-05, ТР-06). */
export interface PatientProgress {
  patient_id: string;
  program_day: number | null;
  duration_days: number;
  is_finished: boolean;
  met_days: number;
  completed_days: number;
  today: {
    training_minutes: number;
    target_training_minutes: number;
    steps: number;
    target_steps: number;
    is_met: boolean;
  } | null;
}

export type ProgramDayStatus = "met" | "not_met" | "no_data" | "in_progress";

export interface PatientRehabProgress {
  start_date: string;
  duration_days: number;
  program_day: number | null;
  is_finished: boolean;
  completed_days: number;
  met_days: number;
  current_stage: {
    number: number;
    first_day: number;
    last_day: number;
  } | null;
  days: Array<{
    day: string;
    program_day: number;
    status: ProgramDayStatus;
  }>;
}

export type AlertKind = "high_heart_rate" | "daily_target_missed" | "daily_target_met" | "low_activity" | "training_minutes_exceeded";

export interface ClinicianAlert {
  alert_id: string;
  patient_id: string;
  kind: AlertKind;
  day: string | null;
  created_at: string;
  is_viewed: boolean;
  viewed_at: string | null;
  high_heart_rate: {
    started_at: string;
    last_at: string;
    duration_minutes: number;
    max_heart_rate: number;
    threshold_bpm: number;
  } | null;
  daily_target: {
    training_minutes: number;
    target_training_minutes: number;
    steps: number;
    target_steps: number;
  } | null;
  low_activity: {
    steps: number;
    threshold_steps: number;
  } | null;
  training_minutes_exceeded: {
    training_minutes: number;
    threshold_minutes: number;
  } | null;
}

export interface UnviewedAlertCount {
  patient_id: string;
  unviewed_alerts: number;
}

/** Карточка пациента, которую вводит врач (ТР-07). */
export interface PatientAdmission {
  last_name: string;
  first_name: string;
  middle_name: string | null;
  birth_date: string;
  height_cm: number;
  weight_kg: number;
  diagnosis: string;
  time_zone: string;
}

export interface EnrollmentCode {
  enrollment_code: string;
  enrollment_code_expires_at: string;
}

export interface AdmittedPatient extends EnrollmentCode {
  patient_id: string;
  name: string;
}

export interface PatientCard {
  patient_id: string;
  name: string;
  profile:
    | (Omit<PatientAdmission, "time_zone"> & { age_years: number })
    | null;
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

/** Вошедший врач (ТР-11): панель показывает только его пациентов. */
export interface Clinician {
  clinician_id: string;
  login: string;
  name: string;
}

/** Сеанс врача — cookie `kardio_session` (HttpOnly): браузер отправляет её сам, скрипт её не видит. */
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as { code?: string; title?: string; detail?: string };
    throw new ApiError(
      response.status,
      problem.code ?? "http_error",
      problem.detail ?? problem.title ?? response.statusText,
    );
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}

const getJson = <T,>(path: string) => request<T>("GET", path);

function alertsQuery(kind?: AlertKind): string {
  const query = new URLSearchParams({ limit: "500" });
  if (kind) query.set("kind", kind);
  return query.toString();
}

/** Сеанс истёк или его нет — панель показывает форму входа. */
export function isSignedOut(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

export const api = {
  me: () => getJson<Clinician>("/api/v1/auth/me"),
  signIn: (login: string, password: string) =>
    request<Clinician>("POST", "/api/v1/auth/sign-in", { login, password }),
  signOut: () => request<void>("POST", "/api/v1/auth/sign-out"),
  patients: (search = "") =>
    getJson<PatientOverview[]>(`/api/v1/patients${search.trim() ? `?search=${encodeURIComponent(search.trim())}` : ""}`),
  rehabProgress: () => getJson<PatientProgress[]>("/api/v1/rehab-progress"),
  patientRehabProgress: (patientId: string) =>
    getJson<PatientRehabProgress>(`/api/v1/patients/${patientId}/rehab-progress`),
  alerts: (kind?: AlertKind) => getJson<ClinicianAlert[]>(`/api/v1/alerts?${alertsQuery(kind)}`),
  patientAlerts: (patientId: string, kind?: AlertKind) =>
    getJson<ClinicianAlert[]>(`/api/v1/patients/${patientId}/alerts?${alertsQuery(kind)}`),
  unviewedAlertCounts: () => getJson<UnviewedAlertCount[]>("/api/v1/alerts/unviewed-counts"),
  markAlertViewed: (patientId: string, alertId: string) =>
    request<void>("POST", `/api/v1/patients/${patientId}/alerts/${alertId}/viewed`),
  rehabPlan: (patientId: string) => getJson<RehabPlan>(`/api/v1/patients/${patientId}/rehab-plan`),
  updateRehabPlan: (patientId: string, plan: RehabPlanUpdate) =>
    request<RehabPlan>("PUT", `/api/v1/patients/${patientId}/rehab-plan`, plan),
  createPatient: (card: PatientAdmission) => request<AdmittedPatient>("POST", "/api/v1/patients", card),
  patientCard: (patientId: string) => getJson<PatientCard>(`/api/v1/patients/${patientId}/profile`),
  newEnrollmentCode: (patientId: string) =>
    request<EnrollmentCode>("POST", `/api/v1/patients/${patientId}/enrollment-code`),
  patientDay: (patientId: string, day: string) =>
    getJson<PatientDay>(`/api/v1/patients/${patientId}/days/${day}`),
  dayTargetProgress: (patientId: string, day: string) =>
    getJson<DayTargetProgress>(`/api/v1/patients/${patientId}/days/${day}/target-progress`),
  liveHeartRate: (patientId: string, windowSeconds = 300) =>
    getJson<LiveHeartRateWindow>(`/api/v1/patients/${patientId}/live-heart-rate?window_seconds=${windowSeconds}`),
  latestLiveHeartRateRequest: (patientId: string) =>
    getJson<ClinicianLiveHeartRateRequestEnvelope>(
      `/api/v1/patients/${patientId}/live-heart-rate-requests/latest`,
    ),
  requestLiveHeartRate: (patientId: string, durationMinutes: number) =>
    request<ClinicianLiveHeartRateRequest>(
      "POST",
      `/api/v1/patients/${patientId}/live-heart-rate-requests`,
      { duration_minutes: durationMinutes },
    ),
};

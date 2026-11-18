import type {
  ClinicianLiveHeartRateRequest,
  LiveHeartRateFailureReason,
  LiveHeartRateRequestStatus,
} from "./api";

export const MIN_LIVE_HEART_RATE_DURATION = 1;
export const MAX_LIVE_HEART_RATE_DURATION = 60;
export const DEFAULT_LIVE_HEART_RATE_DURATION = 10;

export function parseLiveHeartRateDuration(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const duration = Number(value);
  return Number.isSafeInteger(duration) &&
    duration >= MIN_LIVE_HEART_RATE_DURATION &&
    duration <= MAX_LIVE_HEART_RATE_DURATION
    ? duration
    : null;
}

const STATUS_LABELS: Record<LiveHeartRateRequestStatus, string> = {
  waiting_for_phone: "Ожидает телефон",
  running: "Измерение идёт",
  completed: "Измерение завершено",
  stopped_by_patient: "Остановлено пациентом",
  failed: "Не выполнено",
};

const FAILURE_LABELS: Record<LiveHeartRateFailureReason, string> = {
  expired: "телефон не получил запрос вовремя",
  band_unavailable: "браслет недоступен",
  notification_permission_denied: "на телефоне запрещены уведомления",
  companion_association_missing: "браслет не подготовлен для фонового измерения",
  measurement_failed: "ошибка измерения",
};

export function liveHeartRateRequestStatusLabel(status: LiveHeartRateRequestStatus): string {
  return STATUS_LABELS[status];
}

export function liveHeartRateFailureLabel(reason: LiveHeartRateFailureReason): string {
  return FAILURE_LABELS[reason];
}

export function liveHeartRateRequestDescription(request: ClinicianLiveHeartRateRequest): string {
  if (request.status === "failed" && request.failure_reason !== null) {
    return `${STATUS_LABELS.failed}: ${FAILURE_LABELS[request.failure_reason]}`;
  }
  if (request.status === "running") {
    return `${STATUS_LABELS.running} · ${request.duration_minutes} мин`;
  }
  return STATUS_LABELS[request.status];
}

export function isActiveLiveHeartRateRequest(status: LiveHeartRateRequestStatus): boolean {
  return status === "waiting_for_phone" || status === "running";
}
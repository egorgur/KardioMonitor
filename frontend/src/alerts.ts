import type { AlertKind, ClinicianAlert } from "./api";
import { formatClock, formatDay } from "./time";

export const ALERT_KIND_LABELS: Record<AlertKind, string> = {
  high_heart_rate: "Высокий пульс",
  daily_target_missed: "Норма дня не выполнена",
  daily_target_met: "Норма дня выполнена",
  low_activity: "Почти не ходит",
  training_minutes_exceeded: "Превышен порог тренировочных минут",
};

export function alertCountLabel(count: number): string {
  const remainder100 = count % 100;
  const remainder10 = count % 10;
  if (remainder10 === 1 && remainder100 !== 11) return `${count} непросмотренное оповещение`;
  if (remainder10 >= 2 && remainder10 <= 4 && (remainder100 < 12 || remainder100 > 14)) {
    return `${count} непросмотренных оповещения`;
  }
  return `${count} непросмотренных оповещений`;
}

export function alertDescription(alert: ClinicianAlert): string {
  if (alert.kind === "high_heart_rate" && alert.high_heart_rate) {
    const value = alert.high_heart_rate;
    return `Пульс до ${value.max_heart_rate} уд/мин при пороге ${value.threshold_bpm}, эпизод ${value.duration_minutes} мин.`;
  }
  if ((alert.kind === "daily_target_missed" || alert.kind === "daily_target_met") && alert.daily_target) {
    const value = alert.daily_target;
    return `${value.training_minutes} из ${value.target_training_minutes} тренировочных минут, ${value.steps.toLocaleString("ru-RU")} из ${value.target_steps.toLocaleString("ru-RU")} шагов.`;
  }
  if (alert.kind === "low_activity" && alert.low_activity) {
    const value = alert.low_activity;
    return `${value.steps.toLocaleString("ru-RU")} шагов за день при пороге ${value.threshold_steps.toLocaleString("ru-RU")}.`;
  }
  if (alert.kind === "training_minutes_exceeded" && alert.training_minutes_exceeded) {
    const value = alert.training_minutes_exceeded;
    return `Тренировочные минуты за день: ${value.training_minutes} при верхнем пороге ${value.threshold_minutes} мин.`;
  }
  return "Значения оповещения недоступны.";
}

export function alertEventLabel(alert: ClinicianAlert, timeZone: string): string {
  if (alert.kind === "high_heart_rate" && alert.high_heart_rate) {
    const value = alert.high_heart_rate;
    const eventDay = new Intl.DateTimeFormat("en-CA", { timeZone }).format(new Date(value.started_at));
    return `${formatDay(eventDay)}, ${formatClock(value.started_at, timeZone)}–${formatClock(value.last_at, timeZone)}`;
  }
  return alert.day ? `за ${formatDay(alert.day)}` : "дата события не указана";
}

export function alertCreatedLabel(alert: ClinicianAlert, timeZone: string): string {
  const createdDay = new Intl.DateTimeFormat("en-CA", { timeZone }).format(new Date(alert.created_at));
  return `${formatDay(createdDay)}, ${formatClock(alert.created_at, timeZone)}`;
}

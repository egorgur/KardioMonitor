import type { DayTargetProgress, ProgramDayStatus } from "./api";

export type ProgressTone = "success" | "danger" | "neutral";

export interface DayVerdict {
  label: string;
  tone: ProgressTone;
}

export function boundedPercent(value: number, target: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(target) || target <= 0) return target === 0 && value >= 0 ? 100 : 0;
  return Math.max(0, Math.min(100, (value / target) * 100));
}

export function programPercent(programDay: number | null, durationDays: number, isFinished: boolean): number {
  if (isFinished) return 100;
  return boundedPercent(programDay ?? 0, durationDays);
}

export function dayVerdict(progress: DayTargetProgress["progress"]): DayVerdict {
  if (!progress) return { label: "Дата вне программы", tone: "neutral" };
  if (!progress.has_data) return { label: "Нет данных · норма не выполнена", tone: "danger" };
  if (progress.is_final) {
    return progress.is_met
      ? { label: "Норма выполнена", tone: "success" }
      : { label: "Норма не выполнена", tone: "danger" };
  }
  return progress.is_met
    ? { label: "Пока выполнена", tone: "success" }
    : { label: "Пока не выполнена", tone: "neutral" };
}

export const PROGRAM_DAY_LABELS: Record<ProgramDayStatus, string> = {
  met: "Норма выполнена",
  not_met: "Норма не выполнена",
  no_data: "Нет данных",
  in_progress: "День продолжается",
};

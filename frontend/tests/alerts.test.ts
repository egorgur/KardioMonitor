import { describe, expect, it } from "vitest";
import type { ClinicianAlert } from "../src/api";
import { ALERT_KIND_LABELS, alertCountLabel, alertCreatedLabel, alertDescription, alertEventLabel } from "../src/alerts";
import { formatDay } from "../src/time";

const baseAlert = {
  alert_id: "alert-1",
  patient_id: "patient-1",
  day: "2026-11-01",
  created_at: "2026-11-01T06:30:00Z",
  is_viewed: false,
  viewed_at: null,
  high_heart_rate: null,
  daily_target: null,
  low_activity: null,
  training_minutes_exceeded: null,
};

describe("alert descriptions", () => {
  it("describes a high-heart-rate episode without a diagnosis", () => {
    const alert: ClinicianAlert = {
      ...baseAlert,
      kind: "high_heart_rate",
      day: null,
      high_heart_rate: {
        started_at: "2026-11-01T06:05:00Z",
        last_at: "2026-11-01T06:11:00Z",
        duration_minutes: 6,
        max_heart_rate: 142,
        threshold_bpm: 130,
      },
    };

    expect(alertDescription(alert)).toBe("Пульс до 142 уд/мин при пороге 130, эпизод 6 мин.");
  });

  it.each([
    ["daily_target_missed", "12 из 30 тренировочных минут, 6 200 из 10 000 шагов."],
    ["daily_target_met", "30 из 30 тренировочных минут, 10 000 из 10 000 шагов."],
  ] as const)("describes %s", (kind, expected) => {
    const met = kind === "daily_target_met";
    const alert: ClinicianAlert = {
      ...baseAlert,
      kind,
      daily_target: {
        training_minutes: met ? 30 : 12,
        target_training_minutes: 30,
        steps: met ? 10_000 : 6_200,
        target_steps: 10_000,
      },
    };

    expect(alertDescription(alert)).toBe(expected);
  });

  it("describes low activity using the configured threshold", () => {
    const alert: ClinicianAlert = {
      ...baseAlert,
      kind: "low_activity",
      low_activity: { steps: 400, threshold_steps: 1_000 },
    };

    expect(alertDescription(alert)).toBe("400 шагов за день при пороге 1 000.");
  });

  it("describes the exceeded upper threshold separately from the daily target", () => {
    const alert: ClinicianAlert = {
      ...baseAlert,
      kind: "training_minutes_exceeded",
      training_minutes_exceeded: { training_minutes: 61, threshold_minutes: 60 },
    };

    expect(alertDescription(alert)).toBe("Тренировочные минуты за день: 61 при верхнем пороге 60 мин.");
    expect(alertEventLabel(alert, "Asia/Novosibirsk")).toBe(`за ${formatDay("2026-11-01")}`);
  });

  it("does not invent exceeded values when the details are missing", () => {
    expect(alertDescription({ ...baseAlert, kind: "training_minutes_exceeded" })).toBe("Значения оповещения недоступны.");
  });

  it("does not invent a no-data verdict from zero values", () => {
    const alert: ClinicianAlert = {
      ...baseAlert,
      kind: "daily_target_missed",
      daily_target: { training_minutes: 0, target_training_minutes: 30, steps: 0, target_steps: 10_000 },
    };

    expect(alertDescription(alert)).toContain("0 из 30");
    expect(alertDescription(alert)).not.toContain("Нет данных");
  });
});

describe("alert time labels", () => {
  const alert: ClinicianAlert = {
    ...baseAlert,
    kind: "high_heart_rate",
    day: null,
    high_heart_rate: {
      started_at: "2026-11-01T06:05:00Z",
      last_at: "2026-11-01T06:11:00Z",
      duration_minutes: 6,
      max_heart_rate: 142,
      threshold_bpm: 130,
    },
  };

  it("uses the patient time zone for the event and creation time", () => {
    expect(alertEventLabel(alert, "Asia/Yekaterinburg")).toContain("11:05–11:11");
    expect(alertCreatedLabel(alert, "Asia/Yekaterinburg")).toContain("11:30");
  });

  it("keeps labels for every server alert kind", () => {
    expect(Object.keys(ALERT_KIND_LABELS)).toEqual([
      "high_heart_rate",
      "daily_target_missed",
      "daily_target_met",
      "low_activity",
      "training_minutes_exceeded",
    ]);
  });
});

describe("alertCountLabel", () => {
  it.each([
    [1, "1 непросмотренное оповещение"],
    [2, "2 непросмотренных оповещения"],
    [5, "5 непросмотренных оповещений"],
    [11, "11 непросмотренных оповещений"],
    [22, "22 непросмотренных оповещения"],
  ])("formats %i", (count, expected) => {
    expect(alertCountLabel(count)).toBe(expected);
  });
});

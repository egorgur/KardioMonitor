import { describe, expect, it } from "vitest";
import type { DayTargetProgress } from "../src/api";
import { PROGRAM_DAY_LABELS, boundedPercent, dayVerdict, programPercent } from "../src/progress";

const progress = (changes: Partial<NonNullable<DayTargetProgress["progress"]>> = {}) => ({
  targets: {
    training_zone: { lower_bpm: 90, upper_bpm: 110 },
    training_minutes: 30,
    steps: 10_000,
  },
  training_minutes: 15,
  steps: 6_200,
  walking_minutes: 20,
  walking_minutes_without_heart_rate: 2,
  has_data: true,
  is_final: false,
  is_met: false,
  ...changes,
});

describe("boundedPercent", () => {
  it.each([
    [15, 30, 50],
    [40, 30, 100],
    [-1, 30, 0],
    [0, 0, 100],
  ])("maps %s of %s to %s percent", (value, target, expected) => {
    expect(boundedPercent(value, target)).toBe(expected);
  });
});

describe("dayVerdict", () => {
  it("distinguishes a date outside the program", () => {
    expect(dayVerdict(null)).toEqual({ label: "Дата вне программы", tone: "neutral" });
  });

  it("shows no data separately from an ordinary missed target", () => {
    expect(dayVerdict(progress({ has_data: false, is_final: true }))).toEqual({
      label: "Нет данных · норма не выполнена",
      tone: "danger",
    });
  });

  it.each([
    [false, false, "Пока не выполнена", "neutral"],
    [false, true, "Пока выполнена", "success"],
    [true, false, "Норма не выполнена", "danger"],
    [true, true, "Норма выполнена", "success"],
  ] as const)("formats final=%s met=%s", (isFinal, isMet, label, tone) => {
    expect(dayVerdict(progress({ is_final: isFinal, is_met: isMet }))).toEqual({ label, tone });
  });
});

describe("programPercent", () => {
  it("counts the current day as elapsed", () => {
    expect(programPercent(12, 42, false)).toBeCloseTo(28.57, 1);
  });

  it("returns zero before the program and 100 after it", () => {
    expect(programPercent(null, 42, false)).toBe(0);
    expect(programPercent(42, 42, true)).toBe(100);
  });
});

describe("program day labels", () => {
  it("keeps no-data and an unfinished day distinct", () => {
    expect(PROGRAM_DAY_LABELS.no_data).toBe("Нет данных");
    expect(PROGRAM_DAY_LABELS.in_progress).toBe("День продолжается");
  });
});

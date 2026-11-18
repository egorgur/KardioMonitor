import { describe, expect, it } from "vitest";
import type { RehabPlan, RehabPlanUpdate } from "../src/api";
import {
  addRehabStage,
  alertTimeInZone,
  rehabPlanPayload,
  removeRehabStage,
  toRehabPlanUpdate,
  validateRehabPlan,
} from "../src/rehabPlan";

const plan = (): RehabPlan => ({
  time_zone: "Europe/Moscow",
  is_default: true,
  effective_from: "2026-11-01",
  program: {
    start_date: "2026-10-01",
    duration_days: 42,
    stages: [
      {
        first_day: 1,
        last_day: 42,
        targets: {
          training_zone: { lower_bpm: 90, upper_bpm: 110 },
          training_minutes: 30,
          steps: 10_000,
        },
      },
    ],
  },
  monitoring_settings: {
    walking_steps_threshold: 60,
    high_heart_rate_threshold: 130,
    low_activity_steps: 1_000,
    daily_alert_time_utc: "17:00:00",
    training_minutes_upper_threshold: null,
  },
});

describe("rehab plan payload", () => {
  it("keeps only writable fields and normalizes browser time", () => {
    const source = plan();
    const draft = toRehabPlanUpdate(source);
    draft.monitoring_settings.daily_alert_time_utc = "16:30";

    const payload = rehabPlanPayload(draft);

    expect(payload).not.toHaveProperty("time_zone");
    expect(payload).not.toHaveProperty("is_default");
    expect(payload).not.toHaveProperty("effective_from");
    expect(payload.monitoring_settings.daily_alert_time_utc).toBe("16:30:00");
    expect(source.monitoring_settings.daily_alert_time_utc).toBe("17:00:00");
  });
});

describe("training minutes upper threshold", () => {
  it.each([null, 1, 60, 1440])("preserves the independently configured threshold %s", (threshold) => {
    const source = plan();
    source.monitoring_settings.training_minutes_upper_threshold = threshold;
    const payload = rehabPlanPayload(toRehabPlanUpdate(source));

    expect(payload.monitoring_settings.training_minutes_upper_threshold).toBe(threshold);
    expect(validateRehabPlan(payload)).toEqual([]);
    expect(payload.program.stages[0]?.targets.training_minutes).toBe(30);
  });

  it("disables the check with explicit null without changing the source", () => {
    const source = plan();
    source.monitoring_settings.training_minutes_upper_threshold = 60;
    const draft = toRehabPlanUpdate(source);
    draft.monitoring_settings.training_minutes_upper_threshold = null;

    expect(rehabPlanPayload(draft).monitoring_settings.training_minutes_upper_threshold).toBeNull();
    expect(source.monitoring_settings.training_minutes_upper_threshold).toBe(60);
  });

  it.each([0, -1, 1441, 60.5, Number.NaN, Number.POSITIVE_INFINITY])("rejects invalid threshold %s", (threshold) => {
    const draft = toRehabPlanUpdate(plan());
    draft.monitoring_settings.training_minutes_upper_threshold = threshold;

    expect(validateRehabPlan(draft)).toContain("Верхний порог тренировочных минут — целое число от 1 до 1440 или пустое поле для отключения.");
  });
});

describe("stage editing", () => {
  it("splits the longest stage without creating a gap", () => {
    const result = addRehabStage(toRehabPlanUpdate(plan()));

    expect(result.program.stages.map(({ first_day, last_day }) => [first_day, last_day])).toEqual([
      [1, 21],
      [22, 42],
    ]);
    expect(validateRehabPlan(result)).toEqual([]);
  });

  it("merges removed stage days into its neighbour", () => {
    const withTwoStages = addRehabStage(toRehabPlanUpdate(plan()));
    const result = removeRehabStage(withTwoStages, 0);

    expect(result.program.stages).toHaveLength(1);
    expect(result.program.stages[0]).toMatchObject({ first_day: 1, last_day: 42 });
    expect(validateRehabPlan(result)).toEqual([]);
  });
});

describe("rehab plan validation", () => {
  it("accepts the server default plan", () => {
    expect(validateRehabPlan(toRehabPlanUpdate(plan()))).toEqual([]);
  });

  it("reports uncovered days exactly", () => {
    const draft = toRehabPlanUpdate(plan());
    draft.program.duration_days = 25;
    const first = draft.program.stages[0];
    if (!first) throw new Error("fixture stage is missing");
    first.last_day = 15;
    draft.program.stages.push({
      ...first,
      first_day: 18,
      last_day: 25,
      targets: { ...first.targets, training_zone: { ...first.targets.training_zone } },
    });

    expect(validateRehabPlan(draft)).toContain("Дни 16–17 не входят ни в один этап.");
  });

  it("rejects a reversed training zone and invalid monitoring thresholds", () => {
    const draft: RehabPlanUpdate = toRehabPlanUpdate(plan());
    const stage = draft.program.stages[0];
    if (!stage) throw new Error("fixture stage is missing");
    stage.targets.training_zone = { lower_bpm: 120, upper_bpm: 100 };
    draft.monitoring_settings.walking_steps_threshold = 0;

    expect(validateRehabPlan(draft)).toEqual(expect.arrayContaining([
      "Этап 1: нижняя граница пульса должна быть меньше верхней.",
      "Порог ходьбы — от 1 до 250 шагов в минуту.",
    ]));
  });
});

describe("alertTimeInZone", () => {
  it("shows UTC time in the patient's zone for the current local day", () => {
    expect(alertTimeInZone("17:00:00", "2026-11-01", "Europe/Moscow")).toBe("20:00");
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import { api, type RehabPlanUpdate } from "../src/api";

const plan = (threshold: number | null): RehabPlanUpdate => ({
  program: {
    start_date: "2026-11-12",
    duration_days: 1,
    stages: [{
      first_day: 1,
      last_day: 1,
      targets: { training_zone: { lower_bpm: 90, upper_bpm: 110 }, training_minutes: 30, steps: 10000 },
    }],
  },
  monitoring_settings: {
    walking_steps_threshold: 60,
    high_heart_rate_threshold: 130,
    low_activity_steps: 1000,
    daily_alert_time_utc: "17:00:00",
    training_minutes_upper_threshold: threshold,
  },
});

afterEach(() => vi.unstubAllGlobals());

describe("training minutes threshold API", () => {
  it.each([60, null])("sends explicit threshold %s through the existing plan endpoint", async (threshold) => {
    const payload = plan(threshold);
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(payload)));
    vi.stubGlobal("fetch", fetch);

    await api.updateRehabPlan("patient-demo", payload);

    expect(fetch).toHaveBeenCalledWith("/api/v1/patients/patient-demo/rehab-plan", expect.objectContaining({
      method: "PUT",
      credentials: "same-origin",
      body: JSON.stringify(payload),
    }));
  });

  it("filters both clinician journals by the new kind", async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response("[]")));
    vi.stubGlobal("fetch", fetch);

    await api.alerts("training_minutes_exceeded");
    await api.patientAlerts("patient-demo", "training_minutes_exceeded");

    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      "/api/v1/alerts?limit=500&kind=training_minutes_exceeded",
      "/api/v1/patients/patient-demo/alerts?limit=500&kind=training_minutes_exceeded",
    ]);
  });
});

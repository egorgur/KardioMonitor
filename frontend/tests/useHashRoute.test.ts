import { describe, expect, it } from "vitest";
import { parseHashRoute } from "../src/useHashRoute";

describe("parseHashRoute", () => {
  it("recognizes the global alert journal", () => {
    expect(parseHashRoute("#/alerts")).toEqual({ view: "alerts", patientId: null, day: null });
  });

  it("preserves patient and selected-day routes", () => {
    expect(parseHashRoute("#/patients/patient-1")).toEqual({
      view: "patients",
      patientId: "patient-1",
      day: null,
    });
    expect(parseHashRoute("#/patients/patient-1/2026-11-01")).toEqual({
      view: "patients",
      patientId: "patient-1",
      day: "2026-11-01",
    });
  });

  it("rejects trailing or malformed route fragments", () => {
    expect(parseHashRoute("#/alerts/extra")).toEqual({ view: "patients", patientId: null, day: null });
    expect(parseHashRoute("#/patients/patient-1/not-a-day")).toEqual({ view: "patients", patientId: null, day: null });
  });
});

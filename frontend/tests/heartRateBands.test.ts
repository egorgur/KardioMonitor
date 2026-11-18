import { describe, expect, it } from "vitest";
import type { HeartRateBand } from "../src/api";
import {
  bandIntersectsZone,
  heartRateBandIndex,
  heartRateBandLabel,
} from "../src/heartRateBands";

describe("heartRateBandIndex", () => {
  it.each([
    [39, 0],
    [40, 1],
    [49, 1],
    [50, 2],
    [119, 8],
    [120, 9],
    [180, 9],
  ])("maps %i bpm to band %i", (bpm, expected) => {
    expect(heartRateBandIndex(bpm)).toBe(expected);
  });
});

describe("heartRateBandLabel", () => {
  it.each([
    [{ from_bpm: null, to_bpm: 39, minutes: 1 }, "< 40"],
    [{ from_bpm: 70, to_bpm: 79, minutes: 1 }, "70–79"],
    [{ from_bpm: 120, to_bpm: null, minutes: 1 }, "120+"],
  ] satisfies [HeartRateBand, string][])('formats $expected', (band, expected) => {
    expect(heartRateBandLabel(band)).toBe(expected);
  });
});

describe("bandIntersectsZone", () => {
  const zone = { lower_bpm: 90, upper_bpm: 110 };

  it("marks every interval that overlaps the patient's training zone", () => {
    expect(bandIntersectsZone({ from_bpm: 80, to_bpm: 89, minutes: 2 }, zone)).toBe(false);
    expect(bandIntersectsZone({ from_bpm: 90, to_bpm: 99, minutes: 2 }, zone)).toBe(true);
    expect(bandIntersectsZone({ from_bpm: 110, to_bpm: 119, minutes: 2 }, zone)).toBe(true);
    expect(bandIntersectsZone({ from_bpm: 120, to_bpm: null, minutes: 2 }, zone)).toBe(false);
  });

  it("does not mark intervals when the day has no rehabilitation target", () => {
    expect(bandIntersectsZone({ from_bpm: 90, to_bpm: 99, minutes: 2 }, null)).toBe(false);
  });
});

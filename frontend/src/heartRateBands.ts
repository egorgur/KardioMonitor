import type { HeartRateBand, TrainingHeartRateZone } from "./api";

export const HEART_RATE_BAND_COLORS = [
  "#64748b",
  "#4f7f9c",
  "#3b8f91",
  "#3f9b72",
  "#84a83f",
  "#c59b32",
  "#d47a32",
  "#d75c45",
  "#cf455d",
  "#b93e76",
] as const;

export function heartRateBandIndex(bpm: number): number {
  if (bpm < 40) return 0;
  if (bpm >= 120) return 9;
  return Math.floor((bpm - 40) / 10) + 1;
}

export function heartRateBandLabel(band: HeartRateBand): string {
  if (band.from_bpm === null) return "< 40";
  if (band.to_bpm === null) return "120+";
  return `${band.from_bpm}–${band.to_bpm}`;
}

export function bandIntersectsZone(band: HeartRateBand, zone: TrainingHeartRateZone | null): boolean {
  if (!zone) return false;
  const lower = band.from_bpm ?? Number.NEGATIVE_INFINITY;
  const upper = band.to_bpm ?? Number.POSITIVE_INFINITY;
  return lower <= zone.upper_bpm && upper >= zone.lower_bpm;
}

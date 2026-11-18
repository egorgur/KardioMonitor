import { describe, expect, it } from "vitest";
import type { ClinicianLiveHeartRateRequest } from "../src/api";
import {
  isActiveLiveHeartRateRequest,
  liveHeartRateFailureLabel,
  liveHeartRateRequestDescription,
  liveHeartRateRequestStatusLabel,
  parseLiveHeartRateDuration,
} from "../src/liveHeartRateRequest";

const request: ClinicianLiveHeartRateRequest = {
  request_id: "4ff2c7c7-4873-47aa-b11c-20f662530b1d",
  patient_id: "7d24cc90-10c9-4ca8-9841-e8e219021075",
  duration_minutes: 10,
  requested_at: "2026-05-01T08:00:00Z",
  expires_at: "2026-05-01T10:00:00Z",
  status: "waiting_for_phone",
  started_at: null,
  finished_at: null,
  failure_reason: null,
};

describe("live heart rate request duration", () => {
  it("accepts only whole minutes from 1 through 60", () => {
    expect(parseLiveHeartRateDuration("1")).toBe(1);
    expect(parseLiveHeartRateDuration("10")).toBe(10);
    expect(parseLiveHeartRateDuration("60")).toBe(60);
    expect(parseLiveHeartRateDuration("0")).toBeNull();
    expect(parseLiveHeartRateDuration("61")).toBeNull();
    expect(parseLiveHeartRateDuration("1.5")).toBeNull();
    expect(parseLiveHeartRateDuration("")).toBeNull();
    expect(parseLiveHeartRateDuration(" 10 ")).toBeNull();
  });
});

describe("live heart rate request state", () => {
  it("keeps every server status visible to the clinician", () => {
    expect(liveHeartRateRequestStatusLabel("waiting_for_phone")).toBe("Ожидает телефон");
    expect(liveHeartRateRequestStatusLabel("running")).toBe("Измерение идёт");
    expect(liveHeartRateRequestStatusLabel("completed")).toBe("Измерение завершено");
    expect(liveHeartRateRequestStatusLabel("stopped_by_patient")).toBe("Остановлено пациентом");
    expect(liveHeartRateRequestStatusLabel("failed")).toBe("Не выполнено");
  });

  it("distinguishes active requests from terminal ones", () => {
    expect(isActiveLiveHeartRateRequest("waiting_for_phone")).toBe(true);
    expect(isActiveLiveHeartRateRequest("running")).toBe(true);
    expect(isActiveLiveHeartRateRequest("completed")).toBe(false);
    expect(isActiveLiveHeartRateRequest("stopped_by_patient")).toBe(false);
    expect(isActiveLiveHeartRateRequest("failed")).toBe(false);
  });

  it("explains every failure reason without exposing an implementation code", () => {
    expect(liveHeartRateFailureLabel("expired")).toBe("телефон не получил запрос вовремя");
    expect(liveHeartRateFailureLabel("band_unavailable")).toBe("браслет недоступен");
    expect(liveHeartRateFailureLabel("notification_permission_denied")).toBe(
      "на телефоне запрещены уведомления",
    );
    expect(liveHeartRateFailureLabel("companion_association_missing")).toBe(
      "браслет не подготовлен для фонового измерения",
    );
    expect(liveHeartRateFailureLabel("measurement_failed")).toBe("ошибка измерения");
  });

  it("adds duration for a running request and a reason for a failed request", () => {
    expect(liveHeartRateRequestDescription({ ...request, status: "running" })).toBe(
      "Измерение идёт · 10 мин",
    );
    expect(
      liveHeartRateRequestDescription({
        ...request,
        status: "failed",
        failure_reason: "band_unavailable",
      }),
    ).toBe("Не выполнено: браслет недоступен");
  });
});

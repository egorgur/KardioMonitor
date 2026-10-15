// Время показывается в часовом поясе пациента: его день, его часы.

export function todayIn(timeZone: string): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone }).format(new Date());
}

export function shiftDay(day: string, days: number): string {
  const date = new Date(`${day}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

export function formatDay(day: string): string {
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "short",
    day: "numeric",
    month: "long",
    timeZone: "UTC",
  }).format(new Date(`${day}T12:00:00Z`));
}

export function formatClock(iso: string, timeZone: string): string {
  return new Intl.DateTimeFormat("ru-RU", { hour: "2-digit", minute: "2-digit", timeZone }).format(new Date(iso));
}

export function hourIn(iso: string, timeZone: string): number {
  const hour = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", hourCycle: "h23", timeZone }).format(new Date(iso));
  return Number(hour);
}

/** «12 с назад», «5 мин назад», «вчера в 18:40». */
export function formatAgo(iso: string, timeZone: string, now = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return `${seconds} с назад`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} мин назад`;
  const time = formatClock(iso, timeZone);
  const day = new Intl.DateTimeFormat("en-CA", { timeZone }).format(new Date(iso));
  if (day === todayIn(timeZone)) return `сегодня в ${time}`;
  if (day === shiftDay(todayIn(timeZone), -1)) return `вчера в ${time}`;
  return `${formatDay(day)}, ${time}`;
}

/** Значение «пульса сейчас» свежее, пока телефон присылает его чаще, чем раз в 20 секунд. */
export const LIVE_FRESH_SECONDS = 20;

export function isFresh(iso: string | null, now = Date.now()): boolean {
  return iso !== null && now - new Date(iso).getTime() < LIVE_FRESH_SECONDS * 1000;
}

/** Ось пульса — по десяткам, с запасом: деления 60, 70, 80, а не 68, 76, 89. */
export const HEART_RATE_DOMAIN: [(min: number) => number, (max: number) => number] = [
  (min) => Math.floor((min - 5) / 10) * 10,
  (max) => Math.ceil((max + 5) / 10) * 10,
];

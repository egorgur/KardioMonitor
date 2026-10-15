import { useEffect, useRef, useState } from "react";

export interface Polled<T> {
  data: T | null;
  error: Error | null;
}

/**
 * Опрос API раз в `intervalMs`. Новый ключ (`deps`) сбрасывает данные. Ответ на устаревший
 * запрос (пользователь уже выбрал другой день) отбрасывается.
 */
export function usePolling<T>(load: () => Promise<T>, intervalMs: number, deps: unknown[]): Polled<T> {
  const [state, setState] = useState<Polled<T>>({ data: null, error: null });
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    let isCurrent = true;
    setState({ data: null, error: null });
    const tick = () => {
      loadRef.current().then(
        (data) => isCurrent && setState({ data, error: null }),
        (error: Error) => isCurrent && setState((previous) => ({ data: previous.data, error })),
      );
    };
    tick();
    const timer = window.setInterval(tick, intervalMs);
    return () => {
      isCurrent = false;
      window.clearInterval(timer);
    };
  }, [intervalMs, ...deps]); // deps задаёт вызывающий: смена пациента или дня

  return state;
}

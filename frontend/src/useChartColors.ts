import { useEffect, useState } from "react";

export interface ChartColors {
  heart: string;
  steps: string;
  grid: string;
  axis: string;
}

// SVG-атрибуты Recharts не понимают var(--…): берём вычисленные значения токенов темы.
function readColors(): ChartColors {
  const style = getComputedStyle(document.documentElement);
  const token = (name: string) => style.getPropertyValue(name).trim();
  return {
    heart: token("--heart"),
    steps: token("--steps"),
    grid: token("--grid"),
    axis: token("--text-muted"),
  };
}

/** Цвета графиков из темы; меняются вместе со светлой/тёмной темой системы. */
export function useChartColors(): ChartColors {
  const [colors, setColors] = useState(readColors);
  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const update = () => setColors(readColors());
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  return colors;
}

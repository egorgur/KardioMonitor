import type { RehabPlan, RehabPlanUpdate, RehabStage } from "./api";

export function toRehabPlanUpdate(plan: RehabPlan): RehabPlanUpdate {
  return cloneRehabPlanUpdate({
    program: plan.program,
    monitoring_settings: plan.monitoring_settings,
  });
}

export function rehabPlanPayload(plan: RehabPlanUpdate): RehabPlanUpdate {
  const payload = cloneRehabPlanUpdate(plan);
  const time = payload.monitoring_settings.daily_alert_time_utc;
  if (/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) {
    payload.monitoring_settings.daily_alert_time_utc = `${time}:00`;
  }
  return payload;
}

export function cloneRehabPlanUpdate(plan: RehabPlanUpdate): RehabPlanUpdate {
  return {
    program: {
      ...plan.program,
      stages: plan.program.stages.map((stage) => ({
        ...stage,
        targets: {
          ...stage.targets,
          training_zone: { ...stage.targets.training_zone },
        },
      })),
    },
    monitoring_settings: { ...plan.monitoring_settings },
  };
}

/** Делит пополам самый длинный этап, сохраняя непрерывное покрытие программы. */
export function addRehabStage(plan: RehabPlanUpdate): RehabPlanUpdate {
  const draft = cloneRehabPlanUpdate(plan);
  let splitIndex = -1;
  let longest = 1;
  draft.program.stages.forEach((stage, index) => {
    const length = stage.last_day - stage.first_day + 1;
    if (length > longest) {
      splitIndex = index;
      longest = length;
    }
  });
  if (splitIndex < 0) return draft;

  const stage = draft.program.stages[splitIndex];
  if (!stage) return draft;
  const previousLastDay = stage.last_day;
  stage.last_day = Math.floor((stage.first_day + previousLastDay) / 2);
  const next: RehabStage = {
    ...stage,
    first_day: stage.last_day + 1,
    last_day: previousLastDay,
    targets: {
      ...stage.targets,
      training_zone: { ...stage.targets.training_zone },
    },
  };
  draft.program.stages.splice(splitIndex + 1, 0, next);
  return draft;
}

/** Удаляет этап и присоединяет его дни к соседнему, чтобы не создавать разрыв. */
export function removeRehabStage(plan: RehabPlanUpdate, index: number): RehabPlanUpdate {
  const draft = cloneRehabPlanUpdate(plan);
  if (draft.program.stages.length <= 1) return draft;
  const removed = draft.program.stages[index];
  if (!removed) return draft;

  if (index > 0) {
    const previous = draft.program.stages[index - 1];
    if (previous) previous.last_day = removed.last_day;
  } else {
    const next = draft.program.stages[1];
    if (next) next.first_day = removed.first_day;
  }
  draft.program.stages.splice(index, 1);
  return draft;
}

const isIntegerIn = (value: number, min: number, max: number) =>
  Number.isInteger(value) && value >= min && value <= max;

export function validateRehabPlan(plan: RehabPlanUpdate): string[] {
  const errors: string[] = [];
  const { program, monitoring_settings: settings } = plan;
  const validDuration = isIntegerIn(program.duration_days, 1, 365);
  if (!validDuration) errors.push("Длительность программы — от 1 до 365 дней.");
  if (!program.start_date) errors.push("Укажите дату начала программы.");
  if (program.stages.length === 0) errors.push("Добавьте хотя бы один этап программы.");

  let rangesAreValid = program.stages.length > 0;
  for (const [index, stage] of program.stages.entries()) {
    const name = `Этап ${index + 1}`;
    if (!Number.isInteger(stage.first_day) || !Number.isInteger(stage.last_day) || stage.first_day < 1 || stage.first_day > stage.last_day) {
      errors.push(`${name}: первый день должен быть не больше последнего.`);
      rangesAreValid = false;
    }
    const zone = stage.targets.training_zone;
    if (!Number.isInteger(zone.lower_bpm) || !Number.isInteger(zone.upper_bpm) || zone.lower_bpm >= zone.upper_bpm) {
      errors.push(`${name}: нижняя граница пульса должна быть меньше верхней.`);
    } else if (zone.lower_bpm < 40 || zone.upper_bpm > 220) {
      errors.push(`${name}: зона пульса — в пределах 40–220 уд/мин.`);
    }
    if (!isIntegerIn(stage.targets.training_minutes, 1, 1440)) {
      errors.push(`${name}: тренировочных минут — от 1 до 1440.`);
    }
    if (!isIntegerIn(stage.targets.steps, 0, 100_000)) {
      errors.push(`${name}: шагов в день — от 0 до 100 000.`);
    }
  }

  if (validDuration && rangesAreValid) {
    const stages = [...program.stages].sort((left, right) => left.first_day - right.first_day);
    let nextDay = 1;
    for (const stage of stages) {
      if (stage.first_day > nextDay) {
        errors.push(`Дни ${nextDay}–${stage.first_day - 1} не входят ни в один этап.`);
      } else if (stage.first_day < nextDay) {
        errors.push(`Дни ${stage.first_day}–${Math.min(stage.last_day, nextDay - 1)} входят в несколько этапов.`);
      }
      nextDay = Math.max(nextDay, stage.last_day + 1);
    }
    if (nextDay <= program.duration_days) {
      errors.push(`Дни ${nextDay}–${program.duration_days} не входят ни в один этап.`);
    } else if (nextDay - 1 > program.duration_days) {
      errors.push(`Этапы заходят за конец программы — день ${program.duration_days}.`);
    }
  }

  if (!isIntegerIn(settings.walking_steps_threshold, 1, 250)) {
    errors.push("Порог ходьбы — от 1 до 250 шагов в минуту.");
  }
  if (!isIntegerIn(settings.high_heart_rate_threshold, 60, 250)) {
    errors.push("Порог высокого пульса — от 60 до 250 уд/мин.");
  }
  if (!isIntegerIn(settings.low_activity_steps, 0, 100_000)) {
    errors.push("Порог «почти не ходит» — от 0 до 100 000 шагов.");
  }
  if (settings.training_minutes_upper_threshold !== null && !isIntegerIn(settings.training_minutes_upper_threshold, 1, 1440)) {
    errors.push("Верхний порог тренировочных минут — целое число от 1 до 1440 или пустое поле для отключения.");
  }
  if (!/^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(settings.daily_alert_time_utc)) {
    errors.push("Укажите время дневных оповещений в UTC.");
  }
  return errors;
}

export function alertTimeInZone(utcTime: string, day: string, timeZone: string): string | null {
  if (!/^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(utcTime)) return null;
  const isoTime = utcTime.length === 5 ? `${utcTime}:00` : utcTime;
  const date = new Date(`${day}T${isoTime}Z`);
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat("ru-RU", { hour: "2-digit", minute: "2-digit", timeZone }).format(date);
}

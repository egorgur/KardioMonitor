import { type FormEvent, useState } from "react";
import { type AdmittedPatient, ApiError, type EnrollmentCode, type PatientAdmission, api } from "./api";

const BROWSER_TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;

/** Новый пациент (ТР-07): карточка вводится врачом, врач становится лечащим. */
export function AdmitPatient({ onAdmitted, onCancel }: { onAdmitted: (patient: AdmittedPatient) => void; onCancel: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const [isSending, setSending] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim();
    const card: PatientAdmission = {
      last_name: text("last_name"),
      first_name: text("first_name"),
      middle_name: text("middle_name") || null,
      birth_date: text("birth_date"),
      height_cm: Number(text("height_cm")),
      weight_kg: Number(text("weight_kg").replace(",", ".")),
      diagnosis: text("diagnosis"),
      time_zone: text("time_zone"),
    };
    setSending(true);
    setError(null);
    try {
      onAdmitted(await api.createPatient(card));
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "Сервер недоступен");
    } finally {
      setSending(false);
    }
  }

  return (
    <form className="detail form" onSubmit={submit}>
      <h1>Новый пациент</h1>
      <div className="form-grid">
        <Field name="last_name" label="Фамилия" required />
        <Field name="first_name" label="Имя" required />
        <Field name="middle_name" label="Отчество" />
        <Field name="birth_date" label="Дата рождения" type="date" required />
        <Field name="height_cm" label="Рост, см" type="number" required />
        <Field name="weight_kg" label="Вес, кг" inputMode="decimal" required />
        <Field name="time_zone" label="Часовой пояс" defaultValue={BROWSER_TIME_ZONE} required />
      </div>
      <label className="form-field">
        Диагноз
        <textarea name="diagnosis" rows={3} required />
      </label>
      <p className="muted">Программа — по умолчанию (42 дня, 90–110 уд/мин, 30 мин, 10 000 шагов); её можно изменить после создания.</p>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="form-actions">
        <button type="submit" className="primary-button" disabled={isSending}>
          {isSending ? "Сохранение…" : "Создать пациента"}
        </button>
        <button type="button" className="link-button" onClick={onCancel}>
          Отмена
        </button>
      </div>
    </form>
  );
}

function Field({ label, ...input }: { label: string } & React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="form-field">
      {label}
      <input {...input} />
    </label>
  );
}

/** Код привязки показывается один раз: его кладёт в QR утилита персонала (`e=`). */
export function EnrollmentCodeNotice({ name, code }: { name: string; code: EnrollmentCode }) {
  const expires = new Date(code.enrollment_code_expires_at).toLocaleString("ru-RU");
  return (
    <section className="notice">
      <h2>Пациент {name} создан</h2>
      <p>
        Код привязки телефона: <code>{code.enrollment_code}</code> — действует до {expires}. Сохраните его в утилите
        персонала для QR браслета: после закрытия окна код не показывается, но можно выдать новый в карточке пациента.
      </p>
    </section>
  );
}

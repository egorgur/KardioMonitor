import { useEffect, useState } from "react";
import { type EnrollmentCode, type PatientCard, api } from "./api";
import { EnrollmentCodeNotice } from "./AdmitPatient";

/** Карточка пациента (ТР-07): видит только лечащий врач; открытие записывается в журнал доступа. */
export function PatientCardInfo({ patientId }: { patientId: string }) {
  const [card, setCard] = useState<PatientCard | null>(null);
  const [code, setCode] = useState<EnrollmentCode | null>(null);

  useEffect(() => {
    api.patientCard(patientId).then(setCard, () => setCard(null));
  }, [patientId]);

  const profile = card?.profile;
  if (!card || !profile) return null;
  return (
    <div className="card-info">
      <span>
        {profile.age_years} лет · {profile.height_cm} см · {profile.weight_kg} кг
      </span>
      <span className="muted">{profile.diagnosis}</span>
      <button type="button" className="link-button" onClick={() => api.newEnrollmentCode(patientId).then(setCode)}>
        Новый код привязки телефона
      </button>
      {code && <EnrollmentCodeNotice name={card.name} code={code} />}
    </div>
  );
}

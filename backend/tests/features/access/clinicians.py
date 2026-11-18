"""Вымышленные врачи для тестов. Пароль — «hashed:» + пароль (`FakePasswordHasher`)."""

from kardiomonitor.features.access.domain.clinician import Clinician, ClinicianName, Login

PASSWORD = "correct horse battery"  # noqa: S105 — вымышленный пароль теста
DOCTOR_A = Clinician.register(Login("doctor.a"), ClinicianName("Врач А"), "hashed:" + PASSWORD)
DOCTOR_B = Clinician.register(Login("doctor.b"), ClinicianName("Врач Б"), "hashed:" + PASSWORD)

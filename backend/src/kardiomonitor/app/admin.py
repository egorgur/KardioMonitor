"""Команда администратора сервера: врачи и лечащие врачи пациентов (ТР-11, В-23, ADR-0011).

    uv run python -m kardiomonitor.app.admin create-clinician <логин> "<имя врача>"
    uv run python -m kardiomonitor.app.admin reset-password <логин>
    uv run python -m kardiomonitor.app.admin assign <patient_id> <логин>
    uv run python -m kardiomonitor.app.admin patients

Пароль вводится в скрытом приглашении — не в аргументах, чтобы не попасть в историю оболочки.
Список пациентов показывает имена: только в терминал администратора, не в журналы.
"""

import argparse
import asyncio
import sys
from collections.abc import Callable, Sequence
from getpass import getpass
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from kardiomonitor.app.db_metadata import metadata
from kardiomonitor.app.settings import Settings
from kardiomonitor.features.access.data.argon2_password_hasher import Argon2PasswordHasher
from kardiomonitor.features.access.data.sql_clinicians import SqlClinicians
from kardiomonitor.features.access.domain.clinician import Clinician, ClinicianName, Login
from kardiomonitor.features.access.domain.password import Password, PasswordHasher
from kardiomonitor.features.patients.data.sql_patient_registry import SqlPatientRegistry
from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.platform.db.provider import create_engine
from kardiomonitor.shared_kernel import DomainRuleViolation

Sessions = async_sessionmaker[AsyncSession]


class AdminCommandError(Exception):
    """Команду выполнить нельзя; сообщение — для администратора."""


async def create_clinician(
    sessions: Sessions,
    login: Login,
    name: ClinicianName,
    password: Password,
    hasher: PasswordHasher,
) -> Clinician:
    async with sessions.begin() as session:
        clinicians = SqlClinicians(session)
        if await clinicians.by_login(login) is not None:
            raise AdminCommandError(f"логин {login.value} уже занят")
        clinician = Clinician.register(login, name, await hasher.hash(password))
        await clinicians.add(clinician)
    return clinician


async def reset_password(
    sessions: Sessions, login: Login, password: Password, hasher: PasswordHasher
) -> None:
    """Новый пароль и снятие блокировки входа."""
    async with sessions.begin() as session:
        clinicians = SqlClinicians(session)
        clinician = await _clinician(clinicians, login)
        await clinicians.save(clinician.with_password_hash(await hasher.hash(password)))


async def assign_patient(sessions: Sessions, patient_id: PatientId, login: Login) -> None:
    """Назначить лечащего врача; прежний врач перестаёт видеть пациента."""
    async with sessions.begin() as session:
        clinician = await _clinician(SqlClinicians(session), login)
        registry = SqlPatientRegistry(session)
        patient = await registry.by_id(patient_id)
        if patient is None:
            raise AdminCommandError(f"пациента {patient_id} нет")
        await registry.save_attending_clinician(patient.with_attending_clinician(clinician.id))


async def patients_report(sessions: Sessions) -> list[str]:
    """Строки «идентификатор, имя, логин лечащего врача или —» в порядке регистрации."""
    async with sessions() as session:
        clinicians = SqlClinicians(session)
        lines = []
        for patient in await SqlPatientRegistry(session).all_patients():
            attending = (
                None
                if patient.attending_clinician_id is None
                else await clinicians.by_id(patient.attending_clinician_id)
            )
            login = "—" if attending is None else attending.login.value
            lines.append(f"{patient.id}  {patient.name.value}  врач: {login}")
        return lines


async def _clinician(clinicians: SqlClinicians, login: Login) -> Clinician:
    clinician = await clinicians.by_login(login)
    if clinician is None:
        raise AdminCommandError(f"врача с логином {login.value} нет")
    return clinician


def _ask_password() -> Password:
    password = getpass("Пароль (от 12 знаков): ")
    if getpass("Повторите пароль: ") != password:
        raise AdminCommandError("пароли не совпадают")
    return Password(password)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="kardiomonitor.app.admin", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create-clinician", help="создать аккаунт врача")
    create.add_argument("login")
    create.add_argument("name")
    commands.add_parser("reset-password", help="новый пароль врача").add_argument("login")
    assign = commands.add_parser("assign", help="назначить пациенту лечащего врача")
    assign.add_argument("patient_id", type=UUID)
    assign.add_argument("login")
    commands.add_parser("patients", help="пациенты и их лечащие врачи")
    return parser


async def _run(arguments: argparse.Namespace, sessions: Sessions) -> list[str]:
    hasher = Argon2PasswordHasher()
    match arguments.command:
        case "create-clinician":
            login, name = Login(arguments.login), ClinicianName(arguments.name)
            clinician = await create_clinician(sessions, login, name, _ask_password(), hasher)
            return [f"врач создан: {clinician.login.value}"]
        case "reset-password":
            await reset_password(sessions, Login(arguments.login), _ask_password(), hasher)
            return ["пароль изменён, вход разблокирован"]
        case "assign":
            await assign_patient(sessions, PatientId(arguments.patient_id), Login(arguments.login))
            return ["лечащий врач назначен"]
        case _:
            return await patients_report(sessions)


async def _main(arguments: argparse.Namespace, settings: Settings) -> list[str]:
    engine = create_engine(settings.database_url)
    try:
        async with engine.begin() as connection:
            await connection.run_sync(metadata.create_all)
        return await _run(arguments, async_sessionmaker(engine, expire_on_commit=False))
    finally:
        await engine.dispose()


def main(
    argv: Sequence[str] | None = None, write: Callable[[str], object] = sys.stdout.write
) -> int:
    arguments = _parser().parse_args(argv)
    try:
        lines = asyncio.run(_main(arguments, Settings()))
    except (AdminCommandError, DomainRuleViolation) as error:
        write(f"ошибка: {error}\n")
        return 1
    for line in lines:
        write(line + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

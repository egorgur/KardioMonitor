"""Настройки из переменных окружения `KARDIO_*` и файла `.env` (не коммитится)."""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Настройки сервера; пример — `.env.example`."""

    model_config = SettingsConfigDict(env_prefix="KARDIO_", env_file=".env", extra="ignore")

    database_url: str = "sqlite+aiosqlite:///./kardio.db"
    secure_cookies: bool = True
    """Cookie сеанса врача только по HTTPS (ADR-0011). localhost браузеры считают безопасным;
    выключать — только для разработки по HTTP на другом адресе."""

    periodic_checks: bool = True
    """Дневные оповещения раз в минуту в процессе сервера (ADR-0009). Тесты выключают: их
    запускают явно."""

    frontend_dist: Path = Path("../frontend/dist")
    """Собранная панель (`npm run build`); нет каталога — сервер отдаёт только API."""

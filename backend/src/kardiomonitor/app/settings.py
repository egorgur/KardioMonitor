"""Настройки из переменных окружения `KARDIO_*` и файла `.env` (не коммитится)."""

from pathlib import Path

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Настройки сервера; пример — `.env.example`."""

    model_config = SettingsConfigDict(env_prefix="KARDIO_", env_file=".env", extra="ignore")

    database_url: str = "sqlite+aiosqlite:///./kardio.db"
    dashboard_key: SecretStr | None = None
    """Ключ панели клиники; не задан — панель открыта всем, у кого есть ссылка ngrok."""

    frontend_dist: Path = Path("../frontend/dist")
    """Собранная панель (`npm run build`); нет каталога — сервер отдаёт только API."""

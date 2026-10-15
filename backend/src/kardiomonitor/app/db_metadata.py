"""Таблицы всех контекстов — чтобы `Base.metadata` знала их все до `create_all`."""

from kardiomonitor.features.monitoring.data import tables as monitoring_tables
from kardiomonitor.features.patients.data import tables as patients_tables
from kardiomonitor.platform.db.base import Base

TABLE_MODULES = (patients_tables, monitoring_tables)
metadata = Base.metadata

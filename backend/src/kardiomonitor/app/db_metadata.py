"""Таблицы всех контекстов — чтобы `Base.metadata` знала их все до `create_all`."""

from kardiomonitor.features.access.data import tables as access_tables
from kardiomonitor.features.alerts.data import tables as alerts_tables
from kardiomonitor.features.monitoring.data import tables as monitoring_tables
from kardiomonitor.features.patients.data import tables as patients_tables
from kardiomonitor.features.rehab.data import tables as rehab_tables
from kardiomonitor.platform.db.base import Base

TABLE_MODULES = (access_tables, patients_tables, monitoring_tables, rehab_tables, alerts_tables)
metadata = Base.metadata

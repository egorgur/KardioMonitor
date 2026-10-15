"""Правила кода, которых нет в ruff и import-linter (coding-rules.md, ADR-0006).

Упал тест — исправлять код. Ослабить правило можно только изменив документы с согласия человека.
"""

import ast
import re
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[2]
SOURCES = sorted((BACKEND / "src" / "kardiomonitor").rglob("*.py"))
TESTS = sorted((BACKEND / "tests").rglob("*.py"))
FEATURE_TESTS = sorted((BACKEND / "tests" / "features").rglob("test_*.py"))

MAX_MODULE_LINES = 300
FORBIDDEN_CLASS_SUFFIXES = (
    "Manager", "Helper", "Util", "Utils", "Processor", "Handler", "Service", "Data", "Info", "Item",
)  # fmt: skip
FORBIDDEN_MODULE_NAMES = {"utils.py", "helpers.py", "common.py", "services.py", "misc.py"}
CYRILLIC = re.compile(r"[а-яё]", re.IGNORECASE)


def _relative(path: Path) -> str:
    return str(path.relative_to(BACKEND))


def test_модуль_не_длиннее_300_строк() -> None:
    too_long = [
        f"{_relative(path)}: {lines} строк — разделите по смыслу"
        for path in SOURCES + TESTS
        if (lines := len(path.read_text().splitlines())) > MAX_MODULE_LINES
    ]

    assert not too_long, "модуль ≤ 300 строк (coding-rules.md §3)\n" + "\n".join(too_long)


def test_нет_модулей_с_пустыми_именами() -> None:
    misnamed = [_relative(path) for path in SOURCES if path.name in FORBIDDEN_MODULE_NAMES]

    assert not misnamed, "utils/helpers/services — свалка без имени (§1)\n" + "\n".join(misnamed)


def test_в_именах_классов_контекстов_нет_пустых_суффиксов() -> None:
    features = [path for path in SOURCES if "features" in path.parts]
    violations = [
        f"{_relative(path)}:{node.lineno}: {node.name}"
        for path in features
        for node in ast.walk(ast.parse(path.read_text()))
        if isinstance(node, ast.ClassDef) and node.name.endswith(FORBIDDEN_CLASS_SUFFIXES)
    ]

    assert not violations, "имена — из глоссария (coding-rules.md §2)\n" + "\n".join(violations)


def test_имена_тестов_контекстов_описывают_поведение_по_русски() -> None:
    violations = [
        f"{_relative(path)}:{node.lineno}: {node.name}"
        for path in FEATURE_TESTS
        for node in ast.walk(ast.parse(path.read_text()))
        if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef)
        and node.name.startswith("test_")
        and not CYRILLIC.search(node.name)
    ]

    assert not violations, "test_поведение_на_русском (testing.md)\n" + "\n".join(violations)

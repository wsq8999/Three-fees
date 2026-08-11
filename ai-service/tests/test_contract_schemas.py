from __future__ import annotations

import json
from pathlib import Path

from jsonschema import Draft202012Validator

CONTRACT_DIRECTORY = Path(__file__).resolve().parents[2] / "contracts" / "ai" / "v1"


def test_all_atomic_request_and_response_schemas_are_valid_draft_2020_12() -> None:
    schema_files = sorted(CONTRACT_DIRECTORY.glob("*.schema.json"))

    assert len(schema_files) == 12
    for schema_file in schema_files:
        schema = json.loads(schema_file.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        assert schema["$id"].endswith(schema_file.name)

from __future__ import annotations

import json
from pathlib import Path

from app.models import (
    CorrectionInterpretationRequest,
    CorrectionInterpretationResponse,
    DocumentParseRequest,
    DocumentParseResponse,
    FactExtractionRequest,
    FactExtractionResponse,
    ReasonJudgmentRequest,
    ReasonJudgmentResponse,
    ReportCompositionRequest,
    ReportCompositionResponse,
    ReportAssistanceRequest,
    ReportAssistanceResponse,
)

SCHEMAS = {
    "document-parse-request": DocumentParseRequest,
    "document-parse-response": DocumentParseResponse,
    "fact-extraction-request": FactExtractionRequest,
    "fact-extraction-response": FactExtractionResponse,
    "reason-judgment-request": ReasonJudgmentRequest,
    "reason-judgment-response": ReasonJudgmentResponse,
    "report-composition-request": ReportCompositionRequest,
    "report-composition-response": ReportCompositionResponse,
    "correction-interpretation-request": CorrectionInterpretationRequest,
    "correction-interpretation-response": CorrectionInterpretationResponse,
    "report-assistance-request": ReportAssistanceRequest,
    "report-assistance-response": ReportAssistanceResponse,
}


def main() -> None:
    output_directory = Path(__file__).resolve().parents[2] / "contracts" / "ai" / "v1"
    output_directory.mkdir(parents=True, exist_ok=True)
    for name, model in SCHEMAS.items():
        schema = model.model_json_schema(by_alias=True, mode="validation")
        schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
        schema["$id"] = f"https://three-fees.example/contracts/ai/v1/{name}.schema.json"
        target = output_directory / f"{name}.schema.json"
        target.write_text(
            json.dumps(schema, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()

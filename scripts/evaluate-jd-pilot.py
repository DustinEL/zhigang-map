from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from openpyxl import load_workbook


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DIR = PROJECT_ROOT / "test-data" / "evaluation"
NO_RESPONSIBILITY = "没有提到职责"
NUMBERED_ITEM = re.compile(r"(?:（\d{1,2}）|(?<![\d.-])\d{1,2}\s*[.、．）)])(?=\s*[^\d])")


def load_frozen_evaluator():
    path = Path(__file__).with_name("evaluate-human-jd-gold.py")
    spec = importlib.util.spec_from_file_location("frozen_jd_evaluator", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def verify_seal_values(inputs: bytes, predictions: bytes, seal: dict, expected_count: int) -> None:
    if seal.get("sampleCount") != expected_count:
        raise ValueError("seal sampleCount does not match the expected count")
    if seal.get("inputsSha256") != sha256_bytes(inputs):
        raise ValueError("seal inputsSha256 does not match the input file")
    if seal.get("predictionsSha256") != sha256_bytes(predictions):
        raise ValueError("seal predictionsSha256 does not match the prediction file")


def parse_responsibilities(value, uncertainty) -> list[str] | None:
    if NO_RESPONSIBILITY in str(uncertainty or ""):
        return []
    text = str(value or "").strip()
    if not text:
        return None
    text = NUMBERED_ITEM.sub("\n", text)
    text = re.sub(r"(?:^|\n)\s*[●·]\s*", "\n", text)
    return [item.strip(" \t\r\n；;。") for item in re.split(r"[\n；;]+", text) if item.strip(" \t\r\n；;。")]


def parse_skills(value) -> list[str]:
    return [item.strip() for item in str(value or "").split("|") if item.strip()]


def load_inputs(path: Path, expected_count: int) -> list[dict]:
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(records) != expected_count:
        raise ValueError(f"evaluation input count is {len(records)}, expected {expected_count}")
    return records


def load_annotations(path: Path, inputs: list[dict]) -> tuple[list[dict], list[dict]]:
    workbook = load_workbook(path, read_only=True, data_only=True)
    sheet = workbook.worksheets[1]
    headers = {cell.value: cell.column for cell in sheet[1]}
    required_headers = [
        "recordId", "标准岗位名称", "技能标注", "核心职责标注", "不确定项",
        "标注状态", "标注员姓名或编号", "开始时间", "完成时间",
    ]
    missing_headers = [name for name in required_headers if name not in headers]
    if missing_headers:
        raise ValueError(f"annotation workbook is missing columns: {missing_headers}")

    cases = []
    issues = []
    for index, input_record in enumerate(inputs, start=2):
        source_id = sheet.cell(index, headers["recordId"]).value
        if source_id != input_record["sourceRecordId"]:
            raise ValueError(f"annotation/input order mismatch at sample {index - 1}")
        status = str(sheet.cell(index, headers["标注状态"]).value or "").strip()
        metadata = [sheet.cell(index, headers[name]).value for name in required_headers[6:]]
        if status != "完成" or any(value in (None, "") for value in metadata):
            raise ValueError(f"sample {source_id} has incomplete annotation metadata")
        title = str(sheet.cell(index, headers["标准岗位名称"]).value or "").strip()
        skills = parse_skills(sheet.cell(index, headers["技能标注"]).value)
        uncertainty = sheet.cell(index, headers["不确定项"]).value
        responsibilities = parse_responsibilities(
            sheet.cell(index, headers["核心职责标注"]).value, uncertainty)
        if not title or not skills:
            raise ValueError(f"sample {source_id} is missing title or skills")
        if responsibilities is None:
            issues.append({
                "sampleNumber": index - 1,
                "recordId": source_id,
                "field": "responsibility",
                "action": "excluded-from-responsibility-metrics",
                "reason": "blank annotation without a no-responsibility explanation",
            })
        cases.append({
            "sampleNumber": index - 1,
            "recordId": source_id,
            "title": title,
            "skills": skills,
            "responsibilities": responsibilities,
        })
    workbook.close()
    return cases, issues


def evaluate(cases: list[dict], predictions: dict, frozen) -> dict:
    counts_type = frozen.Counts
    zero = counts_type(0, 0, 0)
    totals = {"title": zero, "skill": zero, "responsibility": zero}
    case_counts = {"skill": [], "responsibility": [], "primarySkillsResponsibilities": []}
    errors = []
    responsibility_sample_count = 0

    for case in cases:
        prediction = predictions.get(str(case["sampleNumber"]))
        if not isinstance(prediction, dict):
            raise ValueError(f"missing sealed prediction for sample {case['sampleNumber']}")
        predicted_skills = [
            fact.get("normalizedValue", "") for fact in prediction.get("facts", [])
            if fact.get("type") == "SKILL"
        ]
        title_counts = frozen.exact_set_counts([case["title"]], [prediction.get("title", "")], frozen.normalize_text)
        skill_counts = frozen.exact_set_counts(case["skills"], predicted_skills, frozen.normalize_skill)
        responsibility_counts = zero
        responsibility_scored = case["responsibilities"] is not None
        if responsibility_scored:
            responsibility_counts = frozen.match_responsibilities(
                case["responsibilities"], prediction.get("responsibilities", []))
            responsibility_sample_count += 1
            case_counts["responsibility"].append(responsibility_counts)

        totals["title"] += title_counts
        totals["skill"] += skill_counts
        totals["responsibility"] += responsibility_counts
        primary_counts = skill_counts + responsibility_counts
        case_counts["skill"].append(skill_counts)
        case_counts["primarySkillsResponsibilities"].append(primary_counts)
        if title_counts.fp or title_counts.fn or skill_counts.fp or skill_counts.fn or (
            responsibility_scored and (responsibility_counts.fp or responsibility_counts.fn)
        ):
            errors.append({
                "sampleNumber": case["sampleNumber"],
                "recordId": case["recordId"],
                "responsibilityScored": responsibility_scored,
                "counts": {
                    "title": title_counts.__dict__,
                    "skill": skill_counts.__dict__,
                    "responsibility": responsibility_counts.__dict__,
                },
            })

    primary = totals["skill"] + totals["responsibility"]
    fields = {name: frozen.metric_from_counts(value.tp, value.fp, value.fn) for name, value in totals.items()}
    combined_metric = frozen.metric_from_counts(primary.tp, primary.fp, primary.fn)
    return {
        "primarySkillsResponsibilities": combined_metric,
        "fields": fields,
        "microConfidenceIntervals95": {
            name: frozen.bootstrap_micro_metric(values)
            for name, values in case_counts.items()
        },
        "coverage": {
            "title": len(cases),
            "skill": len(cases),
            "responsibility": responsibility_sample_count,
            "totalSamples": len(cases),
        },
        "errorSampleCount": len(errors),
        "errors": errors,
    }


def decision_for(score: float) -> str:
    if score >= 92:
        return "advance-to-holdout-decision"
    if score >= 88:
        return "repair-confirmed-errors-only"
    return "stop-and-do-not-annotate-holdout"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", type=Path, default=DEFAULT_DIR)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--expected-count", type=int, default=30)
    args = parser.parse_args()
    report_path = args.report or args.dir / "pilot-30-evaluation-report.json"
    inputs_path = args.dir / "pilot-30-evaluation-inputs.jsonl"
    predictions_path = args.dir / "pilot-30-predictions-sealed.json"
    seal_path = args.dir / "pilot-30-prediction-seal.json"
    workbook_path = args.dir / "annotation" / "annotator.xlsx"

    inputs_bytes = inputs_path.read_bytes()
    predictions_bytes = predictions_path.read_bytes()
    seal = json.loads(seal_path.read_text(encoding="utf-8"))
    verify_seal_values(inputs_bytes, predictions_bytes, seal, args.expected_count)
    inputs = load_inputs(inputs_path, args.expected_count)
    cases, issues = load_annotations(workbook_path, inputs)
    predictions = json.loads(predictions_bytes.decode("utf-8"))
    if len(predictions) != args.expected_count:
        raise ValueError("sealed prediction count does not match the expected count")
    frozen = load_frozen_evaluator()
    result = evaluate(cases, predictions, frozen)
    score = result["primarySkillsResponsibilities"]["f1"]
    report = {
        "schemaVersion": "1.0.0",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "official": False,
        "status": "PRELIMINARY_PILOT",
        "sampleCount": args.expected_count,
        "annotationSha256": sha256_file(workbook_path),
        "inputsSha256": sha256_file(inputs_path),
        "predictionsSha256": sha256_file(predictions_path),
        "sealSha256": sha256_file(seal_path),
        "metricDefinition": "Frozen JD evaluator: normalized exact skill match; responsibility one-to-one character-bigram similarity >= 0.62; entity-level micro F1.",
        "annotationIssues": issues,
        "stopLossDecision": decision_for(score),
        **result,
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "report": str(report_path),
        "score": report["primarySkillsResponsibilities"],
        "fields": report["fields"],
        "coverage": report["coverage"],
        "annotationIssues": report["annotationIssues"],
        "decision": report["stopLossDecision"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from datetime import date
from pathlib import Path

from openpyxl import load_workbook


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_WORKBOOK = PROJECT_ROOT / "test-data" / "jd-extraction" / "JD独立盲测50条-待人工复核.xlsx"
DEFAULT_AUDIT = PROJECT_ROOT / "test-data" / "evaluation" / "jd-blind-50-preannotation-audit.json"
EVALUATOR_PATH = PROJECT_ROOT / "scripts" / "evaluate-human-jd-gold.py"
HIGH_RISK_SKILLS = {"AI", "Agent", "LLM", "大模型", "智能体", "Go", "MCP", "软件开发", "算法工程", "性能调优"}


def load_evaluator():
    spec = importlib.util.spec_from_file_location("jd_human_evaluator", EVALUATOR_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def annotate(workbook_path: Path, audit_path: Path, base_url: str) -> dict:
    evaluator = load_evaluator()
    username, password = evaluator.local_credentials()
    client = evaluator.ApiClient(base_url, username, password)
    workbook = load_workbook(workbook_path)
    sheet = workbook["JD人工标注"]
    audit_rows = []

    for row in range(2, sheet.max_row + 1):
        sample_number = int(sheet.cell(row, 1).value)
        record_id = int(sheet.cell(row, 2).value)
        title_hint = str(sheet.cell(row, 7).value or "").strip()
        source = str(sheet.cell(row, 9).value or "")
        result = client.post("/api/job-descriptions/extractions", {"documentId": record_id, "text": f"岗位名称：{title_hint}\n{source}"})
        facts = [fact for fact in result.get("facts", []) if fact.get("type") == "SKILL"]
        skills = list(dict.fromkeys(str(fact.get("normalizedValue") or "").strip() for fact in facts if fact.get("normalizedValue")))
        responsibilities = list(dict.fromkeys(str(value).strip() for value in result.get("responsibilities", []) if str(value).strip()))

        evidence_errors = []
        for fact in facts:
            raw = str(fact.get("rawValue") or "")
            if raw and raw not in source and raw not in title_hint:
                evidence_errors.append(f"技能缺少原文字面证据：{fact.get('normalizedValue')}")
        risks = sorted(HIGH_RISK_SKILLS & set(skills))
        warnings = list(result.get("warnings", []))
        if not skills:
            warnings.append("未识别到技能")
        if not responsibilities:
            warnings.append("未识别到职责")

        sheet.cell(row, 10, "已完成")
        sheet.cell(row, 11, result.get("title") or title_hint or "未明确")
        sheet.cell(row, 15, "|".join(skills))
        sheet.cell(row, 16, "||".join(responsibilities))
        notes = ["Codex v14规则链预标注，待人工逐条复核"]
        if risks:
            notes.append("优先复核高风险技能：" + "、".join(risks))
        notes.extend(evidence_errors)
        notes.extend(warnings)
        sheet.cell(row, 18, "；".join(dict.fromkeys(notes)))
        sheet.cell(row, 19, "Codex v14辅助预标注")
        sheet.cell(row, 20, date.today().isoformat())
        sheet.cell(row, 21, "待复核")
        sheet.cell(row, 22, None)
        sheet.cell(row, 23, None)
        audit_rows.append({
            "sampleNumber": sample_number,
            "recordId": record_id,
            "skillCount": len(skills),
            "responsibilityCount": len(responsibilities),
            "highRiskSkills": risks,
            "evidenceErrors": evidence_errors,
            "warnings": warnings,
        })
        print(f"[{sample_number}/50] skills={len(skills)} responsibilities={len(responsibilities)} risks={len(risks)}", flush=True)

    workbook.save(workbook_path)
    audit = {
        "workbook": str(workbook_path),
        "sampleCount": len(audit_rows),
        "evidenceErrorCount": sum(bool(row["evidenceErrors"]) for row in audit_rows),
        "emptySkillCount": sum(row["skillCount"] == 0 for row in audit_rows),
        "emptyResponsibilityCount": sum(row["responsibilityCount"] == 0 for row in audit_rows),
        "priorityReviewSamples": [row for row in audit_rows if row["highRiskSkills"] or row["evidenceErrors"] or row["warnings"]],
        "rows": audit_rows,
    }
    audit_path.parent.mkdir(parents=True, exist_ok=True)
    audit_path.write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8")
    return audit


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workbook", type=Path, default=DEFAULT_WORKBOOK)
    parser.add_argument("--audit", type=Path, default=DEFAULT_AUDIT)
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    args = parser.parse_args()
    print(json.dumps(annotate(args.workbook, args.audit, args.base_url), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

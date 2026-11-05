from __future__ import annotations

import json
from pathlib import Path

from openpyxl import load_workbook

import importlib.util


PROJECT_ROOT = Path(__file__).resolve().parents[1]
WORKBOOK_PATH = PROJECT_ROOT / "test-data" / "jd-extraction" / "JD人工标注样本100条.xlsx"
DB_MODULE_PATH = PROJECT_ROOT / "scripts" / "create-jd-annotation-workbook.py"


def load_db_module():
    spec = importlib.util.spec_from_file_location("jd_workbook_db", DB_MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fetch_facts(module, environment: dict[str, str]) -> dict[int, dict[str, list[str]]]:
    query = """
SELECT HEX(JSON_OBJECT(
  'recordId', df.dynamic_record_id,
  'factType', df.fact_type,
  'value', CASE WHEN df.fact_type = 'RESPONSIBILITY' THEN df.raw_value ELSE df.canonical_value END,
  'rawValue', df.raw_value,
  'startOffset', df.start_offset,
  'endOffset', df.end_offset,
  'offsetVerified', df.offset_verified
))
FROM zhigang_map.dynamic_fact df
WHERE df.dynamic_record_id IN (
  SELECT id FROM zhigang_map.dynamic_record WHERE data_mode='REAL'
)
ORDER BY df.dynamic_record_id, df.fact_type, df.start_offset;
""".strip()
    import os
    import subprocess
    command = [
        str(module.MYSQL_CLIENT), "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names",
        "-h", "127.0.0.1", "-P", environment.get("LOCAL_MYSQL_PORT", "3306"),
        "-u", environment.get("MYSQL_USERNAME", "root"), "-e", query,
    ]
    process_environment = os.environ.copy()
    process_environment["MYSQL_PWD"] = environment["MYSQL_PASSWORD"]
    completed = subprocess.run(command, check=True, capture_output=True, text=True, encoding="utf-8", env=process_environment)
    grouped: dict[int, dict[str, list[str]]] = {}
    for line in completed.stdout.splitlines():
        if not line.strip():
            continue
        fact = json.loads(bytes.fromhex(line.strip()).decode("utf-8"))
        entry = grouped.setdefault(int(fact["recordId"]), {"SKILL": [], "RESPONSIBILITY": []})
        if not fact["offsetVerified"]:
            continue
        value = str(fact["value"] or "").strip().rstrip("。；;")
        if value and value not in entry[fact["factType"]]:
            entry[fact["factType"]].append(value)
    return grouped


def main() -> None:
    module = load_db_module()
    annotation_spec = importlib.util.spec_from_file_location(
        "ai_preannotation_helpers", PROJECT_ROOT / "scripts" / "ai-preannotate-jd-workbook.py"
    )
    annotation_helpers = importlib.util.module_from_spec(annotation_spec)
    annotation_spec.loader.exec_module(annotation_helpers)
    environment = module.load_environment()
    facts = fetch_facts(module, environment)
    workbook = load_workbook(WORKBOOK_PATH)
    sheet = workbook["JD人工标注"]
    missing = []
    for row in range(2, sheet.max_row + 1):
        record_id = int(sheet.cell(row, 2).value)
        record_facts = facts.get(record_id, {"SKILL": [], "RESPONSIBILITY": []})
        responsibilities = annotation_helpers.extract_responsibilities_from_source(str(sheet.cell(row, 9).value or ""))
        if not responsibilities:
            responsibilities = record_facts["RESPONSIBILITY"]
        if not record_facts["SKILL"] or not responsibilities:
            missing.append(record_id)
        sheet.cell(row, 10, "已完成")
        sheet.cell(row, 11, sheet.cell(row, 7).value)
        sheet.cell(row, 15, "|".join(record_facts["SKILL"]))
        sheet.cell(row, 16, "||".join(responsibilities))
        sheet.cell(row, 18, "Codex使用数据库中已通过偏移校验的原文证据预标注，待人工逐条复核")
        sheet.cell(row, 19, "Codex AI辅助预标注")
        sheet.cell(row, 20, "2026-08-12")
        sheet.cell(row, 21, "待复核")
        sheet.cell(row, 22, None)
        sheet.cell(row, 23, None)
    if missing:
        raise AssertionError(f"以下记录缺少技能或职责证据：{missing}")
    workbook.save(WORKBOOK_PATH)
    print(json.dumps({"annotated": sheet.max_row - 1, "missing": missing, "workbook": str(WORKBOOK_PATH)}, ensure_ascii=False))


if __name__ == "__main__":
    main()

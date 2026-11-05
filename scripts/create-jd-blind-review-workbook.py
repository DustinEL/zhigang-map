from __future__ import annotations

import importlib.util
import json
from collections import Counter
from pathlib import Path

from openpyxl import Workbook, load_workbook


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_SCRIPT = PROJECT_ROOT / "scripts" / "create-jd-annotation-workbook.py"
DEVELOPMENT_GOLD = PROJECT_ROOT / "test-data" / "jd-extraction" / "JD人工复核金标100条-冻结版.xlsx"
OUTPUT_PATH = PROJECT_ROOT / "test-data" / "jd-extraction" / "JD独立盲测50条-待人工复核.xlsx"
SAMPLE_SIZE = 50


def load_source_module():
    spec = importlib.util.spec_from_file_location("jd_annotation_workbook", SOURCE_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def development_record_ids() -> set[int]:
    workbook = load_workbook(DEVELOPMENT_GOLD, read_only=True, data_only=True)
    sheet = workbook["JD人工标注"]
    return {int(row[0]) for row in sheet.iter_rows(min_row=2, min_col=2, max_col=2, values_only=True)}


def main() -> None:
    module = load_source_module()
    module.SAMPLE_SIZE = SAMPLE_SIZE
    module.SAMPLE_SEED = "zhigang-jd-blind-20260813-v1"

    population = module.fetch_records(module.load_environment())
    excluded_ids = development_record_ids()
    candidates = [record for record in population if int(record["id"]) not in excluded_ids]
    sample = module.stratified_sample(candidates)

    if len(population) != 317 or len(candidates) != 217:
        raise AssertionError(f"数据规模异常：总数={len(population)}，盲测候选={len(candidates)}")
    if len(sample) != SAMPLE_SIZE or {int(record["id"]) for record in sample} & excluded_ids:
        raise AssertionError("盲测样本数量错误或与开发集重叠")

    workbook = Workbook()
    module.add_instructions(workbook, candidates, sample)
    module.add_annotation_sheet(workbook, sample)
    module.add_statistics(workbook, candidates, sample)
    workbook.properties.title = "智岗 JD 独立盲测样本（50条，待人工复核）"
    workbook.properties.subject = "排除开发集后的JD解析准确率盲测"
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    workbook.save(OUTPUT_PATH)
    module.validate_workbook(OUTPUT_PATH, {int(record["id"]) for record in sample})

    print(json.dumps({
        "output": str(OUTPUT_PATH),
        "population": len(population),
        "excludedDevelopmentRecords": len(excluded_ids),
        "blindCandidates": len(candidates),
        "sampleCount": len(sample),
        "overlap": 0,
        "roles": dict(sorted(Counter(record["roleCode"] for record in sample).items())),
        "sources": dict(sorted(Counter(record["sourceName"] for record in sample).items())),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

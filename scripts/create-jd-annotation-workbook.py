from __future__ import annotations

import hashlib
import json
import os
import subprocess
from collections import Counter, defaultdict
from pathlib import Path

from openpyxl import Workbook, load_workbook
from openpyxl.formatting.rule import FormulaRule
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.datavalidation import DataValidation


PROJECT_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_PATH = PROJECT_ROOT / "test-data" / "jd-extraction" / "JD人工标注样本100条.xlsx"
MYSQL_CLIENT = Path("D:/mysql-8.1.0-winx64/mysql-8.1.0-winx64/bin/mysql.exe")
SAMPLE_SIZE = 100
SAMPLE_SEED = "zhigang-jd-human-gold-20260812-v1"


def load_environment() -> dict[str, str]:
    values: dict[str, str] = {}
    for line in (PROJECT_ROOT / ".env.local").read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        name, value = stripped.split("=", 1)
        values[name.strip()] = value.strip()
    return values


def fetch_records(environment: dict[str, str]) -> list[dict]:
    port = environment.get("LOCAL_MYSQL_PORT", "3306")
    username = environment.get("MYSQL_USERNAME", "root")
    query = """
SELECT HEX(JSON_OBJECT(
  'id', dr.id,
  'recordKey', dr.source_record_key,
  'sourceCode', ds.source_code,
  'sourceName', ds.source_name,
  'sourceUrl', COALESCE(dr.canonical_url, dr.source_url, ''),
  'company', dr.company_name,
  'city', COALESCE(dr.city, ''),
  'collectedTitle', dr.title,
  'publishedAt', COALESCE(DATE_FORMAT(dr.published_at, '%Y-%m-%d %H:%i:%s'), ''),
  'collectedAt', DATE_FORMAT(dr.collected_at, '%Y-%m-%d %H:%i:%s'),
  'contentHash', dr.content_hash,
  'roleCode', SUBSTRING_INDEX(SUBSTRING_INDEX(dr.status_reason, '，', 1), 'TARGET_ROLE:', -1),
  'rawText', dr.raw_text
))
FROM zhigang_map.dynamic_record dr
JOIN zhigang_map.data_source ds ON ds.id = dr.source_id
WHERE dr.data_mode = 'REAL'
ORDER BY dr.id;
""".strip()
    command = [
        str(MYSQL_CLIENT), "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names",
        "-h", "127.0.0.1", "-P", str(port), "-u", username, "-e", query,
    ]
    process_environment = os.environ.copy()
    process_environment["MYSQL_PWD"] = environment["MYSQL_PASSWORD"]
    completed = subprocess.run(
        command, check=True, capture_output=True, text=True, encoding="utf-8", env=process_environment
    )
    return [
        json.loads(bytes.fromhex(line.strip()).decode("utf-8"))
        for line in completed.stdout.splitlines()
        if line.strip()
    ]


def deterministic_rank(record: dict) -> str:
    value = f"{SAMPLE_SEED}|{record['id']}|{record['contentHash']}"
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def largest_remainder_quotas(counts: Counter, target: int, ensure_each: bool = True) -> dict[str, int]:
    total = sum(counts.values())
    exact = {key: target * count / total for key, count in counts.items()}
    quotas = {key: int(value) for key, value in exact.items()}
    if ensure_each:
        for key in counts:
            if counts[key] > 0 and quotas[key] == 0:
                quotas[key] = 1
    while sum(quotas.values()) > target:
        candidates = [key for key, value in quotas.items() if value > 1]
        key = min(candidates, key=lambda item: (exact[item] - quotas[item], item))
        quotas[key] -= 1
    while sum(quotas.values()) < target:
        candidates = [key for key in counts if quotas[key] < counts[key]]
        key = max(candidates, key=lambda item: (exact[item] - quotas[item], counts[item], item))
        quotas[key] += 1
    return quotas


def stratified_sample(records: list[dict]) -> list[dict]:
    by_role: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        by_role[record["roleCode"]].append(record)
    role_quotas = largest_remainder_quotas(Counter({key: len(value) for key, value in by_role.items()}), SAMPLE_SIZE)

    selected: list[dict] = []
    for role, role_records in sorted(by_role.items()):
        quota = role_quotas[role]
        by_source: dict[str, list[dict]] = defaultdict(list)
        for record in role_records:
            by_source[record["sourceCode"]].append(record)
        source_quotas = largest_remainder_quotas(
            Counter({key: len(value) for key, value in by_source.items()}), quota, ensure_each=False
        )
        for source, source_records in sorted(by_source.items()):
            ranked = sorted(source_records, key=deterministic_rank)
            selected.extend(ranked[:source_quotas[source]])

    return sorted(selected, key=lambda item: (item["roleCode"], item["sourceCode"], deterministic_rank(item)))


def add_instructions(workbook: Workbook, population: list[dict], sample: list[dict]) -> None:
    sheet = workbook.active
    sheet.title = "标注说明"
    lines = [
        ("智岗 JD 人工金标说明", "title"),
        ("用途", f"对{len(sample)}条独立抽样JD进行逐字段人工标注，用于当前参赛版本的JD解析准确率复测。"),
        ("数据库核验", f"dynamic_record共{len(population)}条；均为REAL、PROMOTED，正文哈希互不重复。"),
        ("抽样方法", f"按目标岗位类别和来源平台分层随机抽样；固定种子：{SAMPLE_SEED}。"),
        ("独立性要求", "只依据JD原文填写。表格没有预填数据库技能、职责或解析器预测结果。"),
        ("字段分隔", "多个技能使用英文竖线 | 分隔；多条职责、行业场景使用双竖线 || 分隔。"),
        ("不存在的字段", "原文没有明确内容时填【未明确】，不要依据常识补全。"),
        ("岗位名称", "以原文明确出现的岗位名称为准；采集岗位标题仅作定位参考，需与原文核对。"),
        ("技能", "仅标注明确要求、优先或提及的技术技能；不要把职责动作自动推断成技能。"),
        ("职责", "保留原文语义，去掉序号即可；不要改写成原文没有的职责。"),
        ("标注状态", "未标注 → 标注中 → 已完成 → 已复核。只有已复核样本才能进入最终评测。"),
        ("建议流程", "第一位标注员填写，第二位复核员对照原文检查；争议内容写入备注。"),
        ("样本数量", str(len(sample))),
    ]
    for row_index, (label, value) in enumerate(lines, 1):
        sheet.cell(row_index, 1, label)
        sheet.cell(row_index, 2, value)
    sheet["A1"].font = Font(size=16, bold=True, color="FFFFFF")
    sheet["A1"].fill = PatternFill("solid", fgColor="1F4E78")
    sheet["B1"].fill = PatternFill("solid", fgColor="1F4E78")
    for row in range(2, len(lines) + 1):
        sheet.cell(row, 1).font = Font(bold=True, color="1F4E78")
        sheet.cell(row, 1).fill = PatternFill("solid", fgColor="D9EAF7")
        sheet.cell(row, 2).alignment = Alignment(wrap_text=True, vertical="top")
    sheet.column_dimensions["A"].width = 18
    sheet.column_dimensions["B"].width = 105
    sheet.freeze_panes = "A2"


def add_annotation_sheet(workbook: Workbook, sample: list[dict]) -> None:
    sheet = workbook.create_sheet("JD人工标注")
    headers = [
        "样本序号", "数据库记录ID", "目标岗位分层", "来源平台", "企业", "城市", "采集岗位标题（仅参考）",
        "原文网址", "JD原文", "标注状态", "人工岗位名称", "人工薪资", "人工职级/经验要求", "人工学历要求",
        "人工技能（用|分隔）", "人工职责（用||分隔）", "人工行业场景（用||分隔）", "标注备注", "标注人", "标注日期",
        "复核结论", "复核人", "复核日期", "正文SHA-256",
    ]
    sheet.append(headers)
    for index, record in enumerate(sample, 1):
        raw_text = record["rawText"]
        if len(raw_text) > 32767:
            raise ValueError(f"记录 {record['id']} 的原文超过 Excel 单元格上限")
        sheet.append([
            index, record["id"], record["roleCode"], record["sourceName"], record["company"], record["city"],
            record["collectedTitle"], record["sourceUrl"], raw_text, "未标注", "", "", "", "", "", "", "", "",
            "", "", "待复核", "", "", record["contentHash"],
        ])

    header_fill = PatternFill("solid", fgColor="1F4E78")
    for cell in sheet[1]:
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = header_fill
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
    for row in sheet.iter_rows(min_row=2):
        for cell in row:
            cell.alignment = Alignment(vertical="top", wrap_text=True)
        row[8].font = Font(name="Microsoft YaHei", size=10)
    widths = [10, 13, 25, 24, 24, 14, 34, 42, 90, 12, 25, 18, 22, 20, 40, 65, 45, 35, 14, 14, 14, 14, 14, 68]
    for index, width in enumerate(widths, 1):
        sheet.column_dimensions[chr(64 + index) if index <= 26 else "A"].width = width
    for row in range(2, len(sample) + 2):
        sheet.row_dimensions[row].height = 150
    sheet.row_dimensions[1].height = 42
    sheet.freeze_panes = "J2"
    sheet.auto_filter.ref = f"A1:X{len(sample) + 1}"
    sheet.sheet_view.showGridLines = False

    status = DataValidation(type="list", formula1='"未标注,标注中,已完成,已复核"', allow_blank=False)
    review = DataValidation(type="list", formula1='"待复核,通过,需修改"', allow_blank=False)
    sheet.add_data_validation(status)
    sheet.add_data_validation(review)
    status.add(f"J2:J{len(sample) + 1}")
    review.add(f"U2:U{len(sample) + 1}")
    incomplete_fill = PatternFill("solid", fgColor="FFF2CC")
    sheet.conditional_formatting.add(
        f"A2:X{len(sample) + 1}", FormulaRule(formula=["$J2<>\"已复核\""], fill=incomplete_fill)
    )


def add_statistics(workbook: Workbook, population: list[dict], sample: list[dict]) -> None:
    sheet = workbook.create_sheet("抽样统计")
    sheet.append(["维度", "类别", "数据库总数", "抽样数", "抽样比例"])
    for dimension, field in (("目标岗位", "roleCode"), ("来源平台", "sourceName")):
        population_counts = Counter(item[field] for item in population)
        sample_counts = Counter(item[field] for item in sample)
        for category in sorted(population_counts):
            total = population_counts[category]
            selected = sample_counts[category]
            sheet.append([dimension, category, total, selected, selected / total])
    for cell in sheet[1]:
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor="1F4E78")
    for cell in sheet["E"][1:]:
        cell.number_format = "0.0%"
    sheet.column_dimensions["A"].width = 15
    sheet.column_dimensions["B"].width = 38
    sheet.column_dimensions["C"].width = 14
    sheet.column_dimensions["D"].width = 12
    sheet.column_dimensions["E"].width = 14
    sheet.freeze_panes = "A2"
    sheet.auto_filter.ref = sheet.dimensions


def validate_workbook(path: Path, expected_ids: set[int]) -> None:
    workbook = load_workbook(path, read_only=False, data_only=False)
    if workbook.sheetnames != ["标注说明", "JD人工标注", "抽样统计"]:
        raise AssertionError("工作表结构不正确")
    sheet = workbook["JD人工标注"]
    actual_ids = {sheet.cell(row, 2).value for row in range(2, sheet.max_row + 1)}
    if sheet.max_row != SAMPLE_SIZE + 1 or actual_ids != expected_ids:
        raise AssertionError("Excel 样本数量或记录ID校验失败")
    if any(sheet.cell(row, 9).value in (None, "") for row in range(2, sheet.max_row + 1)):
        raise AssertionError("存在空JD原文")
    if any(sheet.cell(row, 15).value not in (None, "") for row in range(2, sheet.max_row + 1)):
        raise AssertionError("人工技能列不应预填")


def main() -> None:
    environment = load_environment()
    population = fetch_records(environment)
    if len(population) != 317:
        raise AssertionError(f"数据库JD数量不是317，实际为{len(population)}")
    if len({item["contentHash"] for item in population}) != len(population):
        raise AssertionError("数据库存在重复正文哈希")
    sample = stratified_sample(population)
    if len(sample) != SAMPLE_SIZE or len({item["id"] for item in sample}) != SAMPLE_SIZE:
        raise AssertionError("分层抽样结果不是100条唯一记录")
    if {item["roleCode"] for item in sample} != {item["roleCode"] for item in population}:
        raise AssertionError("抽样未覆盖全部目标岗位类别")
    if {item["sourceCode"] for item in sample} != {item["sourceCode"] for item in population}:
        raise AssertionError("抽样未覆盖全部来源平台")

    workbook = Workbook()
    add_instructions(workbook, population, sample)
    add_annotation_sheet(workbook, sample)
    add_statistics(workbook, population, sample)
    workbook.properties.title = "智岗 JD 人工标注样本（100条）"
    workbook.properties.subject = "JD解析准确率独立人工金标"
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    workbook.save(OUTPUT_PATH)
    validate_workbook(OUTPUT_PATH, {item["id"] for item in sample})
    print(json.dumps({
        "output": str(OUTPUT_PATH),
        "databaseCount": len(population),
        "sampleCount": len(sample),
        "uniqueIds": len({item["id"] for item in sample}),
        "uniqueHashes": len({item["contentHash"] for item in sample}),
        "roles": dict(sorted(Counter(item["roleCode"] for item in sample).items())),
        "sources": dict(sorted(Counter(item["sourceName"] for item in sample).items())),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

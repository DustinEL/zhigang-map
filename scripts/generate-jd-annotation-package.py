from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.worksheet.datavalidation import DataValidation


HEADER_FILL = PatternFill("solid", fgColor="1F4E78")
SECTION_FILL = PatternFill("solid", fgColor="D9EAF7")
HEADER_FONT = Font(color="FFFFFF", bold=True)
THIN_BORDER = Border(
    left=Side(style="thin", color="D9E2F3"),
    right=Side(style="thin", color="D9E2F3"),
    top=Side(style="thin", color="D9E2F3"),
    bottom=Side(style="thin", color="D9E2F3"),
)
STATUS_VALUES = "NOT_STARTED,IN_PROGRESS,REVIEWED,UNCERTAIN"


def read_jsonl(path: Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def style_table(worksheet, widths: dict[str, int] | None = None) -> None:
    for cell in worksheet[1]:
        cell.fill = HEADER_FILL
        cell.font = HEADER_FONT
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = THIN_BORDER
    for row in worksheet.iter_rows(min_row=2):
        for cell in row:
            cell.alignment = Alignment(vertical="top", wrap_text=True)
            cell.border = THIN_BORDER
    worksheet.freeze_panes = "A2"
    worksheet.auto_filter.ref = worksheet.dimensions
    worksheet.row_dimensions[1].height = 32
    for column, width in (widths or {}).items():
        worksheet.column_dimensions[column].width = width


def add_title(worksheet, title: str, subtitle: str) -> None:
    worksheet["A1"] = title
    worksheet["A1"].font = Font(size=16, bold=True, color="1F4E78")
    worksheet["A2"] = subtitle
    worksheet["A2"].alignment = Alignment(wrap_text=True, vertical="top")
    worksheet.merge_cells("A1:F1")
    worksheet.merge_cells("A2:F3")
    worksheet.row_dimensions[2].height = 44


def create_annotation_workbook(records: list[dict], role: str, destination: Path) -> None:
    workbook = Workbook()
    instructions = workbook.active
    instructions.title = "说明与签字"
    add_title(instructions, f"JD 盲测独立标注表 — 标注员 {role}", "本文件不包含系统预测。请独立完成，不得查看另一标注员结果或与其讨论具体样本。")
    instruction_rows = [
        ("标注员角色", f"标注员 {role}"),
        ("允许查看", "本表中的 JD 原文、来源元数据和空白标注字段"),
        ("禁止事项", "查看系统预测、另一标注员结果、裁决结果或开发讨论"),
        ("技能格式", "每行：原词 | 标准词 | REQUIRED/BONUS/MENTIONED/NEGATED | 证据位置"),
        ("职责格式", "每行一条职责，必须摘录原文，不得概括、改写或补充"),
        ("不确定项", "无法确定时填写原因，并将标注状态设为 UNCERTAIN"),
        ("标注员姓名或编号", None),
        ("开始时间", None),
        ("完成时间", None),
        ("电子签名", None),
        ("独立性确认", None),
    ]
    for row_index, (label, value) in enumerate(instruction_rows, start=5):
        instructions.cell(row=row_index, column=1, value=label)
        instructions.cell(row=row_index, column=2, value=value)
        instructions.cell(row=row_index, column=1).fill = SECTION_FILL
        instructions.cell(row=row_index, column=1).font = Font(bold=True)
        instructions.cell(row=row_index, column=1).border = THIN_BORDER
        instructions.cell(row=row_index, column=2).border = THIN_BORDER
        instructions.cell(row=row_index, column=2).alignment = Alignment(wrap_text=True, vertical="top")
    instructions.column_dimensions["A"].width = 22
    instructions.column_dimensions["B"].width = 85

    worksheet = workbook.create_sheet("JD标注")
    headers = [
        "recordId", "candidateRecordId", "企业", "岗位名称", "城市", "职级",
        "主岗位方向", "次级岗位方向", "原始链接", "正文SHA-256", "JD原文",
        "标准岗位名称", "技能标注", "核心职责标注", "不确定项", "标注状态",
        "标注员姓名或编号", "开始时间", "完成时间", "备注",
    ]
    worksheet.append(headers)
    for record in records:
        worksheet.append([
            record.get("recordId"),
            record.get("candidateRecordId"),
            record.get("companyName"),
            record.get("jobTitle"),
            record.get("city"),
            record.get("level"),
            record.get("primaryTargetRoleCode"),
            "\n".join(record.get("secondaryTargetRoleCodes") or []),
            record.get("sourceUrl"),
            record.get("contentSha256"),
            record.get("rawText"),
            None, None, None, None, None, None, None, None, None,
        ])
    style_table(worksheet, {
        "A": 20, "B": 28, "C": 28, "D": 30, "E": 18, "F": 16,
        "G": 26, "H": 28, "I": 44, "J": 68, "K": 95,
        "L": 28, "M": 62, "N": 62, "O": 36, "P": 18,
        "Q": 24, "R": 22, "S": 22, "T": 36,
    })
    for row_index in range(2, len(records) + 2):
        worksheet.row_dimensions[row_index].height = 96
    status_validation = DataValidation(type="list", formula1=f'"{STATUS_VALUES}"', allow_blank=True)
    status_validation.error = "请选择规定的标注状态"
    status_validation.errorTitle = "无效状态"
    worksheet.add_data_validation(status_validation)
    status_validation.add(f"P2:P{len(records) + 1}")
    workbook.properties.title = f"JD 盲测标注员 {role} 独立标注表"
    workbook.properties.subject = "不含预测的独立人工标注模板"
    workbook.save(destination)


def create_custodian_workbook(records: list[dict], data_path: Path, manifest_path: Path, destination: Path) -> None:
    workbook = Workbook()
    signoff = workbook.active
    signoff.title = "签字与确认"
    add_title(signoff, "JD 盲测数据保管员签字表", "由数据保管员在冻结、发放和回收各阶段填写。不得由开发人员代签。")
    confirmations = [
        "正式数据目录已与开发工作区隔离",
        "240 条正文与 manifest 已冻结并核对 SHA-256",
        "A/B 标注表均不包含系统预测",
        "A/B 未互看结果，C 未查看系统预测",
        "冻结后正文未发生未记录变更",
    ]
    signoff.append([])
    signoff.cell(row=5, column=1, value="确认事项")
    signoff.cell(row=5, column=2, value="确认（是/否）")
    signoff.cell(row=5, column=3, value="证据或备注")
    for index, confirmation in enumerate(confirmations, start=6):
        signoff.cell(row=index, column=1, value=confirmation)
    signature_start = 12
    for offset, label in enumerate(("数据保管员姓名或编号", "冻结时间", "电子签名", "签字时间")):
        signoff.cell(row=signature_start + offset, column=1, value=label)
    style_table(signoff, {"A": 58, "B": 22, "C": 70})
    yes_no = DataValidation(type="list", formula1='"是,否"', allow_blank=True)
    signoff.add_data_validation(yes_no)
    yes_no.add("B6:B10")

    files = workbook.create_sheet("文件清单")
    files.append(["文件", "SHA-256", "用途", "冻结状态", "保管员复核"])
    files.append([data_path.name, file_sha256(data_path), "240 条 JD 原文", "已生成", None])
    files.append([manifest_path.name, file_sha256(manifest_path), "逐条来源、分层与正文哈希", "已生成", None])
    style_table(files, {"A": 38, "B": 68, "C": 46, "D": 18, "E": 20})

    samples = workbook.create_sheet("样本清单")
    samples.append(["recordId", "candidateRecordId", "企业", "岗位名称", "主岗位方向", "正文SHA-256"])
    for record in records:
        samples.append([
            record.get("recordId"), record.get("candidateRecordId"), record.get("companyName"),
            record.get("jobTitle"), record.get("primaryTargetRoleCode"), record.get("contentSha256"),
        ])
    style_table(samples, {"A": 22, "B": 30, "C": 32, "D": 34, "E": 28, "F": 68})
    workbook.save(destination)


def create_adjudication_workbook(records: list[dict], destination: Path) -> None:
    workbook = Workbook()
    instructions = workbook.active
    instructions.title = "说明与签字"
    add_title(instructions, "JD 盲测分歧裁决表", "由裁决员 C 使用。仅在 A/B 独立标注完成后导入分歧，不得查看系统预测。")
    for row_index, (label, value) in enumerate([
        ("裁决员姓名或编号", None), ("开始时间", None), ("完成时间", None),
        ("电子签名", None), ("未查看预测确认", None),
    ], start=5):
        instructions.cell(row=row_index, column=1, value=label)
        instructions.cell(row=row_index, column=2, value=value)
        instructions.cell(row=row_index, column=1).fill = SECTION_FILL
        instructions.cell(row=row_index, column=1).font = Font(bold=True)
    instructions.column_dimensions["A"].width = 25
    instructions.column_dimensions["B"].width = 80

    worksheet = workbook.create_sheet("裁决")
    headers = [
        "recordId", "企业", "岗位名称", "A标准岗位", "B标准岗位", "A技能标注", "B技能标注",
        "A职责标注", "B职责标注", "分歧类型", "裁决标准岗位", "裁决技能标注", "裁决职责标注",
        "裁决证据位置", "裁决理由", "裁决状态", "裁决员姓名或编号", "完成时间",
    ]
    worksheet.append(headers)
    for record in records:
        worksheet.append([record.get("recordId"), record.get("companyName"), record.get("jobTitle")] + [None] * 15)
    style_table(worksheet, {"A": 22, "B": 28, "C": 30, "D": 26, "E": 26, "F": 55, "G": 55, "H": 55, "I": 55, "J": 28, "K": 28, "L": 55, "M": 55, "N": 36, "O": 46, "P": 20, "Q": 24, "R": 22})
    status_validation = DataValidation(type="list", formula1='"PENDING,ADJUDICATED,UNCERTAIN"', allow_blank=True)
    worksheet.add_data_validation(status_validation)
    status_validation.add(f"P2:P{len(records) + 1}")
    workbook.save(destination)


def create_evidence_workbook(records: list[dict], destination: Path) -> None:
    workbook = Workbook()
    people = workbook.active
    people.title = "人员与签字"
    people.append(["角色", "姓名或编号", "开始时间", "完成时间", "完成样本数", "独立性确认", "未查看预测确认", "电子签名", "签字时间"])
    for role in ("数据保管员", "标注员 A", "标注员 B", "裁决员 C"):
        people.append([role] + [None] * 8)
    style_table(people, {"A": 20, "B": 24, "C": 22, "D": 22, "E": 18, "F": 24, "G": 24, "H": 30, "I": 22})

    files = workbook.create_sheet("文件证据")
    files.append(["文件", "角色", "记录数", "SHA-256", "回收时间", "保管员确认", "备注"])
    for filename, role in (
        ("annotator-a.xlsx", "标注员 A"),
        ("annotator-b.xlsx", "标注员 B"),
        ("adjudication.xlsx", "裁决员 C"),
        ("gold-frozen.xlsx", "数据保管员"),
    ):
        files.append([filename, role, len(records) if role != "裁决员 C" else None, None, None, None, None])
    style_table(files, {"A": 34, "B": 20, "C": 16, "D": 68, "E": 22, "F": 22, "G": 44})

    declarations = workbook.create_sheet("声明")
    declarations.append(["声明", "确认（是/否）", "签字人", "时间", "备注"])
    for text in (
        "A、B、C 为三个不同真人",
        "A、B 在独立阶段未互看标注结果",
        "A、B、C 均未查看系统预测",
        "所有分歧均由 C 逐条裁决",
        "最终金标未根据系统输出修改",
    ):
        declarations.append([text, None, None, None, None])
    style_table(declarations, {"A": 62, "B": 22, "C": 22, "D": 22, "E": 50})
    yes_no = DataValidation(type="list", formula1='"是,否"', allow_blank=True)
    declarations.add_data_validation(yes_no)
    yes_no.add("B2:B6")
    workbook.save(destination)


def write_guide(output_dir: Path) -> Path:
    path = output_dir / "标注与签字操作说明.md"
    path.write_text(
        """# JD 240 条标注与签字操作说明

## 谁在哪里签字

1. 数据保管员：打开 `custodian-signoff.xlsx`，在“签字与确认”填写确认项、姓名或编号、冻结时间、电子签名和签字时间。
2. 标注员 A：打开 `annotator-a.xlsx`，先在“说明与签字”填写身份和时间，完成“JD标注”后填写电子签名。
3. 标注员 B：打开 `annotator-b.xlsx`，操作同 A；A、B 不能互换或共用文件。
4. 裁决员 C：A/B 完成后打开 `adjudication.xlsx`，只处理分歧项，并在“说明与签字”签字。
5. 数据保管员：最后打开 `annotation-evidence.xlsx`，登记四个角色、回收文件哈希、独立性声明和签字。

## 怎么标注

- “标准岗位名称”：填写统一后的岗位名称。
- “技能标注”：每行填写 `原词 | 标准词 | REQUIRED/BONUS/MENTIONED/NEGATED | 证据位置`。
- “核心职责标注”：每行摘录一条原文职责，不得概括、改写或补写。
- “不确定项”：写明无法确定的字段和原因，并将状态设为 `UNCERTAIN`。
- 全部完成并自查后，将状态设为 `REVIEWED`。

## 文件流转顺序

保管员签字冻结 → 分别发 A/B → A/B 独立标注并回收 → 计算分歧 → C 裁决 → 登记证据与哈希 → 冻结金标。

## 电子签名约定

电子签名应使用组织认可的姓名或账号，并同时填写签字时间。若要求手写签名，可打印“说明与签字”页签字后扫描，扫描件与原工作簿一起由保管员保存。
""",
        encoding="utf-8",
    )
    return path


def generate_package(data_path: Path, manifest_path: Path, output_dir: Path) -> dict:
    records = read_jsonl(data_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest_ids = [record.get("recordId") for record in manifest.get("records", [])]
    record_ids = [record.get("recordId") for record in records]
    if manifest.get("recordCount") != len(records) or manifest_ids != record_ids:
        raise ValueError("manifest 与 JD 数据记录不一致")
    if len(record_ids) != len(set(record_ids)):
        raise ValueError("JD recordId 不唯一")

    output_dir.mkdir(parents=True, exist_ok=True)
    files = {
        "custodian-signoff.xlsx": lambda path: create_custodian_workbook(records, data_path, manifest_path, path),
        "annotator-a.xlsx": lambda path: create_annotation_workbook(records, "A", path),
        "annotator-b.xlsx": lambda path: create_annotation_workbook(records, "B", path),
        "adjudication.xlsx": lambda path: create_adjudication_workbook(records, path),
        "annotation-evidence.xlsx": lambda path: create_evidence_workbook(records, path),
    }
    for filename, create in files.items():
        create(output_dir / filename)
    guide = write_guide(output_dir)

    checksum_targets = [output_dir / filename for filename in files] + [guide]
    checksum_path = output_dir / "package-checksums.sha256"
    checksum_path.write_text(
        "".join(f"{file_sha256(path)}  {path.name}\n" for path in checksum_targets),
        encoding="utf-8",
    )
    return {
        "recordCount": len(records),
        "outputDirectory": str(output_dir),
        "files": sorted([*files, guide.name, checksum_path.name]),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 JD 盲测标注与签字工作包")
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(generate_package(args.data, args.manifest, args.output), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

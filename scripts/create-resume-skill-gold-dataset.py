from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile

from openpyxl import Workbook
from openpyxl.formatting.rule import FormulaRule
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.datavalidation import DataValidation


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESUME_DIR = PROJECT_ROOT / "测试简历"
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "test-data" / "resume-skill-gold"
CURATED_NAME = re.compile(r"^(\d{2})_")
WORD_TEXT = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t"


@dataclass(frozen=True)
class SampleFiles:
    sample_id: str
    directory: Path
    docx: Path
    pdf: Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def select_sample_files(sample_dir: Path) -> SampleFiles:
    match = CURATED_NAME.match(sample_dir.name)
    if not match:
        raise ValueError(f"样本目录必须以两位编号开头: {sample_dir.name}")
    sample_id = match.group(1)

    def candidates(extension: str) -> list[Path]:
        return sorted(
            path for path in sample_dir.iterdir()
            if path.is_file()
            and path.suffix.lower() == extension
            and path.name.startswith(f"{sample_id}_")
        )

    docx_files = candidates(".docx")
    pdf_files = candidates(".pdf")
    if len(docx_files) != 1:
        raise ValueError(f"样本 {sample_dir.name} 的整理版 DOCX 数量应为 1，实际为 {len(docx_files)}")
    if len(pdf_files) != 1:
        raise ValueError(f"样本 {sample_dir.name} 的整理版 PDF 数量应为 1，实际为 {len(pdf_files)}")
    return SampleFiles(sample_id, sample_dir, docx_files[0], pdf_files[0])


def extract_docx_text(path: Path) -> str:
    with ZipFile(path) as archive:
        document = ElementTree.fromstring(archive.read("word/document.xml"))
    parts = [node.text.strip() for node in document.iter(WORD_TEXT) if node.text and node.text.strip()]
    return "\n".join(parts)


def build_manifest_records(resume_dir: Path) -> list[dict]:
    records = []
    directories = sorted((path for path in resume_dir.iterdir() if path.is_dir()), key=lambda path: path.name)
    for directory in directories:
        sample = select_sample_files(directory)
        records.append({
            "sampleId": sample.sample_id,
            "sampleName": directory.name,
            "docx": {
                "path": sample.docx.relative_to(resume_dir.parent).as_posix(),
                "sha256": sha256_file(sample.docx),
                "bytes": sample.docx.stat().st_size,
            },
            "pdf": {
                "path": sample.pdf.relative_to(resume_dir.parent).as_posix(),
                "sha256": sha256_file(sample.pdf),
                "bytes": sample.pdf.stat().st_size,
            },
        })
    return records


def dataset_hash(records: list[dict]) -> str:
    payload = json.dumps(records, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def annotation_rows(
    records: list[dict],
    include_text: bool = True,
    annotations: dict[str, dict] | None = None,
) -> tuple[list[str], list[list[str]]]:
    headers = [
        "样本编号", "样本名称", "DOCX文件", "DOCX SHA-256", "DOCX正文",
        "标注状态", "人工标准技能（用|分隔）", "人工原词与标准词映射",
        "技能证据位置", "不确定项", "标注备注", "标注人", "标注日期",
        "复核结论", "复核人", "复核日期",
    ]
    rows = []
    annotations = annotations or {}
    for record in records:
        docx_path = PROJECT_ROOT / record["docx"]["path"]
        text = extract_docx_text(docx_path) if include_text and docx_path.exists() else ""
        annotation = annotations.get(record["sampleId"])
        if annotation:
            status = "已完成"
            skills = "|".join(annotation["skills"])
            mappings = "|".join(annotation.get("mappings", []))
            evidence = annotation.get("evidence", "")
            uncertain = annotation.get("uncertain", "")
            notes = annotation.get("notes", "")
            annotator = "Codex初标"
            annotation_date = "2026-08-14"
        else:
            status = "未标注"
            skills = mappings = evidence = uncertain = notes = annotator = annotation_date = ""
        rows.append([
            record["sampleId"], record["sampleName"], record["docx"]["path"], record["docx"]["sha256"], text,
            status, skills, mappings, evidence, uncertain, notes, annotator, annotation_date, "待复核", "", "",
        ])
    return headers, rows


def write_manifest(output_dir: Path, records: list[dict]) -> None:
    manifest = {
        "version": "resume-skill-gold-v1",
        "purpose": "30份整理版简历的技能人工黄金标注",
        "goldStatus": "NOT_FROZEN",
        "sampleCount": len(records),
        "datasetSha256": dataset_hash(records),
        "selectionRule": "每个目录唯一一个以样本编号开头的整理版 DOCX 和 PDF",
        "samples": records,
    }
    (output_dir / "resume-skill-30-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def write_annotation_csv(output_dir: Path, records: list[dict], annotations: dict[str, dict] | None = None) -> None:
    headers, rows = annotation_rows(records, annotations=annotations)
    with (output_dir / "resume-skill-30-annotation.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(headers)
        writer.writerows(rows)


def write_annotation_workbook(
    path: Path,
    records: list[dict],
    include_text: bool = True,
    annotations: dict[str, dict] | None = None,
) -> None:
    workbook = Workbook()
    instructions = workbook.active
    instructions.title = "标注说明"
    instruction_rows = [
        ("30份简历技能黄金标注", "title"),
        ("独立性", "只依据DOCX正文人工标注；工作簿不包含程序预测结果。"),
        ("样本口径", "DOCX计入准确率；PDF只做解析一致性验证；原始附件不参与评测。"),
        ("技能格式", "标准技能用英文竖线 | 分隔；映射写成 原词=>标准词。"),
        ("判断边界", "不根据岗位名、学校或常识推断；不确定内容写入不确定项。"),
        ("完成条件", "第一人标注、第二人复核；只有已复核且复核通过的样本才能冻结。"),
        ("样本数量", str(len(records))),
        ("数据集SHA-256", dataset_hash(records)),
    ]
    for row_index, (label, value) in enumerate(instruction_rows, 1):
        instructions.cell(row_index, 1, label)
        instructions.cell(row_index, 2, value)
    instructions["A1"].font = Font(size=16, bold=True, color="FFFFFF")
    instructions["A1"].fill = PatternFill("solid", fgColor="1F4E78")
    instructions["B1"].fill = PatternFill("solid", fgColor="1F4E78")
    for row in range(2, len(instruction_rows) + 1):
        instructions.cell(row, 1).font = Font(bold=True, color="1F4E78")
        instructions.cell(row, 1).fill = PatternFill("solid", fgColor="D9EAF7")
        instructions.cell(row, 2).alignment = Alignment(wrap_text=True, vertical="top")
    instructions.column_dimensions["A"].width = 20
    instructions.column_dimensions["B"].width = 105

    sheet = workbook.create_sheet("技能人工标注")
    headers, rows = annotation_rows(records, include_text=include_text, annotations=annotations)
    sheet.append(headers)
    for row in rows:
        sheet.append(row)
    for cell in sheet[1]:
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor="1F4E78")
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
    for row in sheet.iter_rows(min_row=2):
        for cell in row:
            cell.alignment = Alignment(vertical="top", wrap_text=True)
    widths = [10, 34, 55, 68, 90, 12, 48, 55, 55, 35, 35, 14, 14, 14, 14, 14]
    for index, width in enumerate(widths, 1):
        sheet.column_dimensions[chr(64 + index)].width = width
    for row_number in range(2, len(rows) + 2):
        sheet.row_dimensions[row_number].height = 150
    sheet.freeze_panes = "F2"
    sheet.auto_filter.ref = f"A1:P{len(rows) + 1}"
    sheet.sheet_view.showGridLines = False

    status = DataValidation(type="list", formula1='"未标注,标注中,已完成,已复核"', allow_blank=False)
    review = DataValidation(type="list", formula1='"待复核,通过,需修改"', allow_blank=False)
    sheet.add_data_validation(status)
    sheet.add_data_validation(review)
    status.add(f"F2:F{len(rows) + 1}")
    review.add(f"N2:N{len(rows) + 1}")
    sheet.conditional_formatting.add(
        f"A2:P{len(rows) + 1}",
        FormulaRule(formula=['OR($F2<>"已复核",$N2<>"通过")'], fill=PatternFill("solid", fgColor="FFF2CC")),
    )
    workbook.save(path)


def write_guidelines(output_dir: Path) -> None:
    content = """# 30 份简历技能黄金标注说明

## 文件范围

- 每个样本只使用编号开头的整理版 DOCX 计入准确率。
- 同编号整理版 PDF 只验证解析一致性，不重复计入准确率。
- 原始附件、重复版本、网页和源码压缩包不进入评测，也不删除。
- 清单中的 SHA-256 变化时必须重新审核对应样本。

## 标注原则

- 只依据 DOCX 正文明确出现的技术技能，不依据岗位名、学校或常识猜测。
- 程序当前输出不能作为黄金答案；标注表故意不包含预测结果。
- 标准技能用 `|` 分隔；原词映射写成 `原词=>标准词`，多个映射也用 `|` 分隔。
- 无法确定是否属于技能时写入“不确定项”，交由复核者处理。
- 只有“标注状态=已复核”且“复核结论=通过”的样本才能冻结为金标。

## 标准名与别名口径

- `Vue.js`、`Vue 2`、`Vue 3` 统一为 `Vue`；版本信息不作为独立技能计数。
- `Nuxt.js`、`Nuxt 3`、`Nuxt 4` 统一为 `Nuxt`。
- `Node.js` 统一为 `Node.js`，不要简化为含义更宽的 `JavaScript`。
- `Obj-C`、`Objective C` 统一为 `Objective-C`，并与 `C` 分开。
- `C`、`C++`、`C#` 分别计数，禁止从长技能内部推导短技能。
- `AI Agent`、`智能体` 统一为 `AI Agent`；单独出现的 `Agent` 仅在明确指 AI 智能体时归一。
- 产品或框架保持具体名称，例如 `RocketMQ`、`WebRTC`、`FFmpeg` 不折叠为泛化类别。
- 大小写和多余空格不造成新技能，但官方大小写应保留在标准名中。

## 人工流程

1. 第一位标注者逐份阅读正文并填写标准技能、原词映射和证据位置。
2. 第二位复核者核对遗漏、误标和归一化一致性。
3. 对争议项统一裁决后，将状态改为“已复核”、结论改为“通过”。
4. 全部 30 份通过后再生成冻结金标并运行正式评测。
"""
    (output_dir / "resume-skill-30-annotation-guidelines.md").write_text(content, encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成30份简历技能人工黄金标注材料")
    parser.add_argument("--resume-dir", type=Path, default=DEFAULT_RESUME_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--expected-count", type=int, default=30)
    return parser.parse_args()


def load_initial_annotations(output_dir: Path) -> dict[str, dict]:
    path = output_dir / "resume-skill-30-initial-annotations.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return data["annotations"]


def main() -> None:
    args = parse_args()
    records = build_manifest_records(args.resume_dir.resolve())
    if len(records) != args.expected_count:
        raise ValueError(f"样本数量应为 {args.expected_count}，实际为 {len(records)}")
    actual_ids = [record["sampleId"] for record in records]
    expected_ids = [f"{index:02d}" for index in range(1, args.expected_count + 1)]
    if actual_ids != expected_ids:
        raise ValueError(f"样本编号不连续: {actual_ids}")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    annotations = load_initial_annotations(output_dir)
    write_manifest(output_dir, records)
    write_annotation_csv(output_dir, records, annotations)
    write_annotation_workbook(output_dir / "resume-skill-30-annotation.xlsx", records, annotations=annotations)
    write_guidelines(output_dir)
    print(f"已生成 {len(records)} 份样本的标注材料: {output_dir}")
    print(f"数据集 SHA-256: {dataset_hash(records)}")


if __name__ == "__main__":
    main()

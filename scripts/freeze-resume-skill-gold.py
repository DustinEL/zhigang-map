from __future__ import annotations

import argparse
import csv
import json
from datetime import date, datetime
from pathlib import Path

from openpyxl import load_workbook


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DIR = PROJECT_ROOT / "test-data" / "resume-skill-gold"


def propagate_first_review(sheet) -> None:
    reviewer = sheet.cell(2, 15).value
    review_date = sheet.cell(2, 16).value
    if not reviewer or not review_date:
        raise ValueError("第一份样本必须填写复核人和复核日期")
    for row in range(2, sheet.max_row + 1):
        sheet.cell(row, 6, "已复核")
        sheet.cell(row, 14, "通过")
        sheet.cell(row, 15, reviewer)
        sheet.cell(row, 16, review_date)


def iso_date(value) -> str:
    if isinstance(value, (datetime, date)):
        return value.date().isoformat() if isinstance(value, datetime) else value.isoformat()
    return str(value).strip()


def build_gold(sheet, manifest: dict) -> dict:
    manifest_by_id = {sample["sampleId"]: sample for sample in manifest["samples"]}
    samples = []
    for row in range(2, sheet.max_row + 1):
        sample_id = str(sheet.cell(row, 1).value).zfill(2)
        manifest_sample = manifest_by_id.get(sample_id)
        if manifest_sample is None:
            raise ValueError(f"清单中不存在样本 {sample_id}")
        workbook_hash = str(sheet.cell(row, 4).value or "")
        if workbook_hash != manifest_sample["docx"]["sha256"]:
            raise ValueError(f"样本 {sample_id} 的 DOCX SHA-256 与清单不一致")
        status = sheet.cell(row, 6).value
        conclusion = sheet.cell(row, 14).value
        reviewer = sheet.cell(row, 15).value
        review_date = sheet.cell(row, 16).value
        skills = [value.strip() for value in str(sheet.cell(row, 7).value or "").split("|") if value.strip()]
        if status != "已复核" or conclusion != "通过":
            raise ValueError(f"样本 {sample_id} 尚未复核通过")
        if not reviewer or not review_date or not skills:
            raise ValueError(f"样本 {sample_id} 的复核信息或技能为空")
        if len(skills) != len(set(skills)):
            raise ValueError(f"样本 {sample_id} 包含重复技能")
        samples.append({
            "sampleId": sample_id,
            "sampleName": sheet.cell(row, 2).value,
            "docxPath": manifest_sample["docx"]["path"],
            "docxSha256": workbook_hash,
            "pdfPath": manifest_sample["pdf"]["path"],
            "pdfSha256": manifest_sample["pdf"]["sha256"],
            "skills": skills,
            "evidence": sheet.cell(row, 9).value or "",
            "uncertain": sheet.cell(row, 10).value or "",
            "annotator": sheet.cell(row, 12).value or "",
            "annotationDate": iso_date(sheet.cell(row, 13).value),
            "reviewer": str(reviewer),
            "reviewDate": iso_date(review_date),
        })
    if len(samples) != len(manifest["samples"]):
        raise ValueError(f"工作簿样本数 {len(samples)} 与清单样本数 {len(manifest['samples'])} 不一致")
    return {
        "version": "resume-skill-gold-v1",
        "goldStatus": "FROZEN",
        "datasetSha256": manifest["datasetSha256"],
        "sampleCount": len(samples),
        "reviewer": samples[0]["reviewer"],
        "reviewDate": samples[0]["reviewDate"],
        "samples": samples,
    }


def sync_csv(sheet, path: Path) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        for row in sheet.iter_rows(values_only=True):
            writer.writerow([iso_date(value) if isinstance(value, (date, datetime)) else value for value in row])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="传播复核信息并冻结30份简历技能金标")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DIR)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    data_dir = args.data_dir.resolve()
    workbook_path = data_dir / "resume-skill-30-annotation.xlsx"
    manifest_path = data_dir / "resume-skill-30-manifest.json"
    csv_path = data_dir / "resume-skill-30-annotation.csv"
    gold_path = data_dir / "resume-skill-30-gold.json"

    workbook = load_workbook(workbook_path)
    sheet = workbook["技能人工标注"]
    propagate_first_review(sheet)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    gold = build_gold(sheet, manifest)
    workbook.save(workbook_path)
    sync_csv(sheet, csv_path)
    gold_path.write_text(json.dumps(gold, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"已冻结 {gold['sampleCount']} 份简历技能金标: {gold_path}")
    print(f"复核人: {gold['reviewer']}，复核日期: {gold['reviewDate']}")


if __name__ == "__main__":
    main()

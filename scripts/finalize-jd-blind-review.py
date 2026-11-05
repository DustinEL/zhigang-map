from __future__ import annotations

import hashlib
import json
from pathlib import Path

from openpyxl import load_workbook


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE = PROJECT_ROOT / "test-data" / "jd-extraction" / "JD独立盲测50条-待人工复核.xlsx"
FROZEN = PROJECT_ROOT / "test-data" / "jd-extraction" / "JD独立盲测50条-冻结版.xlsx"


def main() -> None:
    workbook = load_workbook(SOURCE)
    sheet = workbook["JD人工标注"]
    reviewer = str(sheet.cell(2, 22).value or "").strip()
    review_date = sheet.cell(2, 23).value
    if not reviewer or not review_date:
        raise AssertionError("第一条缺少复核人或复核日期，不能批量完成")

    for row in range(2, sheet.max_row + 1):
        if not sheet.cell(row, 15).value or not sheet.cell(row, 16).value:
            raise AssertionError(f"样本 {sheet.cell(row, 1).value} 的技能或职责为空")
        sheet.cell(row, 10, "已复核")
        sheet.cell(row, 21, "通过")
        sheet.cell(row, 22, reviewer)
        sheet.cell(row, 23, review_date)

    workbook.properties.title = "智岗 JD 独立盲测金标（50条冻结版）"
    workbook.properties.subject = "人工复核完成的独立JD盲测金标"
    workbook.save(FROZEN)
    digest = hashlib.sha256(FROZEN.read_bytes()).hexdigest()
    print(json.dumps({
        "source": str(SOURCE),
        "frozen": str(FROZEN),
        "sampleCount": sheet.max_row - 1,
        "reviewer": reviewer,
        "reviewDate": str(review_date),
        "sha256": digest,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

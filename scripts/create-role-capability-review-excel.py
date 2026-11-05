"""生成两份彼此独立、全中文的岗位能力卡审核工作簿。"""

import argparse
import hashlib
import json
from pathlib import Path

from openpyxl import Workbook
from openpyxl.formatting.rule import FormulaRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Protection, Side
from openpyxl.worksheet.datavalidation import DataValidation


HEADERS = [
    "序号", "岗位名称", "审核项目", "能力项", "当前内容", "接近可申请最低证据",
    "可申请最低证据", "是否关键阻断", "审核结论", "修改后内容", "修改后接近门槛",
    "修改后可申请门槛", "修改后是否阻断", "审核依据 / 原因", "备注",
]
CAPABILITY_NAMES = {
    "language": "编程语言", "backend": "后端开发", "data": "数据能力", "engineering": "工程化能力",
    "llm": "大语言模型", "agent": "智能体开发", "retrieval": "检索增强", "ai-application": "AI 应用",
    "operations": "部署运维", "systems": "系统基础", "containers": "容器与编排",
    "model-serving": "模型服务", "automation": "自动化", "observability": "可观测性",
    "interaction": "交互与提示词", "query": "查询能力", "pipeline": "数据管道", "storage": "数据存储",
    "testing": "安全测试", "threat": "威胁防护", "ai": "AI 基础", "network": "网络安全",
    "serving": "推理服务", "compute": "GPU 计算", "optimization": "推理优化",
}
LEVEL_TEXT = {
    0: "L0｜无有效证据", 1: "L1｜课程或技能声明", 2: "L2｜项目中实际使用",
    3: "L3｜有结果或量化产出", 4: "L4｜实习 / 生产实践",
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rows_from_cards(cards):
    rows = []
    for card in cards:
        for capability in card.get("coreCapabilities", []):
            rows.append({
                "role": card, "itemType": "CORE", "capabilityId": capability["id"],
                "itemName": CAPABILITY_NAMES.get(capability["id"], capability["id"]),
                "values": capability.get("alternatives", []),
                "near": capability["nearEvidenceLevel"], "ready": capability["readyEvidenceLevel"],
                "blocking": capability["blocking"],
            })
        rows.append({
            "role": card, "itemType": "TRANSFERABLE", "capabilityId": "transferableSkills",
            "itemName": "可迁移技能", "values": card.get("transferableSkills", []),
            "near": None, "ready": None, "blocking": None,
        })
        rows.append({
            "role": card, "itemType": "BONUS", "capabilityId": "bonusSkills",
            "itemName": "加分技能", "values": card.get("bonusSkills", []),
            "near": None, "ready": None, "blocking": None,
        })
    return rows


def build_workbook(catalog, reviewer_label, source_hash):
    workbook = Workbook()
    instruction = workbook.active
    instruction.title = "填写说明"
    review = workbook.create_sheet("审核表")
    metadata = workbook.create_sheet("_程序数据")

    navy = "20334A"
    blue = "2E6F95"
    pale_blue = "EAF3F8"
    pale_green = "E8F4EC"
    pale_red = "FBECEC"
    pale_amber = "FFF4D6"
    border = Border(bottom=Side(style="thin", color="D7E0E7"))

    instruction["A1"] = "岗位能力卡人工独立审核"
    instruction["A1"].font = Font(name="Microsoft YaHei", size=20, bold=True, color=navy)
    instruction["A3"] = "你只需要做三件事"
    instruction["A3"].font = Font(name="Microsoft YaHei", size=13, bold=True, color=blue)
    instructions = [
        "1. 在“审核表”顶部填写姓名和日期。",
        "2. 每一行从下拉框选择“通过 / 修改 / 驳回”。",
        "3. 选择“修改”或“驳回”时，填写建议内容和原因。",
        "两名审核人必须使用不同文件，审核期间不要互看结果。",
        "证据等级：L1=课程或声明；L2=项目实践；L3=有结果；L4=实习或生产实践。",
    ]
    for index, value in enumerate(instructions, 5):
        instruction.cell(index, 1, value)
        instruction.cell(index, 1).font = Font(name="Microsoft YaHei", size=11, color="34495E")
    instruction["A12"] = f"文件用途：{reviewer_label}"
    instruction["A13"] = f"能力卡源文件 SHA-256：{source_hash}"
    instruction.column_dimensions["A"].width = 95
    instruction.sheet_view.showGridLines = False

    review.merge_cells("A1:O1")
    review["A1"] = f"岗位能力卡独立审核表 · {reviewer_label}"
    review["A1"].font = Font(name="Microsoft YaHei", size=18, bold=True, color="FFFFFF")
    review["A1"].fill = PatternFill("solid", fgColor=navy)
    review["A1"].alignment = Alignment(horizontal="left", vertical="center")
    review.row_dimensions[1].height = 38
    review["A2"] = "审核人姓名"
    review["D2"] = "审核日期"
    review["G2"] = "说明"
    review["H2"] = "请独立判断，不要查看另一名审核人的文件"
    for cell in (review["A2"], review["D2"], review["G2"]):
        cell.font = Font(name="Microsoft YaHei", bold=True, color=navy)
    for cell in (review["B2"], review["E2"]):
        cell.fill = PatternFill("solid", fgColor="FFFBEA")
        cell.border = Border(bottom=Side(style="medium", color="D6A84B"))
    review["H2"].font = Font(name="Microsoft YaHei", color="7A5B13")
    review.merge_cells("H2:O2")
    review["A4"] = "填写进度"
    review["B4"] = '=COUNTIF(I7:I200,"通过")+COUNTIF(I7:I200,"修改")+COUNTIF(I7:I200,"驳回")'
    review["C4"] = " / "

    for column, header in enumerate(HEADERS, 1):
        cell = review.cell(6, column, header)
        cell.font = Font(name="Microsoft YaHei", bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor=blue)
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
    review.row_dimensions[6].height = 38

    program_headers = [
        "excelRow", "roleId", "roleCode", "roleName", "cardVersion", "itemType", "capabilityId",
        "currentValues", "nearEvidenceLevel", "readyEvidenceLevel", "blocking", "sourceReferences",
    ]
    metadata.append(program_headers)
    data_rows = rows_from_cards(catalog["cards"])
    last_role_id = None
    for index, item in enumerate(data_rows, 1):
        row = index + 6
        role = item["role"]
        values = "、".join(item["values"])
        visible = [
            index, role["roleName"], "核心能力" if item["itemType"] == "CORE" else item["itemName"],
            item["itemName"], values,
            LEVEL_TEXT.get(item["near"], "不适用"), LEVEL_TEXT.get(item["ready"], "不适用"),
            "是" if item["blocking"] is True else "否" if item["blocking"] is False else "不适用",
            None, None, None, None, None, None, None,
        ]
        for column, value in enumerate(visible, 1):
            cell = review.cell(row, column, value)
            cell.font = Font(name="Microsoft YaHei", size=10, color="263746")
            cell.alignment = Alignment(vertical="top", wrap_text=True)
            cell.border = border
        if role["roleId"] != last_role_id:
            for column in range(1, 16):
                review.cell(row, column).border = Border(top=Side(style="medium", color=blue),
                                                         bottom=Side(style="thin", color="D7E0E7"))
            last_role_id = role["roleId"]
        if item["itemType"] != "CORE":
            for column in range(1, 16):
                review.cell(row, column).fill = PatternFill("solid", fgColor=pale_blue)
        for column in range(9, 16):
            review.cell(row, column).fill = PatternFill("solid", fgColor="FFFDF5")
        metadata.append([
            row, role["roleId"], role["roleCode"], role["roleName"], role["version"], item["itemType"],
            item["capabilityId"], " | ".join(item["values"]), item["near"] if item["near"] is not None else "",
            item["ready"] if item["ready"] is not None else "", str(item["blocking"]).lower() if item["blocking"] is not None else "",
            " | ".join(role.get("sourceReferences", [])),
        ])

    end_row = len(data_rows) + 6
    review["D4"] = len(data_rows)
    decision_validation = DataValidation(type="list", formula1='"通过,修改,驳回"', allow_blank=True)
    level_validation = DataValidation(type="list", formula1='"L0,L1,L2,L3,L4"', allow_blank=True)
    boolean_validation = DataValidation(type="list", formula1='"是,否"', allow_blank=True)
    review.add_data_validation(decision_validation)
    review.add_data_validation(level_validation)
    review.add_data_validation(boolean_validation)
    decision_validation.add(f"I7:I{end_row}")
    level_validation.add(f"K7:L{end_row}")
    boolean_validation.add(f"M7:M{end_row}")
    review.conditional_formatting.add(f"A7:O{end_row}", FormulaRule(formula=['$I7="通过"'], fill=PatternFill("solid", fgColor=pale_green)))
    review.conditional_formatting.add(f"A7:O{end_row}", FormulaRule(formula=['$I7="修改"'], fill=PatternFill("solid", fgColor=pale_amber)))
    review.conditional_formatting.add(f"A7:O{end_row}", FormulaRule(formula=['$I7="驳回"'], fill=PatternFill("solid", fgColor=pale_red)))
    review.freeze_panes = "A7"
    review.auto_filter.ref = f"A6:O{end_row}"
    review.sheet_view.showGridLines = False
    review.row_dimensions.group(3, 5, hidden=True)
    widths = [8, 24, 14, 18, 36, 20, 20, 14, 14, 34, 18, 18, 18, 36, 24]
    for index, width in enumerate(widths, 1):
        review.column_dimensions[chr(64 + index)].width = width
    review.print_title_rows = "1:6"
    review.sheet_properties.pageSetUpPr.fitToPage = True
    review.page_setup.fitToWidth = 1
    review.page_setup.fitToHeight = 0

    metadata.sheet_state = "hidden"
    metadata["N1"] = "sourceSha256"
    metadata["N2"] = source_hash
    metadata["O1"] = "reviewerLabel"
    metadata["O2"] = reviewer_label
    for sheet in workbook.worksheets:
        sheet.protection.sheet = False
    return workbook


def create_workbooks(cards_path, output):
    cards_path = cards_path.resolve()
    output.mkdir(parents=True, exist_ok=True)
    catalog = json.loads(cards_path.read_text(encoding="utf-8"))
    source_hash = sha256(cards_path)
    paths = []
    for label, filename in (("审核人 A", "岗位能力卡-审核人A.xlsx"), ("审核人 B", "岗位能力卡-审核人B.xlsx")):
        path = output / filename
        build_workbook(catalog, label, source_hash).save(path)
        paths.append(path)
    return paths


def parse_args():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cards", type=Path,
                        default=root / "backend/src/main/resources/matching/role-capability-cards-v2.json")
    parser.add_argument("--output", type=Path,
                        default=root / "test-data/matching/role-capability-review-v2")
    return parser.parse_args()


def main():
    args = parse_args()
    for path in create_workbooks(args.cards, args.output):
        print(f"已生成: {path.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

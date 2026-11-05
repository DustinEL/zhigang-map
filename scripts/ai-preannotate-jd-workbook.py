from __future__ import annotations

import json
import re
import time
import urllib.request
from pathlib import Path

from openpyxl import load_workbook


PROJECT_ROOT = Path(__file__).resolve().parents[1]
WORKBOOK_PATH = PROJECT_ROOT / "test-data" / "jd-extraction" / "JD人工标注样本100条.xlsx"
PROGRESS_PATH = PROJECT_ROOT / "test-data" / "jd-extraction" / "jd-ai-preannotation-progress.json"
MODEL_URL = "http://127.0.0.1:11500/v1/chat/completions"
MODEL_NAME = "qwen-local"

SYSTEM_PROMPT = """你是中国招聘JD金标预标注员。只依据用户提供的JD原文，不得使用常识补充。
输出严格JSON对象，不要Markdown：
{"title":"原文岗位名称或未明确","skills":["原文明确出现的技术技能"],"responsibilities":["原文职责完整句子，去掉序号和末尾标点"]}
规则：
1. title必须是原文岗位名称；原文没有岗位名称则填“未明确”，不得用岗位分类代替。
2. skills只收录技术、工具、平台、框架、方法、工程体系或明确专业领域；去掉“熟悉/掌握/精通”等修饰词；不收录沟通能力、学历、年限、公司名。
3. responsibilities只从“岗位职责/工作职责”段落抽取，保持每个编号条目的完整语义，不拆成短语，不从任职要求抽取职责。
4. 不要输出薪资、学历、经验和行业场景。"""


def normalize_for_match(value: str) -> str:
    return re.sub(r"[\s。；;，,、：:（）()／/]", "", str(value or "")).lower()


def clean_responsibility(value: str) -> str:
    cleaned = re.sub(r"^\s*(?:\(?[0-9一二三四五六七八九十]+\)?[.、．]|（[0-9一二三四五六七八九十]+）)\s*", "", value)
    return re.sub(r"[。；;\s]+$", "", cleaned).strip()


def clean_skill(value: str) -> str:
    cleaned = re.sub(r"^(?:熟悉|掌握|精通|了解|具备|使用|至少一种)\s*", "", str(value or "").strip())
    return re.sub(r"[。；;，,\s]+$", "", cleaned).strip()


def is_grounded(value: str, source: str) -> bool:
    needle = normalize_for_match(value)
    return bool(needle) and needle in normalize_for_match(source)


def extract_responsibilities_from_source(source: str) -> list[str]:
    heading = re.search(r"(?:【\s*)?(?:岗位|工作|职位|主要)职责(?:\s*】)?\s*[:：]?", source, re.I)
    if not heading:
        return []
    section = source[heading.end():]
    end = re.search(r"(?:【\s*)?(?:任职|岗位|职位|基本|资格|任职者)\s*(?:要求|资格|条件)(?:\s*】)?\s*[:：]?", section, re.I)
    if end:
        section = section[:end.start()]
    item_pattern = re.compile(
        r"(?:^|\n)\s*(?:\(?\d{1,2}\)?[.、．]|（\d{1,2}）)\s*(.+?)(?=(?:\n\s*(?:\(?\d{1,2}\)?[.、．]|（\d{1,2}）))|$)",
        re.S,
    )
    items = [clean_responsibility(match.group(1).replace("\n", " ")) for match in item_pattern.finditer(section)]
    return [item for item in items if item]


def extract_json(content: str) -> dict:
    content = content.strip()
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content, flags=re.I)
    start, end = content.find("{"), content.rfind("}")
    if start < 0 or end < start:
        raise ValueError("模型响应不含JSON对象")
    return json.loads(content[start : end + 1])


def request_lines(instruction: str, source: str, attempts: int = 4) -> list[str]:
    user_content = f"{instruction}\n只输出结果，每条一行，不要序号、解释或Markdown。没有结果输出：未明确\n\nJD原文：\n{source}"
    payload = {
        "model": MODEL_NAME,
        "temperature": 0,
        "max_tokens": 2200,
        "messages": [
            {"role": "system", "content": "你是中国招聘JD金标预标注员，只能摘录原文，不得推断或改写。"},
            {"role": "user", "content": user_content},
        ],
    }
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(
                MODEL_URL,
                data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                headers={"Content-Type": "application/json; charset=utf-8"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=180) as response:
                result = json.loads(response.read().decode("utf-8"))
            content = result["choices"][0]["message"]["content"].strip()
            content = re.sub(r"^```(?:text)?\s*|\s*```$", "", content, flags=re.I)
            return [line.strip() for line in content.splitlines() if line.strip() and line.strip() != "未明确"]
        except Exception:
            if attempt + 1 == attempts:
                raise
            time.sleep(2 * (attempt + 1))
    raise RuntimeError("无法获取模型标注")


def request_annotation(title_hint: str, source: str) -> dict:
    skills = request_lines(
        "摘录原文明示的技术、工具、平台、框架、方法、工程体系或专业技术领域。去掉熟悉、掌握、精通等前缀。",
        source,
    )
    responsibilities = request_lines(
        "只摘录岗位职责/工作职责段落中的每个完整编号条目，去掉编号和末尾标点；不得从任职要求抽取。",
        source,
    )
    return {"title": title_hint, "skills": skills, "responsibilities": responsibilities}


def grounded_annotation(candidate: dict, source: str, title_hint: str) -> tuple[str, list[str], list[str], list[str]]:
    warnings = []
    title = title_hint.strip() or str(candidate.get("title") or "未明确").strip()

    skills = []
    for value in candidate.get("skills") or []:
        skill = clean_skill(str(value))
        if skill and is_grounded(skill, source) and skill.lower() not in {item.lower() for item in skills}:
            skills.append(skill)
        elif skill:
            warnings.append(f"剔除未在原文定位的技能：{skill}")

    responsibilities = []
    for value in candidate.get("responsibilities") or []:
        responsibility = clean_responsibility(str(value))
        if responsibility and is_grounded(responsibility, source) and responsibility not in responsibilities:
            responsibilities.append(responsibility)
        elif responsibility:
            warnings.append("剔除未在原文逐字定位的职责")
    return title, skills, responsibilities, warnings


def save_progress(progress: dict) -> None:
    PROGRESS_PATH.write_text(json.dumps(progress, ensure_ascii=False, indent=2), encoding="utf-8")


def annotate_workbook() -> None:
    workbook = load_workbook(WORKBOOK_PATH)
    sheet = workbook["JD人工标注"]
    progress = json.loads(PROGRESS_PATH.read_text(encoding="utf-8")) if PROGRESS_PATH.exists() else {}
    total = sheet.max_row - 1
    for row in range(2, sheet.max_row + 1):
        sample_number = int(sheet.cell(row, 1).value)
        key = str(sample_number)
        if key not in progress:
            source = str(sheet.cell(row, 9).value or "")
            title_hint = str(sheet.cell(row, 7).value or "")
            candidate = request_annotation(title_hint, source)
            title, skills, responsibilities, warnings = grounded_annotation(candidate, source, title_hint)
            progress[key] = {
                "title": title,
                "skills": skills,
                "responsibilities": responsibilities,
                "warnings": warnings,
            }
            save_progress(progress)
            print(f"[{len(progress)}/{total}] 样本 {sample_number} 已预标注", flush=True)

        annotation = progress[key]
        sheet.cell(row, 10, "已完成")
        sheet.cell(row, 11, annotation["title"])
        sheet.cell(row, 15, "|".join(annotation["skills"]))
        sheet.cell(row, 16, "||".join(annotation["responsibilities"]))
        warning_text = "；".join(annotation["warnings"])
        sheet.cell(row, 18, f"Codex AI预标注，待人工逐条复核{('；' + warning_text) if warning_text else ''}")
        sheet.cell(row, 19, "Codex AI预标注")
        sheet.cell(row, 20, "2026-08-12")
        sheet.cell(row, 21, "待复核")
        sheet.cell(row, 22, None)
        sheet.cell(row, 23, None)
        if sample_number == 31:
            sheet.cell(row, 18, f"已用AI统一口径重新预标注，覆盖原手工内容，原内容保存在备份文件中{('；' + warning_text) if warning_text else ''}")
    workbook.save(WORKBOOK_PATH)
    print(json.dumps({"workbook": str(WORKBOOK_PATH), "annotated": len(progress)}, ensure_ascii=False))


if __name__ == "__main__":
    annotate_workbook()

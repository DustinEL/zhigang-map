from __future__ import annotations

import html
import json
import re
from pathlib import Path

from docx import Document
from pypdf import PdfReader


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / ".codex-temp" / "resume_72_101_sources"
SEARCH = ROOT / ".codex-temp" / "resume_search_100.json"
TEXT_SUFFIXES = {".html", ".htm", ".md", ".txt", ".json", ".js", ".jsx", ".ts", ".tsx", ".vue"}
SKIP_FILES = {
    "_screening.json", "screening_results.json", "preview_report.json",
    "_content_preview.txt", "package-lock.json", "pnpm-lock.yaml",
}
SIGNAL = re.compile(
    r"[\u4e00-\u9fff]|(?:19|20)\d{2}|Java|Python|C\+\+|C#|SQL|Vue|React|Go|Rust|"
    r"Figma|Axure|Photoshop|Docker|Kubernetes|Linux|Unity|Unreal|Blender|Maya|CAD|"
    r"教育|经历|项目|技能|成果|奖项|荣誉|证书|大学|学院|公司|实习|工作|专业|求职",
    re.I,
)
NOISE = re.compile(
    r"(?i)(?:node_modules|sourceMappingURL|webpack|vite\.config|className|linear-gradient|"
    r"rgba\(|navigator\.|addEventListener|querySelector|localStorage|sessionStorage|"
    r"font-family|@keyframes|draco|three\.module|package\.json|README-部署|安装依赖|npm run|"
    r"坦克大战|飞机大战|游戏说明|操作说明|TECHNICAL_DESIGN|部署教程)"
)
HEADINGS = re.compile(
    r"^(?:关于我|个人简介|自我介绍|教育背景|教育经历|工作经历|实习经历|实践经历|"
    r"项目经历|项目经验|项目展示|作品集|专业技能|技术栈|技能|核心能力|研究经历|"
    r"研究成果|成果|获奖经历|荣誉奖项|证书|校园经历|工作成果|职业履历|自我评价)$",
    re.I,
)


def decode_escapes(value: str) -> str:
    value = html.unescape(value)
    value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match.group(1), 16)), value)
    return value.replace("\\n", " ").replace("\\t", " ").replace("\\\"", '"').replace("\\'", "'")


def html_blocks(text: str) -> list[str]:
    blocks: list[str] = []
    clean_html = re.sub(r"(?is)<(?:style|noscript|svg)\b.*?</(?:style|noscript|svg)>", " ", text)
    for match in re.finditer(r"(?is)<(?:h[1-4]|p|li|dt|dd|td|th)\b[^>]*>(.*?)</(?:h[1-4]|p|li|dt|dd|td|th)>", clean_html):
        value = re.sub(r"(?s)<[^>]+>", " ", match.group(1))
        value = html.unescape(re.sub(r"\s+", " ", value)).strip()
        if value:
            blocks.append(value)
    for script in re.findall(r"(?is)<script\b[^>]*>(.*?)</script>", clean_html):
        blocks.extend(quoted_blocks(script))
    return blocks


def quoted_blocks(text: str) -> list[str]:
    blocks = []
    for left, right, tick in re.findall(
        r"(?:'((?:\\.|[^'\\]){2,700})'|\"((?:\\.|[^\"\\]){2,700})\"|`((?:\\.|[^`\\]){2,700})`)",
        text,
    ):
        value = decode_escapes(left or right or tick)
        if SIGNAL.search(value):
            blocks.append(value)
    return blocks


def document_blocks(path: Path) -> list[str]:
    if path.suffix.lower() == ".pdf":
        return [page.extract_text() or "" for page in PdfReader(path).pages]
    if path.suffix.lower() == ".docx":
        doc = Document(path)
        return [p.text for p in doc.paragraphs] + [
            cell.text for table in doc.tables for row in table.rows for cell in row.cells
        ]
    text = path.read_text(encoding="utf-8", errors="ignore")
    if path.suffix.lower() in {".html", ".htm"}:
        return html_blocks(text)
    if path.suffix.lower() in {".js", ".jsx", ".ts", ".tsx", ".vue", ".json"}:
        return quoted_blocks(text)
    return text.splitlines()


def clean(value: str) -> str:
    value = re.sub(r"(?s)<[^>]+>", " ", value)
    value = re.sub(r"!\[[^]]*\]\([^)]*\)|\[([^]]+)\]\([^)]*\)", r"\1", value)
    value = re.sub(r"^\s*#{1,6}\s*|^\s*[-*+]\s*", "", value)
    value = re.sub(r"\s+", " ", value).strip(" |,;`{}[]")
    return value


def build_preview(folder: Path, description: str) -> dict[str, object]:
    candidates = []
    for path in folder.rglob("*"):
        if not path.is_file() or path.name in SKIP_FILES or path.stat().st_size > 3_000_000:
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES | {".pdf", ".docx"}:
            continue
        try:
            for block in document_blocks(path):
                candidates.append((path, block))
        except Exception:
            continue

    output = [f"来源仓库：{description}"]
    seen = set()
    for path, raw in candidates:
        value = clean(raw)
        key = re.sub(r"\W+", "", value).lower()
        if (
            len(key) < 4
            or len(value) > 700
            or not SIGNAL.search(value)
            or NOISE.search(value)
            or key in seen
            or value.count(":") > 15
            or re.fullmatch(r"[A-Za-z0-9_./:@#%+\-= ]+", value)
        ):
            continue
        seen.add(key)
        label = path.relative_to(folder).as_posix()
        output.append(f"[{label}] {value}")

    preview = folder / "_content_preview.txt"
    preview.write_text("\n".join(output), encoding="utf-8")
    text = "\n".join(output)
    return {
        "folder": folder.name,
        "preview": str(preview),
        "lines": len(output),
        "chinese": len(re.findall(r"[\u4e00-\u9fff]", text)),
        "sections": {
            name: bool(re.search(pattern, text, re.I))
            for name, pattern in {
                "education": r"教育|大学|学院|本科|硕士|博士",
                "work": r"工作|实习|任职|公司|企业",
                "project": r"项目|作品|产品",
                "skill": r"技能|技术栈|熟悉|掌握|工具",
                "result": r"成果|奖项|荣誉|证书|提升|增长|一等奖|二等奖|三等奖",
            }.items()
        },
    }


def main() -> None:
    descriptions = {
        item["full_name"].replace("/", "__"): item.get("description") or item["full_name"]
        for item in json.loads(SEARCH.read_text(encoding="utf-8"))
    }
    report = []
    for folder in sorted(path for path in SOURCE_ROOT.iterdir() if path.is_dir()):
        record = build_preview(folder, descriptions.get(folder.name, folder.name))
        report.append(record)
        print(f"{folder.name}: lines={record['lines']} zh={record['chinese']}")
    (SOURCE_ROOT / "preview_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )


if __name__ == "__main__":
    main()

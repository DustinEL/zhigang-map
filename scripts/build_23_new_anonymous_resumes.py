from __future__ import annotations

import html
import re
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "测试简历_下一轮独立评测"

SOURCE_DIRS = [
    (".resume_mainland3_search", "01"),
    (".resume_mainland3_search", "02"),
    (".resume_mainland3_search", "03"),
] + [(".resume_sources", x) for x in ["chensiqi-resume2026", "ios_audio_video_dev_resume", "iot-resume", "personal-resume", "resume-homepage", "shiwei-resume", "zhilu-cv"]] + [
    (".resume_candidate26_stage", "26"),
    (".resume_batch14", "17_AboLe"),
    (".resume_batch14", "18_YuanYaohui"),
    (".resume_batch14", "19_GuoChunhao"),
] + [(".resume_sources_more", x) for x in ["conanke", "ethan", "guojiahui", "huangyixiang", "sunzhengjun", "ti0001", "wangziyu", "xuanruimu", "zhanghanlin"]]

NAMES = [
    "秦慧圆", "郭力铭", "叶其芳", "陈思琪", "吴保来", "徐华", "林健豪", "黄广平", "石伟", "纸鹿",
    "张辰灿", "Abo Le", "院耀辉", "郭春豪", "柯天南", "岳子烨", "郭嘉辉", "孙正君", "王梓宇",
    "张汉林", "玄锐暮", "黄奕翔", "GXY", "Chj", "Yiming Su", "Siqi Chen", "Xu Hua", "Shiwei",
]


def read_pdf(path: Path) -> str:
    return "\n".join(page.extract_text() or "" for page in PdfReader(str(path)).pages)


def read_source(path: Path) -> str:
    if path.suffix.lower() == ".pdf":
        return read_pdf(path)
    if path.suffix.lower() == ".docx":
        doc = Document(str(path))
        return "\n".join(p.text for p in doc.paragraphs) + "\n" + "\n".join(
            cell.text for table in doc.tables for row in table.rows for cell in row.cells
        )
    text = path.read_text(encoding="utf-8", errors="ignore")
    for include in re.findall(r"\\input\{([^}]+)\}", text):
        inc = path.parent / include
        if inc.suffix == "":
            inc = inc.with_suffix(".tex")
        if inc.exists():
            text += "\n" + inc.read_text(encoding="utf-8", errors="ignore")
    text = re.sub(r"(?is)<(script|style).*?>.*?</\1>", " ", text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = html.unescape(text)
    text = re.sub(r"\\(?:href|url)\{([^}]*)\}\{([^}]*)\}", r"\2", text)
    text = re.sub(r"\\[A-Za-z]+(?:\[[^]]*\])?", " ", text)
    text = re.sub(r"[{}]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def choose_source(base: Path) -> Path:
    if not base.exists():
        matches = sorted(base.parent.glob(base.name + "*"))
        if len(matches) == 1:
            base = matches[0]
    preferred = {
        "shiwei-resume": "src/data/resume.ts",
        "zhilu-cv": "zhilu-cv-main/app/app.vue",
        "ti0001": "JianLI-main/resume-vue/src/data/resume.js",
        "chensiqi-resume2026": "chensiqi-resume2026-main/latex/resume_CN_20260613.tex",
        "17_AboLe": "skywalker23241.github.io-main/index.html",
        "18_YuanYaohui": "jianli-main/index.html",
        "19_GuoChunhao": "jianli-main/resume.pdf",
    }
    for key, rel in preferred.items():
        if base.name.startswith(key):
            candidate = base / rel
            if candidate.exists():
                return candidate
    candidates = []
    for path in base.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".pdf", ".docx", ".tex", ".html", ".htm", ".jsx", ".tsx", ".vue", ".md"}:
            continue
        if path.name.lower() in {"readme.md", "agents.md", "claude.md", "codex.md", "projectlist.vue", "clotheslinenotes.tsx", "app.vue", "progress.md", "contact.tsx"}:
            continue
        if any(part in {"node_modules", ".git", "__pycache__", "cv", "common", "sections", ".claude", "docs", "plans", "rules", "reference"} for part in path.parts):
            continue
        candidates.append(path)
    if not candidates:
        raise FileNotFoundError(base)
    return max(candidates, key=lambda p: p.stat().st_size)


def redact(text: str) -> str:
    for name in sorted(NAMES, key=len, reverse=True):
        text = re.sub(re.escape(name), "匿名候选人", text, flags=re.I)
    text = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[联系方式已脱敏]", text)
    text = re.sub(r"(?:https?://|www\.)\S+", "[链接已脱敏]", text, flags=re.I)
    text = re.sub(r"(?:\+?\d[\d ()-]{7,}\d)", "[联系方式已脱敏]", text)
    text = re.sub(r"(?i)(linkedin|github|portfolio|website)\s*[:|]?\s*\S+", "[链接已脱敏]", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def clean_xml_text(text: str) -> str:
    return "".join(ch for ch in text if ch in "\t\n\r" or ord(ch) >= 0x20)


def configure(doc: Document) -> None:
    sec = doc.sections[0]
    sec.page_width, sec.page_height = Cm(21.59), Cm(27.94)
    sec.top_margin = sec.bottom_margin = sec.left_margin = sec.right_margin = Cm(2.54)
    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(10)
    normal.paragraph_format.space_after = Pt(4)
    normal.paragraph_format.line_spacing = 1.15
    for name, size, color in (("Heading 1", 15, "2E74B5"), ("Heading 2", 12, "1F4D78")):
        style = doc.styles[name]
        style.font.name = "Calibri"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(size)
        style.font.bold = True
        style.font.color.rgb = RGBColor.from_string(color)


def build(index: int, body: str) -> Path:
    folder = OUTPUT / f"{index:02d}_匿名候选人"
    folder.mkdir(parents=True, exist_ok=True)
    doc = Document()
    configure(doc)
    title = doc.add_paragraph()
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = title.add_run(f"候选人 {index:02d}")
    run.bold = True
    run.font.size = Pt(22)
    run.font.color.rgb = RGBColor(11, 37, 69)
    sub = doc.add_paragraph()
    sub.alignment = WD_ALIGN_PARAGRAPH.CENTER
    sub.add_run("个人简历（已脱敏）").font.size = Pt(10)
    body = clean_xml_text(body)
    lines = [clean_xml_text(x.strip(" |•")) for x in re.split(r"\n|(?<=\.) (?=[A-Z][A-Za-z ]{2,24}:)", body) if x.strip()]
    headings = re.compile(r"(?i)^(education|experience|work experience|employment|projects?|technical projects?|skills?|certifications?|research|awards?|summary|professional summary|教育背景|工作经历|项目经历|技能|专业技能)$")
    current = "个人资料"
    for line in lines:
        if headings.match(line.strip(" :-")):
            current = line.strip(" :-")
            doc.add_heading(current, level=1)
        elif len(line) > 180:
            p = doc.add_paragraph(style="List Bullet")
            p.paragraph_format.left_indent = Cm(0.64)
            p.paragraph_format.first_line_indent = Cm(-0.32)
            p.add_run(line)
        else:
            doc.add_paragraph(line)
    target = folder / f"{index:02d}_匿名候选人_已脱敏简历.docx"
    doc.save(target)
    return target


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for offset, (root_name, folder_name) in enumerate(SOURCE_DIRS, start=11):
        source = choose_source(ROOT / root_name / folder_name)
        body = redact(read_source(source))
        build(offset, body)
    print(f"generated={len(SOURCE_DIRS)}")


if __name__ == "__main__":
    main()

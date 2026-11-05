from __future__ import annotations

import re
import shutil
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor
from pypdf import PdfReader


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "测试简历_下一轮独立评测"
STAGING = ROOT / ".resume_new13_search" / "extracted"
STAGING2 = ROOT / ".resume_candidate26" / "extracted"

SOURCES = [
    ("01", "Asad-Jaffery__Resume", "Resume-main/resumes/asad_jaffery_resume_1.pdf"),
    ("02", "HaasCM__resume", "resume-master/CV.tex"),
    ("03", "jaisheng-ong__jaisheng-ong-resume", "jaisheng-ong-resume-main/Ong_JaiSheng_Resume2027.pdf"),
    ("04", "ManthanKeim__resume", "resume-master/resume(1).pdf"),
    ("05", "priths7__Resume", "Resume-master/resume.pdf"),
    ("06", "The-Jat__resume", "resume-main/manish_kala_resume_ours.pdf"),
    ("07", "donaldchiem_resume", "resume-main/resume.pdf"),
    ("08", "getotherscode_my-resume", "my-resume-main/resume-260815.pdf"),
    ("09", "hiiiideepak_deepakbharti-resume", "deepakbharti-resume-main/deep_resume-openfont.pdf"),
    ("10", "rookiepeng__zpeng-resume-cv", "zpeng-resume-cv-master/LaTeX/zpeng_cv.tex"),
]

KNOWN_NAMES = [
    "Asad Jaffery", "Daniil Belyakov", "Jai Sheng Ong", "Manthan Keim",
    "Prithvi Chakravarthy", "Manish Kala", "Donald Chiem", "张辰灿",
    "Deepak Bharti", "Peng", "Zhang Chen Can",
]


def read_pdf(path: Path) -> str:
    return "\n".join(page.extract_text() or "" for page in PdfReader(str(path)).pages)


def read_tex(path: Path) -> str:
    text = path.read_text(encoding="utf-8", errors="ignore")
    inputs = re.findall(r"\\input\{([^}]+)\}", text)
    for include in inputs:
        include_path = path.parent / include
        if include_path.suffix == "":
            include_path = include_path.with_suffix(".tex")
        if include_path.exists():
            text += "\n" + include_path.read_text(encoding="utf-8", errors="ignore")
    text = re.sub(r"(?m)^\s*%.*$", "", text)
    text = re.sub(r"\\(?:href|url)\{([^}]*)\}\{([^}]*)\}", r"\2", text)
    text = re.sub(r"\\(?:textbf|textit|emph|underline|section|subsection|subsubsection|name|email|phone|address|homepage|github|linkedin|title)\*?\{([^{}]*)\}", r"\1", text)
    text = re.sub(r"\\[A-Za-z]+(?:\[[^]]*\])?", " ", text)
    text = text.replace("{", " ").replace("}", " ").replace("&", "&")
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def resolve_source(folder: str, rel: str) -> Path:
    for base in (STAGING, STAGING2):
        candidate = base / folder / rel
        if candidate.exists():
            return candidate
    raise FileNotFoundError(rel)


def anonymize(text: str) -> str:
    text = text.replace("\u2022", "•").replace("\uf0b7", "•")
    for name in sorted(KNOWN_NAMES, key=len, reverse=True):
        text = re.sub(re.escape(name), "匿名候选人", text, flags=re.I)
    text = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[联系方式已脱敏]", text)
    text = re.sub(r"(?:https?://|www\.)\S+", "[链接已脱敏]", text, flags=re.I)
    text = re.sub(r"(?<!\w)(?:\+?\d[\d ()-]{7,}\d)(?!\w)", "[联系方式已脱敏]", text)
    text = re.sub(r"(?i)(linkedin|github|portfolio|website)\s*[:|]?\s*\S+", "[链接已脱敏]", text)
    text = re.sub(r"(?i)(email|phone|tel|mobile|address|location|contact)\s*[:|]?\s*[^|\n]+", "[联系方式已脱敏]", text)
    return text


def split_sections(text: str) -> list[tuple[str, list[str]]]:
    headings = re.compile(r"(?i)\b(education|experience|work experience|employment|projects?|technical projects?|skills?|certifications?|research|awards?|professional summary|summary|教育背景|工作经历|项目经历|技能)\b")
    lines = [re.sub(r"\s+", " ", line).strip(" |•") for line in text.splitlines()]
    lines = [line for line in lines if line]
    sections: list[tuple[str, list[str]]] = []
    current = "个人资料"
    bucket: list[str] = []
    for line in lines:
        match = headings.fullmatch(line.strip(" :|-"))
        if match:
            if bucket:
                sections.append((current, bucket))
            current = match.group(1)
            bucket = []
        else:
            bucket.append(line)
    if bucket:
        sections.append((current, bucket))
    if not sections:
        sections = [("个人资料", lines)]
    return sections


def configure(document: Document) -> None:
    section = document.sections[0]
    section.page_width = Cm(21.59)
    section.page_height = Cm(27.94)
    section.top_margin = Cm(2.54)
    section.bottom_margin = Cm(2.54)
    section.left_margin = Cm(2.54)
    section.right_margin = Cm(2.54)
    normal = document.styles["Normal"]
    normal.font.name = "Calibri"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(10)
    normal.paragraph_format.space_after = Pt(4)
    normal.paragraph_format.line_spacing = 1.15
    for name, size, color in (("Heading 1", 15, "2E74B5"), ("Heading 2", 12, "1F4D78")):
        style = document.styles[name]
        style.font.name = "Calibri"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor.from_string(color)
        style.font.bold = True


def build_doc(index: str, text: str) -> Path:
    folder = OUTPUT / f"{index}_匿名候选人"
    folder.mkdir(parents=True, exist_ok=True)
    doc = Document()
    configure(doc)
    title = doc.add_paragraph()
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = title.add_run(f"匿名候选人 {index}")
    run.font.name = "Calibri"
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    run.font.size = Pt(22)
    run.font.bold = True
    run.font.color.rgb = RGBColor(11, 37, 69)
    note = doc.add_paragraph()
    note.alignment = WD_ALIGN_PARAGRAPH.CENTER
    note.add_run("个人简历（已脱敏）").font.size = Pt(10)
    for heading, items in split_sections(text):
        doc.add_heading(heading, level=1)
        for item in items:
            if len(item) > 180:
                p = doc.add_paragraph(style="List Bullet")
                p.paragraph_format.left_indent = Cm(0.64)
                p.paragraph_format.first_line_indent = Cm(-0.32)
                p.add_run(item)
            else:
                doc.add_paragraph(item)
    target = folder / f"{index}_匿名候选人_已脱敏简历.docx"
    doc.save(target)
    return target


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for index, folder, rel in SOURCES:
        source = resolve_source(folder, rel)
        raw = read_pdf(source) if source.suffix.lower() == ".pdf" else read_tex(source)
        clean = anonymize(raw)
        build_doc(index, clean)
    print("generated", len(SOURCES))


if __name__ == "__main__":
    main()

from __future__ import annotations

import html
import re
from pathlib import Path

from bs4 import BeautifulSoup
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor


ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / ".codex-temp" / "resume_36_40_sources" / "raw"
OUTPUT = ROOT / "测试简历_正式盲测候选池_2026-08-26后新增"

CANDIDATES = [
    {
        "index": 36,
        "role": "AI 数据分析师",
        "names": ["李晟扬", "LI SHENGYANG", "Li Shengyang", "李 晟 扬"],
        "files": ["lishengyang.txt"],
    },
    {
        "index": 37,
        "role": "AI 算法工程师",
        "names": ["黄健文", "Huang Jianwen", "Kelvin Huang"],
        "files": ["kelvin_index.txt"],
    },
    {
        "index": 38,
        "role": "数据分析与产品实践",
        "names": ["李嘉豪", "Li Jiahao"],
        "files": ["xiaolu_index.txt", "xiaolu_data.txt"],
    },
    {
        "index": 39,
        "role": "跨域机器人研究与软件工程",
        "names": ["黄乐", "Huang Le"],
        "files": ["haipi.txt"],
    },
    {
        "index": 40,
        "role": "前端开发",
        "names": ["蒋明哲鑫", "Jiang Mingzhexin"],
        "files": ["jjy_profile.txt"],
    },
]

BLOCK_TAGS = {"h1", "h2", "h3", "h4", "p", "li", "dt", "dd", "td", "th"}
HEADING_RE = re.compile(
    r"^(?:\d{1,2}\s*[/·—-]?\s*)?(?:关于我|个人简介|专业概况|教育背景|教育与研究方向|"
    r"工作经历|实习经历|项目经历|项目经验|精选项目|研究成果|代表性研究成果|技能|技术栈|"
    r"专业技能|实践经验|获奖经历|奖项|证书|校园经历|自我评价|个人总结)$",
    re.I,
)


def clean_xml_text(text: str) -> str:
    return "".join(ch for ch in text if ch in "\t\n\r" or ord(ch) >= 0x20)


def extract_html_blocks(text: str) -> list[str]:
    soup = BeautifulSoup(text, "html.parser")
    for tag in soup(["script", "style", "noscript", "svg"]):
        tag.decompose()
    blocks: list[str] = []
    for tag in soup.find_all(BLOCK_TAGS):
        if tag.find_parent(BLOCK_TAGS):
            continue
        value = tag.get_text(" ", strip=True)
        if value:
            blocks.append(value)
    if len(" ".join(blocks)) < 300:
        blocks = [x.strip() for x in soup.get_text("\n").splitlines() if x.strip()]
    return blocks


def extract_data_strings(text: str) -> list[str]:
    strings = re.findall(r"(?s)(?:'((?:\\.|[^'\\])*)'|\"((?:\\.|[^\"\\])*)\")", text)
    values: list[str] = []
    for left, right in strings:
        value = left or right
        value = value.replace("\\n", " ").replace("\\'", "'").replace('\\"', '"')
        value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), value)
        value = html.unescape(value).strip()
        if len(value) >= 2 and not re.fullmatch(r"[A-Za-z0-9_./:@-]+", value):
            values.append(value)
    return values


def read_blocks(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    if "<html" in text.lower() or "<!doctype" in text.lower():
        return extract_html_blocks(text)
    return extract_data_strings(text)


def redact(text: str, names: list[str]) -> str:
    text = clean_xml_text(html.unescape(text))
    for name in sorted(names, key=len, reverse=True):
        text = re.sub(re.escape(name), "匿名候选人", text, flags=re.I)
    text = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[联系方式已脱敏]", text)
    text = re.sub(r"(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)", "[联系方式已脱敏]", text)
    text = re.sub(r"(?:https?://|www\.)\S+", "[链接已脱敏]", text, flags=re.I)
    text = re.sub(r"(?i)(?:github|gitee|linkedin)\.com/\S+", "[链接已脱敏]", text)
    text = re.sub(r"(?i)(微信|QQ|WeChat)\s*[:：]?\s*[A-Za-z0-9_-]{4,}", r"\1：[联系方式已脱敏]", text)
    text = re.sub(r"\s+", " ", text).strip(" |·•-")
    return text


def prepare_blocks(candidate: dict) -> list[str]:
    blocks: list[str] = []
    for filename in candidate["files"]:
        blocks.extend(read_blocks(RAW / filename))

    skip = re.compile(
        r"^(?:首页|关于|技能|项目|经历|教育|联系|联系我|查看项目|查看作品集|打开导航|"
        r"跳到主要内容|快速导航|导航|返回顶部|全部)$",
        re.I,
    )
    seen: set[str] = set()
    cleaned: list[str] = []
    for block in blocks:
        block = redact(block, candidate["names"])
        if (
            not block
            or skip.fullmatch(block)
            or len(block) > 900
            or re.search(r"(?i)^(?:Email|Phone|WeChat|GitHub)\b", block)
            or re.search(r"(?i)^(?:06\s*[—/-]\s*Contact|如果你有.*邮件、微信)", block)
            or "100% VANILLA JS" in block
            or re.search(r"(?i)lsyGeeK|aaa030226", block)
        ):
            continue
        key = re.sub(r"\W+", "", block).lower()
        if len(key) < 2 or key in seen:
            continue
        seen.add(key)
        cleaned.append(block)
    compacted: list[str] = []
    short_buffer: list[str] = []

    def flush_buffer() -> None:
        if short_buffer:
            compacted.append(" · ".join(short_buffer))
            short_buffer.clear()

    for block in cleaned:
        heading_text = re.sub(r"^\d{1,2}\s*[/·—-]?\s*", "", block).strip()
        if HEADING_RE.fullmatch(heading_text):
            flush_buffer()
            compacted.append(block)
        elif len(block) <= 36 and not re.search(r"[。！？；:]$", block):
            projected = len(" · ".join(short_buffer + [block]))
            if projected > 150:
                flush_buffer()
            short_buffer.append(block)
        else:
            flush_buffer()
            compacted.append(block)
    flush_buffer()
    return compacted


def configure_document(doc: Document) -> None:
    section = doc.sections[0]
    section.page_width = Inches(8.5)
    section.page_height = Inches(11)
    section.top_margin = section.right_margin = section.bottom_margin = section.left_margin = Inches(1)
    section.header_distance = section.footer_distance = Inches(0.492)

    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(10)
    normal.paragraph_format.space_before = Pt(0)
    normal.paragraph_format.space_after = Pt(3)
    normal.paragraph_format.line_spacing = 1.1

    for name, size, color, before, after in (
        ("Heading 1", 15, "2E74B5", 12, 6),
        ("Heading 2", 12, "2E74B5", 10, 5),
        ("Heading 3", 11, "1F4D78", 8, 4),
    ):
        style = doc.styles[name]
        style.font.name = "Calibri"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(size)
        style.font.bold = True
        style.font.color.rgb = RGBColor.from_string(color)
        style.paragraph_format.space_before = Pt(before)
        style.paragraph_format.space_after = Pt(after)


def build(candidate: dict) -> Path:
    index = candidate["index"]
    folder = OUTPUT / f"{index:02d}_匿名候选人"
    folder.mkdir(parents=True, exist_ok=True)

    doc = Document()
    configure_document(doc)
    doc.core_properties.title = "个人简历（已脱敏）"
    doc.core_properties.author = ""
    doc.core_properties.last_modified_by = ""

    title = doc.add_paragraph()
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    title.paragraph_format.space_after = Pt(3)
    run = title.add_run("个人简历（已脱敏）")
    run.bold = True
    run.font.name = "Calibri"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    run.font.size = Pt(22)
    run.font.color.rgb = RGBColor.from_string("0B2545")

    subtitle = doc.add_paragraph()
    subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
    subtitle.paragraph_format.space_after = Pt(12)
    subtitle_run = subtitle.add_run(candidate["role"])
    subtitle_run.font.size = Pt(11)
    subtitle_run.font.color.rgb = RGBColor.from_string("555555")

    blocks = prepare_blocks(candidate)
    for block in blocks:
        heading_text = re.sub(r"^\d{1,2}\s*[/·—-]?\s*", "", block).strip()
        if HEADING_RE.fullmatch(heading_text):
            doc.add_heading(heading_text, level=1)
        else:
            paragraph = doc.add_paragraph(block)
            paragraph.paragraph_format.keep_together = False

    target = folder / f"{index:02d}_匿名候选人_已脱敏简历.docx"
    doc.save(target)
    return target


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    outputs = [build(candidate) for candidate in CANDIDATES]
    for output in outputs:
        print(output)


if __name__ == "__main__":
    main()

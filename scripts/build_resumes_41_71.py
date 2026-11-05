from __future__ import annotations

import base64
import html
import json
import re
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor
from pypdf import PdfReader


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "测试简历_正式盲测候选池_2026-08-26后新增"
RAW = ROOT / ".codex-temp" / "resume_41_71_raw"
JAVA = ROOT / ".codex-temp" / "research_java_agent"
LLM = ROOT / ".codex-temp" / "research_llmops_backend" / "raw"
FETCHED = ROOT / ".codex-temp" / "resume_41_71_fetched"
ROCHSEN = (
    JAVA
    / "_screen"
    / "Rochsen__Intro"
    / "src"
    / ".vuepress"
    / "components"
    / "data.ts"
)


def p(*parts: str) -> Path:
    return ROOT.joinpath(*parts)


CANDIDATES = [
    (41, "Java 后端开发工程师", ["张昊宇"], [JAVA / "01" / "java_resume.pdf"]),
    (42, "Java 后端 / AI 应用开发", ["王瀚铖"], [JAVA / "02" / "readme.json"]),
    (43, "Java 后端 / AI 工程化开发", ["董裔天"], [JAVA / "03" / "index.html"]),
    (44, "Java 后端开发实习", ["陈彪"], [JAVA / "04" / "readme.json"]),
    (45, "Java 后端开发实习", ["刘博文"], [JAVA / "05" / "readme.json"]),
    (46, "Java 后端开发", ["梁超"], [JAVA / "06" / "readme.json"]),
    (47, "AI 应用 / Agent 工程", ["林钡镕"], [JAVA / "07" / "index.html"]),
    (48, "LLM 应用 / Python 全栈开发", ["王易磊"], [JAVA / "08" / "resume.pdf"]),
    (49, "AI 应用 / 通用后端开发", ["王顺", "Wang Shun"], [JAVA / "09" / "source.json"]),
    (50, "Agent 工程 / AI Native 全栈", ["金张政"], [JAVA / "10" / "resume-latest.pdf"]),
    (51, "LLM 与 Agent 研究工程", ["黄彬"], [LLM / "08_huangbin.json"]),
    (52, "企业 AI Agent 平台研发", ["汪毅盛"], [RAW / "esonwong__resume.txt"]),
    (53, "Agent 工程 / AI 工具应用", ["杨豫豪"], [RAW / "yanghao158640__competition-bootcamp.txt"]),
    (54, "深度学习应用与模型部署", ["罗劲锋"], [LLM / "01_luojinfeng.json"]),
    (55, "测试开发 / 机器学习工程", ["王江"], [LLM / "05_wangjiang.json"]),
    (56, "AI / 嵌入式算法工程", ["胡庆培"], [LLM / "09_huqingpei.json"]),
    (57, "数据分析 / 机器学习", ["秦超然"], [LLM / "10_qinchaoran.json"]),
    (58, "AI 项目 / 产品与医疗数据", ["干韩鹏"], [RAW / "ganhanpeng-pixel__gan-hanpeng-personal-profile.txt"]),
    (59, "AI 产品 / 搜索与数据分析", ["刘梦惠"], [RAW / "sevennora-77__sevennora-77.github.io.txt"]),
    (60, "AI 产品 / 搜推策略与数据", ["邹孝沙"], [RAW / "zoey-xs__resume.txt"]),
    (61, "AI 产品 / Agent 与自动化工作流", ["张滨文"], [RAW / "duyu06__resume.txt"]),
    (62, "PHP / 后端工程", ["王龙翔"], [LLM / "03_wanglongxiang.json"]),
    (63, ".NET / 软件开发", ["奎君"], [LLM / "04_kuijun.json"]),
    (64, "全栈 / 平台后端工程", ["李国伟"], [LLM / "06_liguowei.json"]),
    (65, "Java / Python 与 RAG 应用开发", ["徐嘉瑶"], [RAW / "LuomingXu__Resume.txt"]),
    (66, "嵌入式 AI 全栈开发", ["龙清扬"], [RAW / "Simon404Error__portfolio.txt"]),
    (67, "工业设计 / AI 工具链", ["徐家靖"], [RAW / "xujj18084248656__wangye.txt"]),
    (68, "AI 工具学习 / 早期学生实践", ["伍麒锦", "WU QIJIN"], [RAW / "ShDH-CMYK__wuqijin-resume.txt"]),
    (69, "AI 教育产品运营", ["姚伟俊"], [FETCHED / "haihai19880403-design__yao-resume" / "index.html"]),
    (70, "生物信息分析工程", ["罗浩森", "Luo Haosen"], [ROCHSEN]),
    (71, "供应链开发采购 / AI 工具应用", ["杨国辉"], [RAW / "SexLoving__yang-guohui-portfolio.txt"]),
]


HEADINGS = re.compile(
    r"^(?:\d+[.、 ]*)?(?:个人简介|自我介绍|教育背景|教育经历|工作经历|实习经历|"
    r"项目经历|项目经验|项目展示|专业技能|技术栈|技能|研究经历|研究成果|"
    r"获奖经历|荣誉奖项|证书|校园经历|工作成果|职业履历|核心能力)$",
    re.I,
)


def decode_api_json(path: Path) -> str:
    data = json.loads(path.read_text(encoding="utf-8"))
    content = data.get("content")
    if content:
        return base64.b64decode(content).decode("utf-8", errors="ignore")
    return json.dumps(data, ensure_ascii=False)


def source_text(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        return "\n".join(page.extract_text() or "" for page in PdfReader(path).pages)
    text = path.read_text(encoding="utf-8", errors="ignore")
    if path.name == "duyu06__resume.txt":
        values = []
        for left, right in re.findall(r"(?:'([^'\\\n]{2,700})'|\"([^\"\\\n]{2,700})\")", text):
            value = html.unescape(left or right).strip()
            if re.search(r"[\u4e00-\u9fff]", value) and not re.search(r"(?i)(?:className|assets/|\.css$|\.png$|\.jpg$)", value):
                values.append(value)
        return "\n".join(values)
    if path == ROCHSEN:
        zh = text.split('"zh-CN": {', 1)[1].split('"en-US": {', 1)[0]
        values = [
            html.unescape(left or right)
            for left, right in re.findall(r"(?:'([^'\\\n]{2,500})'|\"([^\"\\\n]{2,500})\")", zh)
            if re.search(r"[\u4e00-\u9fff]|20\d{2}|Python|FastApi|Vue3|Typescript|\bR\b|ACMG|VEP|ANNOVAR|GATK|OMIM|Gencode|PUBMED|ClinVar|UCSC", left or right)
        ]
        return "\n".join(values)
    if suffix == ".json":
        text = decode_api_json(path)
    if path.name == "Simon404Error__portfolio.txt" and "<body" in text.lower():
        text = re.split(r"<body[^>]*>", text, maxsplit=1, flags=re.I)[1]
        text = re.split(r"<script\b", text, maxsplit=1, flags=re.I)[0]
    return text


def visible_blocks(text: str) -> list[str]:
    original = text
    if "<html" in text.lower() or "<!doctype" in text.lower():
        text = re.sub(r"(?is)<(?:script|style|noscript|svg)\b.*?</(?:script|style|noscript|svg)>", "\n", text)
        text = re.sub(r"(?i)<br\s*/?>|</(?:p|li|h[1-6]|div|section|article|tr)>", "\n", text)
        text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = html.unescape(text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = re.sub(r"(?s)/\*.*?\*/|<!--.*?-->", "\n", text)
    text = re.sub(r"(?m)^\s*(?:import|export\s+(?:default|type)?|const\s+\w+\s*=|[{}\[\]],?\s*$)", "", text)
    text = re.sub(r"(?m)^\s*(?:[.#][\w-]+|@media|@keyframes|body|html|:root|nav|section|main|header|footer)[^{]*\{.*$", "", text)
    text = re.sub(r"(?m)^\s*(?:position|display|background|color|font-[^:]+|margin|padding|width|height|top|left|right|bottom|z-index|border|opacity|transition|transform|animation|grid-[^:]+|flex-[^:]+|overflow|align-[^:]+|justify-[^:]+|line-height|text-[^:]+|box-shadow|pointer-events|clip-path|content)\s*:[^;{}]+;?\s*$", "", text)
    text = re.sub(r"(?m)^\s*#{1,6}\s*", "", text)
    text = re.sub(r"(?m)^\s*[-*+]\s+", "", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    quoted = re.findall(r"['\"]([^'\"\n]{2,500})['\"]", text)
    lines: list[str] = []
    for raw_line in text.splitlines():
        raw_line = raw_line.strip(" \t|`;,{}[]")
        if len(raw_line) > 700:
            lines.extend(part for part in re.split(r"(?<=[。！？；])\s*", raw_line) if part)
        elif raw_line:
            lines.append(raw_line)
    lines.extend(quoted)
    if len("".join(lines)) < 900 and "<" not in original and re.search(r"[\u4e00-\u9fff]", original):
        html_text = re.sub(r"(?is)<(?:script|style|noscript|svg)\b.*?</(?:script|style|noscript|svg)>", "\n", original)
        html_text = re.sub(r"(?s)<[^>]+>", " ", html_text)
        lines.extend(re.sub(r"\s+", " ", x).strip() for x in html_text.splitlines() if x.strip())
    return [re.sub(r"\s+", " ", line).strip() for line in lines if line.strip()]


def redact(value: str, names: list[str]) -> str:
    value = html.unescape(value)
    for name in sorted(names, key=len, reverse=True):
        value = re.sub(re.escape(name), "匿名候选人", value, flags=re.I)
    value = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[联系方式已脱敏]", value)
    value = re.sub(r"(?<!\d)(?:\+?86[- ]?)?1[3-9](?:[- ]?\d){9}(?!\d)", "[联系方式已脱敏]", value)
    value = re.sub(r"(?:https?://|www\.)\S+", "[链接已脱敏]", value, flags=re.I)
    value = re.sub(r"(?i)(?:github|gitee|linkedin)\s*[:：]?\s*\S+", "[链接已脱敏]", value)
    value = re.sub(r"(?i)(微信|QQ|WeChat)\s*[:：]?\s*[A-Za-z0-9_-]{4,}", r"\1：[联系方式已脱敏]", value)
    value = re.sub(r"(?i)(?:微信号|复制微信号|复制QQ号)\s*[:：]?\s*[A-Za-z0-9_-]{4,}", "[联系方式已脱敏]", value)
    value = re.sub(r"(?i)(?:ID::(?:BIRTH|ORIGIN|GENDER|ETHNIC))\s*\S+(?:\s*[\u4e00-\u9fff·▪]+)*", "[个人信息已脱敏]", value)
    value = re.sub(r"(?i)mailto:\S+", "[联系方式已脱敏]", value)
    value = re.sub(r"\s+", " ", value).strip(" |-·•")
    return value


def prepare(candidate: tuple) -> list[str]:
    _, _, names, sources = candidate
    raw_blocks: list[str] = []
    for source in sources:
        raw_blocks.extend(visible_blocks(source_text(source)))
    skip = re.compile(
        r"(?i)^(?:home|about|contact|portfolio|blog|menu|skip|download|demo|copyright|"
        r"首页|关于|联系|导航|查看|打开|返回|回到顶部\s*↑?|下载|个人网站|开发组织|readme|license)$"
    )
    result: list[str] = []
    seen: set[str] = set()
    for block in raw_blocks:
        block = redact(block, names)
        key = re.sub(r"\W+", "", block).lower()
        if (
            len(key) < 4
            or len(block) > 700
            or skip.fullmatch(block)
            or key in seen
            or re.search(r"(?i)(?:node_modules|package-lock|pnpm-lock|webpack|vite\.config)", block)
            or re.search(r"(?i)(?:navigator\.clipboard|position\s*:\s*fixed|Gotohitorii|3042449437|已复制微信号|已复制QQ号)", block)
            or re.fullmatch(r"(?i)(?:Location|Salary|人人可建|乐于学习新的知识|时常关注学术发展|@?2021 made by jeffrey86 \*|协调开发资源)", block)
            or re.fullmatch(r"(?i)Python 项目代码\s*（?/Cell/）?", block)
            or re.search(r"(?i)^Bioinfo Databases\s*-\s*Genetic Diseases\s*-\s*Gene Annotation", block)
            or "Python项目代码" in block
            or "Bioinfo Databases" in block
            or re.fullmatch(r"(?i)(?:Genetic Diseases|Gene Annotation|Literature Search|Clinical Variants|Genome Browser|projects|Project|interests|welcome)", block)
            or re.search(r"(?i)(?:linear-gradient\(|rgba\()", block)
            or re.search(r"(?i)^©\s*20\d{2}\b", block)
            or block.count(":") > 14
        ):
            continue
        seen.add(key)
        result.append(block)
    return result[:180]


def compact_blocks(blocks: list[str]) -> list[str]:
    compacted: list[str] = []
    buffer: list[str] = []

    def flush() -> None:
        if buffer:
            compacted.append(" · ".join(buffer))
            buffer.clear()

    for block in blocks:
        normalized = re.sub(r"^\d+[.、 ]*", "", block).strip()
        if HEADINGS.fullmatch(normalized):
            flush()
            compacted.append(block)
        elif len(block) <= 38 and not re.search(r"[。！？；;:]$", block):
            if len(" · ".join(buffer + [block])) > 150:
                flush()
            buffer.append(block)
        else:
            flush()
            compacted.append(block)
    flush()
    return compacted


def set_font(run, size: float, color: str = "222222", bold: bool = False) -> None:
    run.font.name = "Calibri"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    run.font.size = Pt(size)
    run.font.color.rgb = RGBColor.from_string(color)
    run.bold = bold


def configure(doc: Document) -> None:
    section = doc.sections[0]
    section.page_width = Inches(8.5)
    section.page_height = Inches(11)
    section.top_margin = section.bottom_margin = Inches(0.58)
    section.left_margin = section.right_margin = Inches(0.72)
    section.header_distance = section.footer_distance = Inches(0.35)
    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(8.8)
    normal.paragraph_format.space_before = Pt(0)
    normal.paragraph_format.space_after = Pt(2)
    normal.paragraph_format.line_spacing = 1.08
    for name, size, before, after in (
        ("Heading 1", 14, 8, 4),
        ("Heading 2", 11.5, 7, 3),
        ("Heading 3", 10.5, 5, 2),
    ):
        style = doc.styles[name]
        style.font.name = "Calibri"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(size)
        style.font.bold = True
        style.font.color.rgb = RGBColor.from_string("235789")
        style.paragraph_format.space_before = Pt(before)
        style.paragraph_format.space_after = Pt(after)
        style.paragraph_format.keep_with_next = True


def add_page_number(section) -> None:
    paragraph = section.footer.paragraphs[0]
    paragraph.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    run = paragraph.add_run("第 ")
    set_font(run, 8, "777777")
    fld = OxmlElement("w:fldSimple")
    fld.set(qn("w:instr"), "PAGE")
    run._r.addnext(fld)
    tail = paragraph.add_run(" 页")
    set_font(tail, 8, "777777")


def build(candidate: tuple) -> Path:
    index, role, _, _ = candidate
    folder = OUTPUT / f"{index:02d}_匿名候选人"
    folder.mkdir(parents=True, exist_ok=True)
    doc = Document()
    configure(doc)
    doc.core_properties.title = "个人简历（已脱敏）"
    doc.core_properties.author = ""
    doc.core_properties.last_modified_by = ""
    title = doc.add_paragraph()
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    title.paragraph_format.space_after = Pt(2)
    set_font(title.add_run("个人简历（已脱敏）"), 21, "0B2545", True)
    subtitle = doc.add_paragraph()
    subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
    subtitle.paragraph_format.space_after = Pt(9)
    set_font(subtitle.add_run(role), 10.5, "555555")
    for block in compact_blocks(prepare(candidate)):
        normalized = re.sub(r"^\d+[.、 ]*", "", block).strip()
        if HEADINGS.fullmatch(normalized):
            doc.add_heading(normalized, level=1)
        else:
            paragraph = doc.add_paragraph(block)
            paragraph.paragraph_format.keep_together = False
    add_page_number(doc.sections[0])
    target = folder / f"{index:02d}_匿名候选人_已脱敏简历.docx"
    doc.save(target)
    return target


def main() -> None:
    if len({candidate[0] for candidate in CANDIDATES}) != 31:
        raise ValueError("候选人编号不唯一或数量不是 31")
    if len({tuple(candidate[2]) for candidate in CANDIDATES}) != 31:
        raise ValueError("候选人姓名重复")
    missing = [str(path) for candidate in CANDIDATES for path in candidate[3] if not path.exists()]
    if missing:
        raise FileNotFoundError("\n".join(missing))
    for candidate in CANDIDATES:
        target = build(candidate)
        print(target)


if __name__ == "__main__":
    main()

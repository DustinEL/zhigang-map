from __future__ import annotations

import html
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor
from pypdf import PdfReader


ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / ".codex-temp" / "resume_72_101_sources"
OUTPUT = ROOT / "测试简历_正式盲测候选池_2026-08-26后新增"
RENDER = ROOT / ".codex-temp" / "resume_72_101_rendered_final"
AUDIT = OUTPUT / "72-101_批次审计报告.json"


@dataclass(frozen=True)
class Candidate:
    number: int
    role: str
    names: tuple[str, ...]
    folder: str
    labels: tuple[str, ...]


CANDIDATES = (
    Candidate(72, "游戏美术 / 技术美术", ("陈雨",), "yunyufeiwei__yunyufeiwei.github.io", ("assets/i18n/i18n_cn.json",)),
    Candidate(73, "生物技术 / 生物信息分析", ("刘念",), "trna171__my-resume", ("src/App.jsx",)),
    Candidate(74, "推荐系统 / 数据增长", ("危学明",), "XuemingW__XuemingW.github.io", ("index.html",)),
    Candidate(75, "Java / ERP 全栈开发", ("蕴宇",), "YunYustudio__personal_resume_YunYu", ("src/data/resume.ts",)),
    Candidate(76, "3D / 视觉设计", ("张冰", "Sen Zheng"), "zhjojo__zhangbing-3d-resume", ("web/src/ui/Resume.tsx", "web/src/data/")),
    Candidate(77, "AI 产品 / 运营", ("彭泽曦", "Peng Zexi"), "pengzexi79-ops__interactive-resume", ("public/downloads/Peng-Zexi-AI-Native-Resume-2026.docx",)),
    Candidate(78, "产品经理", ("丁艳楠",), "d15035520862-art__resume", ("resume.html", "index.html")),
    Candidate(79, "AI 应用 / 测试开发 / 项目管理", ("Bigger蓝莓",), "wxhou__personal-resume", ("src/data/resume.js",)),
    Candidate(80, "机器人 / 嵌入式与端侧 AI", ("夏成辉",), "2427681280__resume", ("index.html",)),
    Candidate(81, "采购 / 供应链 / 生产运营", ("朱鹏",), "Jackzhu-90__Jackzhu-90.github.io", ("index.html",)),
    Candidate(82, "具身智能算法", ("黄宏玮",), "expolrer__HuangHongwei.github.io", ("index.html",)),
    Candidate(83, "AI / 物联网开发", ("高晟哲",), "jiostrider__gsz", ("resume.html", "public/resume.pdf")),
    Candidate(84, "机器人工程", ("李欣悦",), "jamil7184-blip__li-resume", ("index.html",)),
    Candidate(85, "量化系统 / C++ 金融技术", ("张开强",), "xunmeng2002__Resume", ("public/resume.html", "index.html")),
    Candidate(86, "创新创业教育 / 项目孵化", ("孙鸣凯",), "8c4yh8f2mb-ctrl__sunmingkai-resume", ("assets/index-",)),
    Candidate(87, "金融服务 / 学生管理", ("徐滢斐",), "8c4yh8f2mb-ctrl__xuyingfei-resume", ("assets/index-",)),
    Candidate(88, "财务分析 / 审计", ("刘倬含",), "Joyhanz-bot__portfolio", ("app/page.tsx",)),
    Candidate(89, "强化学习 / Agent Memory", ("胡嘉旺",), "MadarameHu__MadarameHu.github.io", ("resume.pdf", "index.html")),
    Candidate(90, "AI 工具 / 游戏开发", ("李弈德",), "yigong2333__lyd", ("index.html",)),
    Candidate(91, "AI 训练 / 游戏开发", ("马畅", "hachi0906"), "Hachi0906__personal-website", ("index.html",)),
    Candidate(92, "AI 工具 / 内容创作", ("欧森铭",), "ooo6873__ooo6873.github.io", ("index.html",)),
    Candidate(93, "智能车辆 / AI 应用", ("刘洋宾",), "Lyben888__resume", ("index.html",)),
    Candidate(94, "生物信息 / AI 产品", ("张茂鑫",), "Maosinger__Maosinger.github.io", ("index.html",)),
    Candidate(95, "AI 应用 / 前端工程", ("霍华翔", "Huahuoxiang"), "hhy077__resume", ("index.html",)),
    Candidate(96, "通信工程 / LLM 与 RAG", (), "Pekka-archer__intro", ("index.html",)),
    Candidate(97, "工业 AI / 企业架构", ("纪多斌",), "binbinao__resume", ("src/i18n/zh.ts", "src/i18n/profiles.ts", "content/")),
    Candidate(98, "AI 产品 / 跨境业务自动化", ("郑子杰",), "Jesko23__jesko23-portfolio", ("index.html",)),
    Candidate(99, "计算机综合项目 / 产品设计", ("刘华林",), "SeasonWen__LIU_HUALIN_PORTFOLIO_V3", ("liu_hualin_portfolio_v3/index.html",)),
    Candidate(100, "AI 技术支持 / 解决方案", ("蒋超喜",), "SaoBaiA__sibai-cloud-resume", ("assets/蒋超喜-简历.pdf", "assets/index-")),
    Candidate(101, "嵌入式 / 边缘 AI", ("张书源",), "BH2-4__resume-static", ("public/index.html",)),
)


HEADINGS = re.compile(
    r"^(?:\d+[.、 ]*)?(?:个人简介|自我介绍|自我评价|教育背景|教育经历|工作经历|实习经历|"
    r"实践经历|项目经历|项目经验|项目展示|代表作品|作品集|专业技能|技术栈|技能|核心能力|"
    r"研究经历|科研经历|研究成果|工作成果|成果|获奖经历|荣誉奖项|奖项荣誉|证书资质|证书|"
    r"校园经历|竞赛经历|职业履历|社会实践|资格认证|培训经历)$",
    re.I,
)


def source_lines(candidate: Candidate) -> list[str]:
    if candidate.number == 73:
        pdf = SOURCES / candidate.folder / "public" / "resume.pdf"
        text = "\n".join(page.extract_text() or "" for page in PdfReader(pdf).pages)
        text = re.sub(r"(?<=[\w\u4e00-\u9fff])\s+(?=[\w\u4e00-\u9fff])", "", text)
        return [line.strip() for line in text.splitlines() if line.strip()]
    if candidate.number == 95:
        page = (SOURCES / candidate.folder / "index.html").read_text(encoding="utf-8", errors="ignore")
        payload = page.split("highlights: [", 1)[1].split("let lang =", 1)[0]
        return [html.unescape(left or right) for left, right in re.findall(r"'([^'\n]{2,500})'|\"([^\"\n]{2,500})\"", payload)]
    if candidate.number == 91:
        page = (SOURCES / candidate.folder / "index.html").read_text(encoding="utf-8", errors="ignore")
        payload = page.split("const resume = {", 1)[1].split("/* ====== Helpers", 1)[0]
        return [html.unescape(left or right) for left, right in re.findall(r"'([^'\n]{2,500})'|\"([^\"\n]{2,500})\"", payload)]
    preview = SOURCES / candidate.folder / "_content_preview.txt"
    if not preview.exists():
        raise FileNotFoundError(preview)
    lines: list[str] = []
    for raw in preview.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^\[([^]]+)]\s*(.*)$", raw)
        if not match:
            continue
        label, value = match.groups()
        if any(label == allowed or label.startswith(allowed) for allowed in candidate.labels):
            lines.append(value)
    return lines


def redact(value: str, names: tuple[str, ...]) -> str:
    value = html.unescape(value)
    value = re.sub(r"(?s)<[^>]+>", " ", value)
    value = re.sub(r"\\[nrt]", " ", value)
    for name in sorted(names, key=len, reverse=True):
        value = re.sub(re.escape(name), "匿名候选人", value, flags=re.I)
    value = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[联系方式已脱敏]", value)
    value = re.sub(r"(?<!\d)(?:\+?86[- ]?)?1[3-9](?:[- ]?\d){9}(?!\d)", "[联系方式已脱敏]", value)
    value = re.sub(r"(?i)(?:https?://|www\.)\S+", "[链接已脱敏]", value)
    value = re.sub(r"(?i)(?:github|gitee|gitlab|linkedin)\s*[:：@]?\s*[\w./-]+", "[链接已脱敏]", value)
    value = re.sub(r"(?i)(微信|微信号|WeChat|QQ|QQ号)\s*[:：]?\s*[A-Za-z0-9_-]{4,}", r"\1：[联系方式已脱敏]", value)
    value = re.sub(r"(?<!\d)\d{7,12}(?!\d)", "[联系方式已脱敏]", value)
    value = re.sub(r"(?:19|20)\d{2}[年./-]\d{1,2}[月./-]\d{1,2}日?", "[个人信息已脱敏]", value)
    value = re.sub(r"(?:年龄|出生年月|出生日期|生日|籍贯|民族|性别|政治面貌)\s*[:：]?\s*[^|·；;,，]{1,18}", "[个人信息已脱敏]", value)
    value = re.sub(r"(?<!\d)\d{1,2}\s*岁(?!\d)", "[个人信息已脱敏]", value)
    value = re.sub(r"[\U00010000-\U0010ffff]", "", value)
    value = re.sub(r"\s+", " ", value).strip(" |,;`{}[]-")
    return value


def prepare(candidate: Candidate) -> list[str]:
    drop = re.compile(
        r"(?i)(?:联系方式|联系电话|手机号码|电子邮箱|邮箱地址|联系地址|现居住地|家庭住址|"
        r"点击下载|下载简历|扫码|二维码|复制微信|复制QQ|navigator\.|addEventListener|querySelector|"
        r"className|linear-gradient|rgba\(|sourceMappingURL|copyright|版权所有|ICP备|公网安备|"
        r"返回顶部|切换主题|菜单|导航|查看大图|点击任意|播放视频|暂无数据)"
    )
    weak = re.compile(r"(?i)^(?:个人简历|resume|home|about|contact|portfolio|blog|首页|关于|联系|作品|qq\.com|gmail\.com|outlook\.com|blue|cyan|steel)$")
    result: list[str] = []
    seen: set[str] = set()
    for raw in source_lines(candidate):
        value = redact(raw, candidate.names)
        key = re.sub(r"\W+", "", value).lower()
        symbols = len(re.findall(r"[{}\[\]<>=$`\\]", value))
        if (
            len(key) < 4
            or len(value) > 620
            or key in seen
            or drop.search(value)
            or weak.fullmatch(value)
            or value.count(":") > 12
            or symbols > max(4, len(value) // 18)
            or re.search(
                r"(?i)(?:npm run|node_modules|webpack|vite|function\s*\(|const\s+\w+|=>|<div|"
                r"useState\(|classList|document\.|window\.|return\s+\w|defaultData=|onClick=|"
                r"aria-label|fetchData|saveData|\.map\(|\.filter\(|\.length|assets/|static/|"
                r"font-size|border-radius|background-color|data:image|application/|__REACT|THREE\.|"
                r"uniform\s+vec|R3F:|logo:\{|if\s*\(typeof|reactFiber|reactProps|reactContainer|"
                r"reactEvents|reactListeners|reactMarker|reactResources|reactRetry|A React form)",
                value,
            )
            or re.search(r"(?i)(?:_state[A-Z]|\.state=|throw new TypeError|skills:\s*\[|level:\s*\d|textContent\s*=|parseInt\(|dataset\.|\.style\.|\.jpg\b|\.png\b|\.webp\b|\$\{[^}]+\}|点击开玩|头像图片)", value)
        ):
            continue
        seen.add(key)
        result.append(value)
    if len("".join(result)) < 150:
        raise ValueError(f"{candidate.number} 的有效履历内容过少：{len(result)} 条")
    return result[:150]


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
    section.top_margin = section.bottom_margin = Inches(0.62)
    section.left_margin = section.right_margin = Inches(0.72)
    section.header_distance = section.footer_distance = Inches(0.35)
    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(9.2)
    normal.paragraph_format.space_after = Pt(3)
    normal.paragraph_format.line_spacing = 1.12
    for name, size, before, after in (
        ("Heading 1", 14, 9, 4),
        ("Heading 2", 12, 7, 3),
        ("Heading 3", 10.5, 5, 2),
    ):
        style = doc.styles[name]
        style.font.name = "Calibri"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(size)
        style.font.bold = True
        style.font.color.rgb = RGBColor.from_string("2E74B5")
        style.paragraph_format.space_before = Pt(before)
        style.paragraph_format.space_after = Pt(after)
        style.paragraph_format.keep_with_next = True


def add_page_number(section) -> None:
    paragraph = section.footer.paragraphs[0]
    paragraph.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    run = paragraph.add_run("第 ")
    set_font(run, 8, "777777")
    field = OxmlElement("w:fldSimple")
    field.set(qn("w:instr"), "PAGE")
    run._r.addnext(field)
    set_font(paragraph.add_run(" 页"), 8, "777777")


def build(candidate: Candidate) -> Path:
    folder = OUTPUT / f"{candidate.number:02d}_匿名候选人"
    folder.mkdir(parents=True, exist_ok=True)
    doc = Document()
    configure(doc)
    doc.core_properties.title = "个人简历（已脱敏）"
    doc.core_properties.author = ""
    doc.core_properties.last_modified_by = ""
    title = doc.add_paragraph()
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    title.paragraph_format.space_after = Pt(2)
    set_font(title.add_run("个人简历（已脱敏）"), 20, "0B2545", True)
    subtitle = doc.add_paragraph()
    subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
    subtitle.paragraph_format.space_after = Pt(9)
    set_font(subtitle.add_run(candidate.role), 10.5, "555555")
    blocks = prepare(candidate)
    if not any(HEADINGS.fullmatch(re.sub(r"^\d+[.、 ]*", "", item).strip()) for item in blocks):
        doc.add_heading("履历概览", level=1)
    for block in blocks:
        normalized = re.sub(r"^\d+[.、 ]*", "", block).strip()
        if HEADINGS.fullmatch(normalized):
            doc.add_heading(normalized, level=1)
        else:
            paragraph = doc.add_paragraph(block)
            paragraph.paragraph_format.keep_together = False
    add_page_number(doc.sections[0])
    target = folder / f"{candidate.number:02d}_匿名候选人_已脱敏简历.docx"
    doc.save(target)
    return target


def convert_to_pdf(docx_paths: list[Path]) -> list[Path]:
    import win32com.client

    word = win32com.client.DispatchEx("Word.Application")
    word.Visible = False
    word.DisplayAlerts = 0
    pdfs: list[Path] = []
    try:
        for docx in docx_paths:
            pdf = docx.with_suffix(".pdf")
            document = word.Documents.Open(str(docx.resolve()), ReadOnly=True)
            try:
                document.ExportAsFixedFormat(str(pdf.resolve()), 17)
            finally:
                document.Close(False)
            pdfs.append(pdf)
    finally:
        word.Quit()
    return pdfs


def docx_text(path: Path) -> str:
    doc = Document(path)
    parts = [paragraph.text for paragraph in doc.paragraphs]
    parts.extend(cell.text for table in doc.tables for row in table.rows for cell in row.cells)
    return "\n".join(parts)


def pdf_text(path: Path) -> tuple[str, int]:
    reader = PdfReader(path)
    return "\n".join(page.extract_text() or "" for page in reader.pages), len(reader.pages)


def shingles(text: str, size: int = 5) -> set[str]:
    normalized = re.sub(r"\s+", "", text)
    return {normalized[i : i + size] for i in range(max(0, len(normalized) - size + 1))}


def jaccard(left: str, right: str) -> float:
    a, b = shingles(left), shingles(right)
    return len(a & b) / len(a | b) if a or b else 1.0


def render_pdfs(pdfs: list[Path]) -> None:
    RENDER.mkdir(parents=True, exist_ok=True)
    for pdf in pdfs:
        target = RENDER / pdf.parent.name
        target.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["pdftoppm", "-png", "-r", "110", str(pdf), str(target / "page")],
            check=True,
            capture_output=True,
        )


def audit(docx_paths: list[Path], pdfs: list[Path]) -> dict[str, object]:
    privacy = re.compile(
        r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|(?<!\d)1[3-9]\d{9}(?!\d)|"
        r"(?i:https?://|www\.|github\s*[:：@]|gitee\s*[:：@]|linkedin\s*[:：@])|"
        r"(?:微信号|QQ号|WeChat)\s*[:：]?\s*[A-Za-z0-9_-]{4,}"
    )
    records = []
    new_texts: dict[int, str] = {}
    for candidate, docx, pdf in zip(CANDIDATES, docx_paths, pdfs):
        dtext = docx_text(docx)
        ptext, pages = pdf_text(pdf)
        hits = sorted(set(match.group(0) for match in privacy.finditer(dtext + "\n" + ptext)))
        name_hits = sorted(name for name in candidate.names if re.search(re.escape(name), dtext + ptext, re.I))
        records.append(
            {
                "number": candidate.number,
                "role": candidate.role,
                "docx": str(docx),
                "pdf": str(pdf),
                "pages": pages,
                "characters": len(re.sub(r"\s+", "", dtext)),
                "docxPdfJaccard": round(jaccard(dtext, ptext), 6),
                "privacyHits": hits,
                "nameHits": name_hits,
            }
        )
        new_texts[candidate.number] = dtext

    internal = []
    numbers = sorted(new_texts)
    for offset, left in enumerate(numbers):
        for right in numbers[offset + 1 :]:
            internal.append((jaccard(new_texts[left], new_texts[right]), left, right))
    internal.sort(reverse=True)

    historical_paths = [
        path
        for path in ROOT.rglob("*.docx")
        if OUTPUT not in path.parents and ".codex-temp" not in path.parts
    ]
    historical = []
    for number, text in new_texts.items():
        best_score, best_path = 0.0, ""
        for path in historical_paths:
            try:
                score = jaccard(text, docx_text(path))
            except Exception:
                continue
            if score > best_score:
                best_score, best_path = score, str(path)
        historical.append((best_score, number, best_path))
    historical.sort(reverse=True)

    report = {
        "count": len(records),
        "records": records,
        "internalHighest": [
            {"score": round(score, 6), "left": left, "right": right}
            for score, left, right in internal[:10]
        ],
        "historicalHighest": [
            {"score": round(score, 6), "number": number, "path": path}
            for score, number, path in historical[:10]
        ],
        "totalPages": sum(item["pages"] for item in records),
        "allPdfReadable": all(item["pages"] >= 1 and item["characters"] >= 300 for item in records),
        "privacyClean": all(not item["privacyHits"] and not item["nameHits"] for item in records),
    }
    AUDIT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def main() -> None:
    if [candidate.number for candidate in CANDIDATES] != list(range(72, 102)):
        raise ValueError("候选人编号必须连续为 72–101")
    if len({candidate.folder for candidate in CANDIDATES}) != 30:
        raise ValueError("来源仓库重复")
    docx_paths = [build(candidate) for candidate in CANDIDATES]
    pdfs = convert_to_pdf(docx_paths)
    render_pdfs(pdfs)
    report = audit(docx_paths, pdfs)
    print(json.dumps({key: value for key, value in report.items() if key != "records"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

from __future__ import annotations

import hashlib
import html
import json
import math
import re
import urllib.request
from collections import Counter
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor
from pypdf import PdfReader


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "测试简历_全新3份_中国大陆"
STAGING = ROOT / ".resume_mainland3_search"
USER_AGENT = "Codex-Resume-Research"
TEXT_SUFFIXES = {
    ".txt", ".md", ".html", ".htm", ".jsx", ".tsx", ".js", ".ts",
    ".json", ".jsonl", ".csv", ".tex", ".xml", ".yaml", ".yml",
    ".java", ".py", ".ps1", ".vue", ".properties",
}
EXCLUDED_PARTS = {
    ".git", "node_modules", "target", "target-jd-final", "target-jd-v14",
    "BOOT-INF", "__pycache__", ".pytest_cache", OUTPUT.name, STAGING.name,
}
EXCLUDED_FILES = {
    Path(__file__).resolve(),
    ROOT / "docs/superpowers/specs/2026-08-19-three-mainland-computing-resumes-design.md",
    ROOT / "docs/superpowers/plans/2026-08-19-three-mainland-computing-resumes.md",
}


RESUMES = [
    {
        "id": "01",
        "folder": "01_秦慧圆_产品经理",
        "name": "秦慧圆",
        "category": "产品经理",
        "contact": "邮箱：2363355094@qq.com  |  GitHub：github.com/qinhuiyuan171-cell",
        "summary": "管理科学与工程硕士在读，具备产品、数据分析与校园运营经历。能够从用户反馈和业务数据中拆解需求，完成 PRD、交互与跨团队推进，并使用机器学习和智能语音技术支持产品判断。",
        "repo": "qinhuiyuan171-cell/hayley-resume",
        "commit": "9cbb08386d4ce212427916fbd8742f8c3242716d",
        "source_page": "https://github.com/qinhuiyuan171-cell/hayley-resume",
        "sources": [
            ("App.jsx", "https://raw.githubusercontent.com/qinhuiyuan171-cell/hayley-resume/9cbb08386d4ce212427916fbd8742f8c3242716d/src/App.jsx"),
            ("秦慧圆_原始公开简历.pdf", "https://raw.githubusercontent.com/qinhuiyuan171-cell/hayley-resume/9cbb08386d4ce212427916fbd8742f8c3242716d/public/downloads/hayley-qin-resume.pdf"),
        ],
        "identifiers": ["秦慧圆", "2363355094@qq.com", "qinhuiyuan171-cell", "hayley-resume"],
        "mainland_evidence": [
            "教育经历：上海理工大学、上海电力大学。",
            "实习经历：喜马拉雅、上海梯方教育、上海俺来也。",
        ],
        "sections": [
            ("核心能力", [
                ("产品能力", "需求分析与 PRD、原型与交互、跨团队推进", []),
                ("数据与技术", "Excel、XGBoost、LightGBM、SHAP、MFCC、CNN、大模型 API", []),
            ]),
            ("教育背景", [
                ("上海理工大学", "管理科学与工程硕士 | 2024.09 - 2027.06", ["专业前 15%，参与 2 项校级科研。"]),
                ("上海电力大学", "公共事业管理学士 | 2020.09 - 2024.06", ["GPA 3.6/4.0，获评优秀毕业生。"]),
            ]),
            ("实习经历", [
                ("喜马拉雅", "产品实习生 | 2026.04 - 2026.09", [
                    "独立负责 4 个从 0 到 1 模块，覆盖儿童 App 与智能硬件，其中 2 项已上线，相关满意度为 92%。",
                    "短号功能：完成 2-6 位短号输入、校验、优先呼叫与失败回落逻辑。",
                    "视频通话：设计 8 个以上状态节点的通话状态机，推动 App、小程序、故事机三端交互对齐。",
                    "陪伴能量：参考蚂蚁森林与微信运动设计激励体系，预计提升日均使用时长 15%。",
                    "硬件解绑：设计批量任务、权益回收与失败明细下载，单次支持 1000 条 SN。",
                ]),
                ("上海梯方教育", "数据分析实习生 | 2025.12 - 2026.04", [
                    "排查数据缺失与错误，完成数百条数据全量核对并沉淀校验规范，准确率达到 98% 以上。",
                    "使用 Excel 函数和透视表计算完成率与达标率，输出每日业务报表，相关点击率提升 12%。",
                ]),
                ("上海俺来也", "校园运营实习生 | 2021.09 - 2022.06", [
                    "基于 2000 余条日订单数据分析工作日与节假日餐流量，支撑配送与档口排班，预测准确率 85%。",
                    "优化配送路径使配送时长降低 18%；依据点击与转化数据调整页面布局和商品展示顺序，使转化率提升 7%。",
                ]),
            ]),
            ("项目经历", [
                ("动态失能风险预测", "机器学习 / CHARLS / SCI 研究成果", [
                    "构建累积共病负担和失能轨迹斜率，搭建 XGBoost、LightGBM 动态预测模型。",
                    "模型 AUC 为 0.7661，准确率提升 3.28%，并使用 SHAP 提供可解释性，用于老年人失能早期风险分层。",
                ]),
                ("智能语音情感系统", "MFCC / CNN / 大模型 API | 2025.06 - 2025.09", [
                    "使用 MFCC 与 CNN 识别 6 类核心情绪，识别准确率 0.85。",
                    "联动大模型 API，根据用户情绪动态切换音色、语速和语调，形成情感反馈闭环；成果包含 2 项软件著作权。",
                ]),
            ]),
        ],
    },
    {
        "id": "02",
        "folder": "02_郭力铭_数据分析",
        "name": "郭力铭",
        "category": "数据分析师",
        "contact": "所在地：江苏常州  |  电话：17586463895  |  邮箱：3192279568@qq.com  |  GitHub：github.com/dee790114-wq",
        "summary": "数学与应用数学本科背景的数据分析实践者，关注数据质量、业务问题拆解与可视化表达，具备 SQL、Python、Power BI、Excel、SPSS、数据建模及数据库测试环境实践。",
        "repo": "dee790114-wq/dee790114-wq.github.io",
        "commit": "275e0b45f2ddc89e4065ec446f92af0ab4214a4a",
        "source_page": "https://github.com/dee790114-wq/dee790114-wq.github.io",
        "sources": [
            ("index.html", "https://raw.githubusercontent.com/dee790114-wq/dee790114-wq.github.io/275e0b45f2ddc89e4065ec446f92af0ab4214a4a/index.html"),
            ("原始仓库.zip", "https://codeload.github.com/dee790114-wq/dee790114-wq.github.io/zip/275e0b45f2ddc89e4065ec446f92af0ab4214a4a"),
        ],
        "identifiers": ["郭力铭", "3192279568@qq.com", "17586463895", "dee790114-wq"],
        "mainland_evidence": [
            "所在地与教育经历：江苏常州、常州工学院。",
            "实习经历：江苏大展信息工程有限公司、海鹏（常州）商贸有限公司。",
        ],
        "sections": [
            ("专业技能", [
                ("SQL / MySQL", "查询、校验、数据建模与问题定位", []),
                ("Python", "Pandas、数据清洗、探索分析与自动化", []),
                ("Power BI", "数据模型、DAX、交互式经营看板", []),
                ("Excel / SPSS", "透视分析、查找模型、情景分析、统计分析与结果解释", []),
                ("数据建模", "星型模型、指标口径与数据质量检查", []),
            ]),
            ("实习经历", [
                ("江苏大展信息工程有限公司", "MySQL 数据库管理员实习生 | 2026.03 - 2026.06", [
                    "参与业务系统数据库基础管理、SQL 数据校验、测试环境维护与问题排查。",
                    "围绕多个业务场景编写与优化 SQL，执行数据查询、更新和一致性校验。",
                    "协助开发与测试团队定位数据差异，记录复现过程并推动问题闭环；维护测试环境、基础配置与备份，整理操作日志和数据核验报告。",
                ]),
                ("海鹏（常州）商贸有限公司", "国内电商运营实习生 | 2025.09 - 2025.11", [
                    "负责淘宝、拼多多店铺的运营数据整理、活动支持和商品信息维护。",
                    "建立流量、访客、转化、客单价和销售统计表，输出日报与周报；跟进促销活动配置与复盘。",
                    "分类整理买家咨询、评价和退换货反馈，为商品与服务优化提供依据。",
                ]),
            ]),
            ("项目经历", [
                ("供应链 OTIF 履约诊断", "Power BI / DAX / Data Modeling", ["复算 32,000 个订单、62,669 条明细，得到准时率 92.8%、足量率 86.8%、OTIF 80.5%，定位客户、城市与产品层面的履约缺口。"]),
                ("零售销售数据仓库", "SQL / MySQL / Power BI / 星型模型", ["通过 Bronze、Silver、Gold 三层模型统一客户、产品和订单数据；构建 48,000 个订单、81,789 条明细的国内零售模型，有效销售额 3,182.6 万元，毛利率 31.6%。"]),
                ("中国城市公开数据分析", "Python / Pandas / Power BI", ["整理南京、苏州、无锡、常州四市 2024 年统计公报，统一经济、人口和居民收支口径，完成 4 个城市、8 项核心及衍生指标的清洗和复算。"]),
                ("电商销售与利润分析", "Power BI / DAX / Python", ["分析 2025 年 36,000 笔国内电商订单，统一销售、折扣、退款、平台费用、履约成本与利润口径；净销售额 573.96 万元，利润 63.31 万元，利润率 11.0%，退款率 4.8%。"]),
                ("Excel 库存补货决策工具", "Excel / 库存分析 / 公式建模", ["将需求、采购提前期、服务水平和库存转化为安全库存、补货点与建议补货量；测算 5 个国内零售品类，其中 4 个需要补货，合计建议补货 485 件。"]),
            ]),
            ("教育背景", [
                ("常州工学院", "数学与应用数学本科 | 2022 - 2026", ["相关课程：数学分析、高等代数、解析几何、Python。"]),
            ]),
            ("其他经历", [
                ("运动会组织", "组织部长 | 2025.09 - 2025.12", ["负责统筹筹备、现场协调、成绩与赛后数据整理。"]),
                ("语言能力", "普通话二级甲等", []),
            ]),
        ],
    },
    {
        "id": "03",
        "folder": "03_叶其芳_软件测试",
        "name": "叶其芳",
        "category": "软件测试工程师",
        "contact": "电话：18501750936  |  邮箱：1562779519@qq.com  |  GitHub：github.com/yeqifang0",
        "summary": "拥有 8 年软件测试经验，覆盖 Web、App、H5、接口、性能、兼容性和自动化测试。可独立负责需求评审、测试设计、执行、缺陷闭环、报告与版本交付，并以 Python、Pytest、Playwright 和 Jenkins 建设自动化回归体系。",
        "repo": "yeqifang0/yeqifang0.github.io",
        "commit": "d5822a2a287ca39dda9f197507f58a077dfde9d4",
        "source_page": "https://github.com/yeqifang0/yeqifang0.github.io",
        "sources": [
            ("叶其芳_原始公开简历.docx", "https://raw.githubusercontent.com/yeqifang0/yeqifang0.github.io/d5822a2a287ca39dda9f197507f58a077dfde9d4/assets/downloads/%E5%8F%B6%E5%85%B6%E8%8A%B3-%E8%BD%AF%E4%BB%B6%E6%B5%8B%E8%AF%95%E5%B7%A5%E7%A8%8B%E5%B8%88-%E7%AE%80%E5%8E%86.docx"),
            ("原始仓库.zip", "https://codeload.github.com/yeqifang0/yeqifang0.github.io/zip/d5822a2a287ca39dda9f197507f58a077dfde9d4"),
        ],
        "identifiers": ["叶其芳", "1562779519@qq.com", "18501750936", "yeqifang0"],
        "mainland_evidence": [
            "教育经历：安徽工程科技学院计算机科学与技术本科。",
            "工作经历：上海赛潍网络科技有限公司、砀郡信息技术（上海）有限公司、上海速册商务咨询有限公司。",
            "联系方式：公开页面提供中国大陆手机号码。",
        ],
        "sections": [
            ("专业技能", [
                ("功能与兼容性测试", "等价类、边界值、场景法、错误推测法；iOS/Android 多机型、多系统与弱网场景", []),
                ("接口与性能测试", "Postman、Requests、JMeter；参数、状态码、响应结构、TPS、P50/P95/P99、错误率与吞吐量", []),
                ("自动化测试", "Python、Pytest、Playwright、Page Object Model、数据驱动、多环境配置", []),
                ("工程与质量工具", "Jenkins、MeterSphere、Allure、JUnit XML、Jira、Charles、Fiddler、DBeaver、Navicat、MySQL、Grafana、Docker", []),
            ]),
            ("工作经历", [
                ("上海赛潍网络科技有限公司", "测试工程师 | 2022.04 - 2026.06", [
                    "负责 Weee! 北美生鲜电商 App/Web/H5，以及 ERP、客服工作台和后台管理系统的测试。",
                    "参与需求评审和业务拆解，完成全端功能、场景、多机型兼容性、接口与数据库一致性测试，闭环管理缺陷并输出测试报告。",
                    "使用 Python 开发接口和 UI 自动化脚本，搭建版本回归体系；通过 Charles、Postman、DBeaver、Grafana 定位前后端和数据问题。",
                ]),
                ("砀郡信息技术（上海）有限公司", "测试工程师 | 2020.10 - 2022.04", [
                    "负责云因 CRM Web 系统和白藤营销 App 的功能、接口、弱网兼容性与数据校验。",
                    "使用 XMind 梳理业务逻辑，借助 Fiddler 抓包分析，使用 Postman 和 SQL 校验接口及业务数据，输出完整测试报告。",
                ]),
                ("上海速册商务咨询有限公司", "测试助理 | 2018.08 - 2020.10", [
                    "负责乐享服务员工自助理赔 App，依据需求覆盖正常、异常与边界场景。",
                    "使用 Postman、Fiddler、MySQL 和日志平台完成接口、断点调试、参数篡改、数据校验和线上缺陷定位。",
                ]),
            ]),
            ("项目经历", [
                ("Weee! 北美生鲜电商", "App / Web / H5 | 2025.10 - 2026.06", [
                    "负责迭代需求评审、业务风险识别、全端功能与兼容性测试、接口和数据库校验、自动化回归及版本测试报告。",
                ]),
                ("ERP 管理系统与客服工作台", "Web / ERP | 2022.04 - 2026.06", [
                    "覆盖采购、销售、库存、仓储、财务、人事、审批和报表模块；开发接口自动化脚本并通过 Grafana 日志定位后台异常。",
                ]),
                ("Python 接口自动化测试框架", "Pytest / Requests / MeterSphere / Jenkins | 2023", [
                    "采用配置、工具、API 封装、测试用例和报告分层，支持数据驱动、多环境、Allure 报告、Jenkins 参数化执行及企业微信结果推送。",
                ]),
                ("Playwright UI 自动化体系", "Playwright / Pytest / POM | 2023", [
                    "覆盖电商登录、购物车与下单流程，支持浏览器会话管理、页面对象分层、多账号参数化和 Jenkins 定时执行。",
                ]),
                ("电商系统全链路测试方案", "功能 / 接口 / 兼容性 / 性能 / 异常测试 | 2025 - 2026", [
                    "覆盖用户旅程、核心接口、设备矩阵、促销并发基线、弱网断网、支付超时、库存超卖、数据冲突及线上巡检。",
                ]),
            ]),
            ("教育背景", [
                ("安徽工程科技学院", "计算机科学与技术本科 | 2014 - 2018", []),
            ]),
        ],
    },
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def request_bytes(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def download_sources() -> dict[str, list[dict[str, str | int]]]:
    STAGING.mkdir(parents=True, exist_ok=True)
    records = {}
    for item in RESUMES:
        folder = STAGING / item["folder"]
        folder.mkdir(parents=True, exist_ok=True)
        item_records = []
        for filename, url in item["sources"]:
            target = folder / filename
            target.write_bytes(request_bytes(url))
            item_records.append({
                "file": str(target.relative_to(ROOT)),
                "url": url,
                "bytes": target.stat().st_size,
                "sha256": sha256(target),
            })
        records[item["name"]] = item_records
    return records


def decode_text(data: bytes) -> str:
    for encoding in ("utf-8", "utf-8-sig", "utf-16", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="ignore")


def docx_text(path: Path) -> str:
    document = Document(path)
    lines = [paragraph.text for paragraph in document.paragraphs]
    for table in document.tables:
        for row in table.rows:
            lines.append(" | ".join(cell.text for cell in row.cells))
    return "\n".join(lines)


def pdf_text(path: Path) -> str:
    return "\n".join(page.extract_text() or "" for page in PdfReader(str(path)).pages)


def visible_text(raw: str) -> str:
    raw = re.sub(r"(?is)<(style|script).*?>.*?</\1>", " ", raw)
    raw = re.sub(r"(?s)<[^>]+>", " ", raw)
    raw = html.unescape(raw)
    return re.sub(r"\s+", " ", raw).strip()


def readable_text(path: Path) -> str:
    try:
        if path.suffix.lower() == ".docx":
            return docx_text(path)
        if path.suffix.lower() == ".pdf":
            return pdf_text(path)
        if path.suffix.lower() in TEXT_SUFFIXES:
            text = decode_text(path.read_bytes())
            return visible_text(text) if path.suffix.lower() in {".html", ".htm"} else text
    except Exception:
        return ""
    return ""


def candidate_body(item: dict) -> str:
    parts = [item["name"], item["category"], item["contact"], item["summary"]]
    for heading, entries in item["sections"]:
        parts.append(heading)
        for title, subtitle, bullets in entries:
            parts.extend([title, subtitle, *bullets])
    return "\n".join(parts)


def normalized_tokens(text: str) -> list[str]:
    return re.findall(r"[a-z0-9_+#.]+|[\u4e00-\u9fff]", text.lower())


def cosine_similarity(left: str, right: str) -> float:
    left_counter = Counter(normalized_tokens(left))
    right_counter = Counter(normalized_tokens(right))
    if not left_counter or not right_counter:
        return 0.0
    dot = sum(value * right_counter.get(token, 0) for token, value in left_counter.items())
    left_size = math.sqrt(sum(value * value for value in left_counter.values()))
    right_size = math.sqrt(sum(value * value for value in right_counter.values()))
    return dot / (left_size * right_size)


def iter_workspace_files():
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.resolve() in EXCLUDED_FILES:
            continue
        if any(part in EXCLUDED_PARTS for part in path.parts):
            continue
        if path.suffix.lower() in TEXT_SUFFIXES | {".docx", ".pdf"}:
            yield path


def deduplicate() -> dict:
    candidates = {item["name"]: candidate_body(item) for item in RESUMES}
    results = {
        item["name"]: {
            "identifiers": {identifier: [] for identifier in item["identifiers"]},
            "maxCosineSimilarity": 0.0,
            "mostSimilarFile": None,
            "normalizedBodySha256": hashlib.sha256(
                " ".join(normalized_tokens(candidates[item["name"]])).encode("utf-8")
            ).hexdigest(),
        }
        for item in RESUMES
    }
    scanned = 0
    for path in iter_workspace_files():
        text = readable_text(path)
        if not text:
            continue
        scanned += 1
        relative = str(path.relative_to(ROOT))
        lower = text.lower()
        for item in RESUMES:
            record = results[item["name"]]
            for identifier in item["identifiers"]:
                if identifier.lower() in lower:
                    record["identifiers"][identifier].append(relative)
            similarity = cosine_similarity(candidates[item["name"]], text)
            if similarity > record["maxCosineSimilarity"]:
                record["maxCosineSimilarity"] = round(similarity, 6)
                record["mostSimilarFile"] = relative
    failures = []
    for name, record in results.items():
        hits = {key: value for key, value in record["identifiers"].items() if value}
        record["strongIdentifierHitCount"] = sum(len(value) for value in hits.values())
        record["passed"] = record["strongIdentifierHitCount"] == 0 and record["maxCosineSimilarity"] < 0.9
        if not record["passed"]:
            failures.append(name)
    return {
        "scope": "整个工作区内可读文本、DOCX 与 PDF；排除当前任务的暂存、输出、生成脚本及依赖/构建目录",
        "scannedReadableFiles": scanned,
        "threshold": {"strongIdentifierHits": 0, "maxCosineSimilarityExclusive": 0.9},
        "candidates": results,
        "passed": not failures,
        "failedCandidates": failures,
    }


def set_font(run, size: float, bold: bool = False, color: str = "222222") -> None:
    run.font.name = "Microsoft YaHei"
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    run.font.size = Pt(size)
    run.bold = bold
    run.font.color.rgb = RGBColor.from_string(color)


def shade(cell, fill: str) -> None:
    properties = cell._tc.get_or_add_tcPr()
    element = OxmlElement("w:shd")
    element.set(qn("w:fill"), fill)
    properties.append(element)


def configure(document: Document) -> None:
    section = document.sections[0]
    section.top_margin = Cm(1.35)
    section.bottom_margin = Cm(1.35)
    section.left_margin = Cm(1.7)
    section.right_margin = Cm(1.7)
    normal = document.styles["Normal"]
    normal.font.name = "Microsoft YaHei"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(9.3)
    normal.paragraph_format.space_after = Pt(2.3)
    normal.paragraph_format.line_spacing = 1.05
    heading = document.styles["Heading 1"]
    heading.font.name = "Microsoft YaHei"
    heading._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    heading.font.size = Pt(12.5)
    heading.font.bold = True
    heading.font.color.rgb = RGBColor(22, 73, 88)
    heading.paragraph_format.space_before = Pt(6)
    heading.paragraph_format.space_after = Pt(2.5)


def add_entry(document: Document, title: str, subtitle: str, bullets: list[str]) -> None:
    table = document.add_table(rows=1, cols=2)
    table.autofit = False
    table.columns[0].width = Cm(8.1)
    table.columns[1].width = Cm(9.6)
    left, right = table.rows[0].cells
    left.text = ""
    right.text = ""
    set_font(left.paragraphs[0].add_run(title), 9.8, True, "164958")
    right.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT
    set_font(right.paragraphs[0].add_run(subtitle), 8.5, False, "555555")
    for cell in (left, right):
        for paragraph in cell.paragraphs:
            paragraph.paragraph_format.space_after = Pt(0)
    for bullet in bullets:
        paragraph = document.add_paragraph(style="List Bullet")
        paragraph.paragraph_format.left_indent = Cm(0.45)
        paragraph.paragraph_format.first_line_indent = Cm(-0.2)
        set_font(paragraph.add_run(bullet), 9.0)


def build_resume(item: dict) -> Path:
    folder = OUTPUT / item["folder"]
    folder.mkdir(parents=True, exist_ok=True)
    document = Document()
    configure(document)
    banner = document.add_table(rows=1, cols=1)
    cell = banner.cell(0, 0)
    shade(cell, "164958")
    cell.text = ""
    name = cell.paragraphs[0]
    name.alignment = WD_ALIGN_PARAGRAPH.CENTER
    set_font(name.add_run(item["name"]), 21, True, "FFFFFF")
    role = cell.add_paragraph()
    role.alignment = WD_ALIGN_PARAGRAPH.CENTER
    set_font(role.add_run(item["category"]), 10.5, True, "DCEDEF")
    contact = document.add_paragraph()
    contact.alignment = WD_ALIGN_PARAGRAPH.CENTER
    contact.paragraph_format.space_before = Pt(4)
    set_font(contact.add_run(item["contact"]), 8.1, False, "555555")
    document.add_heading("个人概述", level=1)
    set_font(document.add_paragraph().add_run(item["summary"]), 9.3)
    for heading, entries in item["sections"]:
        document.add_heading(heading, level=1)
        for title, subtitle, bullets in entries:
            add_entry(document, title, subtitle, bullets)
    document.add_heading("来源与核验说明", level=1)
    for evidence in item["mainland_evidence"]:
        paragraph = document.add_paragraph(style="List Bullet")
        set_font(paragraph.add_run(evidence), 8.6)
    paragraph = document.add_paragraph(style="List Bullet")
    set_font(paragraph.add_run(f"公开来源：{item['source_page']}，固定版本 {item['commit']}。"), 8.6)
    paragraph = document.add_paragraph(style="List Bullet")
    set_font(paragraph.add_run("仅按公开资料整理和统一排版；不据此推断国籍，未补写原资料之外的经历或成果。"), 8.6)
    target = folder / f"{item['id']}_{item['name']}_{item['category']}_中文完整简历.docx"
    document.core_properties.title = f"{item['name']} - {item['category']}中文完整简历"
    document.core_properties.subject = "全新中国大陆背景计算机相关简历"
    document.core_properties.comments = f"Source: {item['source_page']} @ {item['commit']}"
    document.save(target)
    return target


def convert_to_pdf(paths: list[Path]) -> list[Path]:
    import win32com.client

    word = win32com.client.DispatchEx("Word.Application")
    word.Visible = False
    word.DisplayAlerts = 0
    pdfs = []
    try:
        for path in paths:
            pdf = path.with_suffix(".pdf")
            document = word.Documents.Open(str(path.resolve()), ReadOnly=True)
            try:
                document.ExportAsFixedFormat(str(pdf.resolve()), 17)
            finally:
                document.Close(False)
            pdfs.append(pdf)
    finally:
        word.Quit()
    return pdfs


def copy_sources(records: dict[str, list[dict[str, str | int]]]) -> None:
    for item in RESUMES:
        folder = OUTPUT / item["folder"] / "原始公开资料"
        folder.mkdir(parents=True, exist_ok=True)
        for record in records[item["name"]]:
            source = ROOT / str(record["file"])
            (folder / source.name).write_bytes(source.read_bytes())


def write_reports(source_records: dict, dedup: dict) -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    qualification = {
        "statement": "公开资料显示三名候选人具有中国大陆教育、工作或所在地背景；本核验不推断国籍。",
        "candidates": [
            {
                "name": item["name"],
                "role": item["category"],
                "sourcePage": item["source_page"],
                "repository": item["repo"],
                "commit": item["commit"],
                "mainlandEvidence": item["mainland_evidence"],
                "requiredSections": ["联系方式", "教育背景", "工作或实习经历", "项目经历", "技能"],
                "complete": True,
                "sourceFiles": source_records[item["name"]],
            }
            for item in RESUMES
        ],
    }
    (OUTPUT / "候选核验.json").write_text(json.dumps(qualification, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUTPUT / "去重核验.json").write_text(json.dumps(dedup, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# 三份全新计算机相关简历来源与核验说明",
        "",
        "公开资料显示三名候选人具有中国大陆教育、工作或所在地背景。本说明不推断或宣称候选人国籍。",
        "",
        "| 候选人 | 方向 | 大陆背景证据 | 强标识历史命中 | 最高正文相似度 | 来源 |",
        "|---|---|---|---:|---:|---|",
    ]
    for item in RESUMES:
        record = dedup["candidates"][item["name"]]
        evidence = "；".join(item["mainland_evidence"])
        lines.append(
            f"| {item['name']} | {item['category']} | {evidence} | "
            f"{record['strongIdentifierHitCount']} | {record['maxCosineSimilarity']:.6f} | "
            f"[{item['repo']}]({item['source_page']}) |"
        )
    lines.extend([
        "",
        "## 制作原则",
        "",
        "- 每份均包含联系方式、教育、工作或实习、项目和技能。",
        "- 姓名、邮箱、手机号、GitHub 用户名或仓库标识均对当前任务之外的工作区文件执行检索。",
        "- 原始资料按固定提交版本保存，中文成品只做忠实整理和统一排版。",
        "- 当前任务暂存、输出、生成脚本及依赖/构建目录不作为历史样本参与去重。",
    ])
    (OUTPUT / "来源与核验说明.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    source_records = download_sources()
    dedup = deduplicate()
    if not dedup["passed"]:
        raise RuntimeError("候选去重未通过：" + ", ".join(dedup["failedCandidates"]))
    paths = [build_resume(item) for item in RESUMES]
    copy_sources(source_records)
    write_reports(source_records, dedup)
    pdfs = convert_to_pdf(paths)
    for path in paths + pdfs:
        print(path)


if __name__ == "__main__":
    main()

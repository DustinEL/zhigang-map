from __future__ import annotations

import io
import json
import re
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SEARCH = ROOT / ".codex-temp" / "resume_search_100.json"
OUTPUT = ROOT / ".codex-temp" / "resume_72_101_sources"

SELECTED_REPOS = {
    "aaa030226/lishengyang-resume",
    "yigong2333/lyd",
    "xiaolu-fzu/personal-website",
    "haipihuang/haipihuang.github.io",
    "jjy031102/resume",
    "0413one/Resume",
    "XuanRuiMu/XuanRuiMuResume",
    "yunyufeiwei/yunyufeiwei.github.io",
    "YoungYang-GTHB/YoungYang-GTHB.github.io",
    "wangzihao2026/wangzihao2026.github.io",
    "Heidy008/-",
    "2132133232342/resume",
    "trna171/my-resume",
    "skywalker23241/skywalker23241.github.io",
    "ooo6873/ooo6873.github.io",
    "jamil7184-blip/li-resume",
    "xie5623/MyResume",
    "wxhou/personal-resume",
    "luojingjie666/Personal-resume",
    "2427681280/resume",
    "YunYustudio/personal_resume_YunYu",
    "xunmeng2002/Resume",
    "8c4yh8f2mb-ctrl/sunmingkai-resume",
    "8c4yh8f2mb-ctrl/xuyingfei-resume",
    "Bhfail/Bhfail",
    "expolrer/HuangHongwei.github.io",
    "zhanghongbin12/my-resume",
    "Hachi0906/personal-website",
    "MadarameHu/MadarameHu.github.io",
    "d15035520862-art/resume",
    "linpear-jelly/Jelly",
    "Joyhanz-bot/portfolio",
    "pengzexi79-ops/interactive-resume",
    "XuemingW/XuemingW.github.io",
    "tzbkk/cv-hxue",
    "Maosinger/Maosinger.github.io",
    "wangsy615/jianli",
    "OHYRR/portfolio-R",
    "jiostrider/gsz",
    "Jackzhu-90/Jackzhu-90.github.io",
    "Greensdrobo/greens-portfolio",
    "Pekka-archer/introduction",
    "Pekka-archer/intro",
    "zhjojo/zhangbing-3d-resume",
    "Laughing88/Laughing88.github.io",
    "SoNowYouSeeXZH/My-Cat-FuZai",
    "jiangdong678/-4.0",
    "Radiant303/Radiant303.github.io",
    "1208826038/embedded-skill-notes",
}

EXTRA_REPOS = [
    {"full_name": "ryankongnewkingworld/resume", "description": "孔令军的个人简历：企业 AI 转型与 AI 解决方案", "default_branch": "main"},
    {"full_name": "SeasonWen/LIU_HUALIN_PORTFOLIO_V3", "description": "刘华林个人作品集与简历", "default_branch": "main"},
    {"full_name": "BH2-4/resume-static", "description": "张书源个人简历站", "default_branch": "main"},
    {"full_name": "Lyben888/resume", "description": "刘洋宾交互式个人简历", "default_branch": "main"},
    {"full_name": "SaoBaiA/sibai-cloud-resume", "description": "蒋超喜个人简历网站", "default_branch": "main"},
    {"full_name": "binbinao/resume", "description": "Robin Ji 工业 AI 基础设施架构师简历", "default_branch": "main"},
    {"full_name": "hhy077/resume", "description": "霍华翔个人简历", "default_branch": "main"},
    {"full_name": "Jesko23/jesko23-portfolio", "description": "郑子杰 AI 产品经理个人简历", "default_branch": "main"},
    {"full_name": "ZJU-Jason/ZJU-Jason.github.io", "description": "周鹏个人简历网站", "default_branch": "main"},
    {"full_name": "lipzzllip/lipzzllip.github.io", "description": "赵子麟 AI 应用研发个人简历", "default_branch": "main"},
]

TEXT_SUFFIXES = {
    ".html", ".htm", ".md", ".txt", ".json", ".js", ".jsx", ".ts", ".tsx",
    ".vue", ".yaml", ".yml", ".xml", ".css",
}
DOCUMENT_SUFFIXES = {".pdf", ".docx"}
SKIP_PARTS = {"node_modules", ".git", "dist", "build", "coverage", "vendor"}
SECTION_PATTERNS = {
    "education": r"教育(?:背景|经历)|院校|学校|大学|学院|学历|本科|硕士|博士",
    "work": r"工作经历|实习经历|职业经历|任职|公司|企业|事务所|工作经验",
    "project": r"项目经历|项目经验|项目展示|作品集|核心项目|代表项目",
    "skill": r"专业技能|技术栈|技能清单|核心能力|擅长|工具",
    "result": r"成果|奖项|荣誉|获奖|专利|论文|证书|业绩|提升|增长|一等奖|二等奖|三等奖",
}


def safe_member(name: str, size: int) -> bool:
    path = Path(name)
    if size > 3_000_000 or any(part.lower() in SKIP_PARTS for part in path.parts):
        return False
    return path.suffix.lower() in TEXT_SUFFIXES | DOCUMENT_SUFFIXES


def fetch_archive(full_name: str, branch: str) -> bytes:
    url = f"https://codeload.github.com/{full_name}/zip/refs/heads/{branch}"
    request = urllib.request.Request(url, headers={"User-Agent": "Codex resume research"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()


def decode_text(data: bytes) -> str:
    for encoding in ("utf-8", "utf-8-sig", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            pass
    return data.decode("utf-8", errors="ignore")


def extract_repo(repo: dict[str, object]) -> dict[str, object]:
    full_name = str(repo["full_name"])
    branch = str(repo.get("default_branch") or "main")
    target = OUTPUT / full_name.replace("/", "__")
    target.mkdir(parents=True, exist_ok=True)
    screening = target / "_screening.json"
    if screening.exists():
        return json.loads(screening.read_text(encoding="utf-8"))
    try:
        archive = fetch_archive(full_name, branch)
    except urllib.error.HTTPError as exc:
        if exc.code == 404 and branch != "master":
            branch = "master"
            archive = fetch_archive(full_name, branch)
        else:
            raise

    combined: list[str] = []
    files: list[str] = []
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        for item in zf.infolist():
            if item.is_dir() or not safe_member(item.filename, item.file_size):
                continue
            relative = Path(*Path(item.filename).parts[1:])
            if not relative.parts:
                continue
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            data = zf.read(item)
            destination.write_bytes(data)
            files.append(relative.as_posix())
            if relative.suffix.lower() in TEXT_SUFFIXES:
                combined.append(decode_text(data))

    text = "\n".join(combined)
    compact = re.sub(r"\s+", " ", text)
    sections = {name: bool(re.search(pattern, compact, re.I)) for name, pattern in SECTION_PATTERNS.items()}
    chinese_count = len(re.findall(r"[\u4e00-\u9fff]", compact))
    years = sorted(set(re.findall(r"(?:19|20)\d{2}", compact)))
    score = sum(sections.values()) + int(chinese_count >= 500) + int(len(years) >= 2)
    record = {
        "full_name": full_name,
        "source_url": f"https://github.com/{full_name}",
        "description": repo.get("description"),
        "branch": branch,
        "files": files,
        "chinese_count": chinese_count,
        "years": years,
        "sections": sections,
        "score": score,
    }
    screening.write_text(
        json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return record


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    repos = json.loads(SEARCH.read_text(encoding="utf-8"))
    chosen = [repo for repo in repos if repo["full_name"] in SELECTED_REPOS] + EXTRA_REPOS
    results = []
    with ThreadPoolExecutor(max_workers=6) as executor:
        futures = {executor.submit(extract_repo, repo): repo for repo in chosen}
        for index, future in enumerate(as_completed(futures), start=1):
            repo = futures[future]
            try:
                record = future.result()
                results.append(record)
                print(
                    f"[{index:02d}/{len(chosen):02d}] {record['full_name']} "
                    f"score={record['score']} zh={record['chinese_count']} files={len(record['files'])}",
                    flush=True,
                )
            except Exception as exc:  # Keep screening the remaining public sources.
                print(f"[{index:02d}/{len(chosen):02d}] {repo['full_name']} ERROR {exc}", flush=True)
    (OUTPUT / "screening_results.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
    )


if __name__ == "__main__":
    main()

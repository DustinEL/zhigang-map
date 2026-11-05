from __future__ import annotations

import json
import http.client
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SEARCH_RESULTS = ROOT / ".codex-temp" / "resume_search_100.json"
OUTPUT = ROOT / ".codex-temp" / "resume_41_71_fetched"

SKIP_PARTS = {
    ".git",
    ".github",
    ".next",
    "dist",
    "build",
    "node_modules",
    "vendor",
    "coverage",
    "assets",
    "images",
    "fonts",
}
TEXT_SUFFIXES = {".html", ".md", ".vue", ".tsx", ".jsx", ".ts", ".js", ".json"}
PREFERRED = re.compile(
    r"(?i)(?:resume|profile|about|experience|education|project|portfolio|data|index|app|home)"
)


def request_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "Codex-Resume-Research"})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)


def request_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "Codex-Resume-Research"})
    with urllib.request.urlopen(req, timeout=8) as response:
        charset = response.headers.get_content_charset() or "utf-8"
        return response.read().decode(charset, errors="ignore")


def score_path(path: str, size: int) -> tuple[int, int]:
    lower = path.lower()
    name = Path(lower).name
    score = 0
    if PREFERRED.search(path):
        score += 6
    if name in {"index.html", "readme.md", "app.vue", "page.tsx", "data.ts", "data.js"}:
        score += 5
    if "/src/" in f"/{lower}" or "/docs/" in f"/{lower}":
        score += 2
    if name in {"package.json", "tsconfig.json"} or name.endswith(".config.js"):
        score -= 8
    return score, min(size, 300_000)


def eligible(path: str, size: int) -> bool:
    parts = {part.lower() for part in Path(path).parts}
    if parts & SKIP_PARTS:
        return False
    if Path(path).suffix.lower() not in TEXT_SUFFIXES:
        return False
    if size < 200 or size > 500_000:
        return False
    return True


def fetch_repo(repo: dict) -> int:
    full_name = repo["full_name"]
    branch = repo.get("default_branch") or "main"
    tree_url = (
        f"https://api.github.com/repos/{full_name}/git/trees/"
        f"{urllib.parse.quote(branch, safe='')}?recursive=1"
    )
    tree = request_json(tree_url).get("tree", [])
    choices = [
        item
        for item in tree
        if item.get("type") == "blob" and eligible(item["path"], item.get("size", 0))
    ]
    choices.sort(
        key=lambda item: score_path(item["path"], item.get("size", 0)), reverse=True
    )

    repo_dir = OUTPUT / full_name.replace("/", "__")
    repo_dir.mkdir(parents=True, exist_ok=True)
    saved = 0
    for item in choices[:6]:
        path = item["path"]
        raw_url = (
            f"https://raw.githubusercontent.com/{full_name}/"
            f"{urllib.parse.quote(branch, safe='')}/{urllib.parse.quote(path)}"
        )
        try:
            text = request_text(raw_url)
        except (urllib.error.URLError, TimeoutError, http.client.RemoteDisconnected):
            continue
        if len(text.strip()) < 200:
            continue
        target = repo_dir / path.replace("/", "__")
        target.write_text(text, encoding="utf-8")
        saved += 1
    return saved


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    candidates = json.loads(SEARCH_RESULTS.read_text(encoding="utf-8"))[52:]
    for repo in candidates:
        target = OUTPUT / repo["full_name"].replace("/", "__")
        if target.exists():
            continue
        try:
            saved = fetch_repo(repo)
            print(f"{repo['full_name']}: {saved}")
        except urllib.error.HTTPError as exc:
            print(f"{repo['full_name']}: HTTP {exc.code}")
        except (
            urllib.error.URLError,
            TimeoutError,
            http.client.RemoteDisconnected,
            json.JSONDecodeError,
        ) as exc:
            print(f"{repo['full_name']}: {exc}")
        time.sleep(0.2)


if __name__ == "__main__":
    main()

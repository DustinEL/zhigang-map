"""Create two independent blank annotation files from a frozen unlabeled manifest."""
import argparse, json
from pathlib import Path

def main():
    p = argparse.ArgumentParser(); p.add_argument("--manifest", type=Path, required=True); p.add_argument("--output", type=Path, required=True); a = p.parse_args()
    m = json.loads(a.manifest.read_text(encoding="utf-8")); a.output.mkdir(parents=True, exist_ok=True)
    rows = [{"caseId": c["caseId"], "resumeId": c["resumeId"], "roleId": c["roleId"], "label": "", "evidence": [], "reasonCodes": [], "comments": ""} for c in m["cases"]]
    for name in ("annotator-A.json", "annotator-B.json"):
        (a.output / name).write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (a.output / "annotation-instructions.md").write_text(
        "# 独立双标说明\n\n两名标注者分别填写 A/B 文件，不查看系统预测或对方结果。每条填写 gap/near/ready、原文证据和原因码。完成后再提交第三人仲裁分歧。\n",
        encoding="utf-8")
    print(f"created {len(rows)} blank annotation rows")

if __name__ == "__main__": main()

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "outputs/2026-08-28-independent-30-72-101-annotation"
MANIFEST = ROOT / "outputs/diagnostics/final-independent-30-72-101-frozen-manifest.json"

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def by_case(items):
    return {item["caseId"]: item for item in items}

def main():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    a_path = OUT / "annotator-A.json"; b_path = OUT / "annotator-B.json"
    a = by_case(json.loads(a_path.read_text(encoding="utf-8")))
    b = by_case(json.loads(b_path.read_text(encoding="utf-8")))
    items = []
    for case in manifest["cases"]:
        left, right = a[case["caseId"]], b[case["caseId"]]
        if left["label"] == right["label"]:
            continue
        items.append({
            **{key: case[key] for key in ("caseId", "resumeId", "roleId", "roleCode", "roleName", "roleVersion")},
            "annotatorA": {key: left[key] for key in ("label", "evidence", "reasonCodes", "comments")},
            "annotatorB": {key: right[key] for key in ("label", "evidence", "reasonCodes", "comments")},
            "adjudicatedLabel": "",
            "adjudicatedEvidence": [],
            "adjudicationReason": "",
            "adjudicatorComments": "",
        })
    result = {
        "schemaVersion": "match-adjudication-v1.0",
        "datasetId": manifest["datasetId"], "datasetSha256": manifest["datasetSha256"],
        "manifestSha256": sha(MANIFEST), "annotationASha256": sha(a_path),
        "annotationBSha256": sha(b_path),
        "allowedLabels": ["需先补关键能力", "接近可申请", "可申请实习/校招"],
        "instructions": ["只仲裁本文件中的分歧 case", "依据简历原文和岗位要求，不查看系统预测", "每条填写 adjudicatedLabel、adjudicatedEvidence 和 adjudicationReason", "不得为了补足类别数量而修改标签"],
        "disagreementCount": len(items), "items": items,
    }
    target = OUT / "adjudication.json"
    if target.exists():
        raise RuntimeError(f"refusing to overwrite {target}")
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"created {len(items)} adjudication items: {target}")

if __name__ == "__main__":
    main()

import csv
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "测试简历_正式盲测候选池_2026-08-26后新增"
OUTPUT = ROOT / "outputs/diagnostics/intakes/final-independent-30-72-101-v2"
CARDS = ROOT / "backend/src/main/resources/matching/role-capability-cards-v2.json"

def text_sha(path):
    import importlib.util
    p = Path(__file__).with_name("finalize-match-blind-intake.py")
    spec = importlib.util.spec_from_file_location("fin", p)
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    return mod.normalized_text_sha256(mod.extract_text(path))

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    folders = []
    for path in SOURCE.iterdir():
        m = re.fullmatch(r"(\d+)_匿名候选人", path.name)
        if m and 72 <= int(m.group(1)) <= 101:
            docs = sorted(path.glob("*.docx"))
            if len(docs) != 1:
                raise RuntimeError(f"candidate {m.group(1)} DOCX count={len(docs)}")
            folders.append((int(m.group(1)), docs[0]))
    folders.sort()
    if [n for n, _ in folders] != list(range(72, 102)):
        raise RuntimeError(f"candidate ids incomplete: {[n for n, _ in folders]}")
    roles = json.loads(CARDS.read_text(encoding="utf-8"))["cards"]
    role_ids = [2, 3, 4, 5, 7, 9, 12]
    roles = [r for r in roles if int(r["roleId"]) in role_ids]
    OUTPUT.mkdir(parents=True, exist_ok=True)
    fields = ["resumeId","candidateKey","sourcePath","contentSha256","normalizedTextSha256","duplicateGroup","split","consentOrLicense","piiRedacted","collectedBy","collectedAt","notes"]
    resumes = []
    for idx, (number, doc) in enumerate(folders, 1):
        rel = doc.relative_to(ROOT).as_posix()
        resumes.append({"resumeId": f"M72101-{idx:03d}", "candidateKey": f"blind-72-101-{number:03d}", "sourcePath": rel, "contentSha256": sha(doc), "normalizedTextSha256": text_sha(doc), "duplicateGroup": f"blind-72-101-{number:03d}", "split":"blind", "consentOrLicense":"user-provided-public-source", "piiRedacted":"true", "collectedBy":"user-provided-public-source", "collectedAt":"2026-08-28T00:00:00Z", "notes":"72-101 formal blind pool; DOCX registered, paired PDF retained in source pool"})
    cases = []
    for resume in resumes:
        for role in roles:
            n = len(cases) + 1
            cases.append({"caseId":f"M72101-C-{n:04d}","resumeId":resume["resumeId"],"roleId":role["roleId"],"roleCode":role["roleCode"],"roleName":role["roleName"],"roleVersion":role["version"]})
    with (OUTPUT / "resume-registry.csv").open("w", encoding="utf-8-sig", newline="") as f:
        w=csv.DictWriter(f, fieldnames=fields); w.writeheader(); w.writerows(resumes)
    cfields=list(cases[0])
    with (OUTPUT / "case-manifest.csv").open("w", encoding="utf-8-sig", newline="") as f:
        w=csv.DictWriter(f, fieldnames=cfields); w.writeheader(); w.writerows(cases)
    base={"schemaVersion":"match-final-blind-intake-v1.0","status":"INTAKE_INCOMPLETE","createdAt":"2026-08-28T00:00:00Z","roleCatalogPath":CARDS.resolve().as_posix(),"roleCatalogSchemaVersion":json.loads(CARDS.read_text(encoding="utf-8"))["schemaVersion"],"roleCatalogSha256":sha(CARDS),"plannedResumeCount":30,"plannedRoleCount":7,"plannedCaseCount":210,"containsLabels":False,"roles":[{"roleId":r["roleId"],"roleCode":r["roleCode"],"roleName":r["roleName"],"roleVersion":r["version"]} for r in roles],"resumes":resumes,"cases":cases}
    (OUTPUT / "intake-manifest.json").write_text(json.dumps(base,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(OUTPUT)

if __name__ == "__main__": main()

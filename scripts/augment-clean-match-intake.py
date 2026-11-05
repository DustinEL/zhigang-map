"""Create a new unlabeled 30-resume intake by adding one audited candidate."""
import argparse, csv, json, hashlib
from pathlib import Path

def read_csv(path):
    with path.open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))

def write_csv(path, fields, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields); writer.writeheader(); writer.writerows(rows)

def main():
    p = argparse.ArgumentParser(); p.add_argument("--base", type=Path, required=True); p.add_argument("--source", type=Path, required=True); p.add_argument("--output", type=Path, required=True); p.add_argument("--cards", type=Path, required=True); args = p.parse_args()
    base = args.base.resolve(); source = args.source.resolve(); out = args.output.resolve()
    if out.exists(): raise SystemExit(f"拒绝覆盖已有目录: {out}")
    out.mkdir(parents=True)
    manifest = json.loads((base / "intake-manifest.json").read_text(encoding="utf-8"))
    registry = read_csv(base / "resume-registry.csv")
    cases = read_csv(base / "case-manifest.csv")
    source_rows = read_csv(source / "resume-registry.csv")
    extra = next(row for row in source_rows if row["resumeId"] == "NR34-034")
    extra = dict(extra); extra["resumeId"] = "NR30-030"; extra["candidateKey"] = "next-round-clean-30-030"
    registry.append(extra)
    roles = manifest["roles"]
    for role in roles:
        cases.append({"caseId": f"NR30-C-{len(cases)+1:04d}", "resumeId": extra["resumeId"], "roleId": role["roleId"], "roleCode": role["roleCode"], "roleName": role["roleName"], "roleVersion": role["roleVersion"]})
    fields = list(registry[0]); write_csv(out / "resume-registry.csv", fields, registry)
    write_csv(out / "case-manifest.csv", list(cases[0]), cases)
    manifest["plannedResumeCount"] = len(registry); manifest["plannedCaseCount"] = len(cases); manifest["resumes"] = registry; manifest["cases"] = cases; manifest["status"] = "INTAKE_INCOMPLETE"
    manifest["roleCatalogPath"] = args.cards.resolve().as_posix()
    manifest["roleCatalogSchemaVersion"] = json.loads(args.cards.read_text(encoding="utf-8"))["schemaVersion"]
    manifest["roleCatalogSha256"] = hashlib.sha256(args.cards.resolve().read_bytes()).hexdigest()
    (out / "intake-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"created {len(registry)} resumes and {len(cases)} cases at {out}")

if __name__ == "__main__": main()

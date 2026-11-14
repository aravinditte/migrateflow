#!/usr/bin/env python3
"""
Independent read-only verification of the public MigrateFlow showcase
(Automated Showcase spec, sections 5.4/9/11). Uses ONLY the anonymous
public GET API plus the checked-in fixture expectations; it never signs
in and never writes. Exits non-zero and prints every mismatch when the
displayed evidence is not authentic and internally consistent.

Usage:  python3 scripts/verify-showcase.py [base-url]
        (default base URL http://localhost:8081, or MIGRATEFLOW_BASE_URL)
"""
import json
import os
import sys
import urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("MIGRATEFLOW_BASE_URL", "http://localhost:8081")).rstrip("/")
FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"
problems = []


def get(path):
    req = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception as exc:
        problems.append(f"GET {path} unreachable: {exc}")
        return None, None


def check(label, actual, expected):
    if actual != expected:
        problems.append(f"{label}: expected {expected!r}, got {actual!r}")
    else:
        print(f"  ok {label}: {actual!r}")


def main():
    expected = json.loads((FIXTURES / "expected_results.json").read_text())

    print(f"Verifying public showcase at {BASE} ...")
    status, overview = get("/api/v1/showcase/overview")
    if status != 200 or overview is None:
        print(f"FAIL: overview returned HTTP {status}")
        sys.exit(1)
    check("setupState", overview["setupState"], "READY")
    check("integrity", overview["integrity"], "OK")
    check("integrityProblems", overview["integrityProblems"], [])
    v = overview["validation"]
    check("raw customers", v["rawCustomers"], expected["customers"]["rawRows"])
    check("raw orders", v["rawOrders"], expected["orders"]["rawRows"])
    check("accepted customers", v["acceptedCustomers"], expected["customers"]["acceptedRows"])
    check("accepted orders", v["acceptedOrders"], expected["orders"]["acceptedRows"])
    check("excluded customers", v["excludedCustomers"], expected["customers"]["excludedRows"])
    check("excluded orders", v["excludedOrders"], expected["orders"]["excludedRows"])
    check("rehearsal status", overview["rehearsal"]["status"], "VERIFIED")
    check("rehearsal reconciliation", overview["rehearsal"]["reconciliation"], "PASS")
    check("cutover status", overview["cutover"]["status"], "VERIFIED")
    check("cutover reconciliation", overview["cutover"]["reconciliation"], "PASS")
    check("accepted total yen", overview["money"]["acceptedTotalYen"], expected["orders"]["acceptedAmountTotalYen"])
    check("written total yen", overview["money"]["writtenTotalYen"], expected["orders"]["acceptedAmountTotalYen"])
    if not overview["verification"]["verifiedAt"]:
        problems.append("verification.verifiedAt is empty")
    if not overview["verification"]["reportEvidenceHash"]:
        problems.append("verification.reportEvidenceHash is empty")

    status, workflow = get("/api/v1/showcase/workflow")
    if status == 200:
        stages = workflow["stages"]
        check("workflow stage count", len(stages), 11)
        incomplete = [s["key"] for s in stages if s["status"] != "COMPLETED"]
        if incomplete:
            problems.append(f"workflow stages not COMPLETED: {incomplete}")
        else:
            print("  ok all 11 workflow stages COMPLETED")
    else:
        problems.append(f"workflow returned HTTP {status}")

    for scope in ("rehearsal", "cutover"):
        status, report = get(f"/api/v1/showcase/reconciliation?scope={scope}")
        if status != 200:
            problems.append(f"reconciliation[{scope}] returned HTTP {status}")
        else:
            check(f"reconciliation[{scope}] status", report["status"], "PASS")
            check(f"reconciliation[{scope}] differences",
                  report["summary"]["differences"], [])

    status, uat = get("/api/v1/showcase/uat")
    if status == 200:
        passed = [c for c in uat["cases"] if c["required"] and c["latestResult"] == "PASS"]
        check("required UAT cases passing", len(passed), 5)
        check("approval status", uat["approvalStatus"], "APPROVE")
    else:
        problems.append(f"uat returned HTTP {status}")

    status, records = get("/api/v1/showcase/records?kind=customers&size=5")
    if status != 200 or not records["content"]:
        problems.append(f"records sample missing (HTTP {status})")
    else:
        row = records["content"][0]
        print(f"  ok records sample: {row['externalId']} from {row['sourceFile']} row {row['sourceRowNumber']}")

    status, issues = get("/api/v1/showcase/validation?size=50")
    if status != 200:
        problems.append(f"validation returned HTTP {status}")
    else:
        decided = [i for i in issues["content"] if i["decision"] == "EXCLUDE"]
        rows = {(i["kind"], i["dataRowNumber"]) for i in decided}
        check("distinct excluded rows listed", len(rows),
              expected["customers"]["excludedRows"] + expected["orders"]["excludedRows"])

    if problems:
        print("\nSHOWCASE VERIFICATION FAILED:")
        for p in problems:
            print(f"  - {p}")
        sys.exit(1)
    print("\nShowcase verification passed: every displayed figure traces to a real persisted artifact.")


if __name__ == "__main__":
    main()

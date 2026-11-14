#!/usr/bin/env python3
"""
Live backend-to-TypeScript contract verification (MF-011).
Fetches live responses from all public showcase endpoints and validates
them against the exact schemas and field types required by the frontend.

Usage:  python3 scripts/verify-contracts-live.py [base-url]
"""
import json
import os
import sys
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("MIGRATEFLOW_BASE_URL", "http://localhost:8081")).rstrip("/")
errors = []

def get(path):
    req = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except Exception as e:
        errors.append(f"GET {path} failed: {e}")
        return None, None

def require_fields(obj, expected_fields, name):
    if not isinstance(obj, dict):
        errors.append(f"{name} is not an object: {type(obj)}")
        return
    for field, expected_type in expected_fields.items():
        if field not in obj:
            errors.append(f"{name} missing field {field!r}")
        elif obj[field] is not None and not isinstance(obj[field], expected_type):
            errors.append(f"{name}.{field} expected {expected_type}, got {type(obj[field])}")

def main():
    print(f"Testing live contracts against {BASE} ...")

    # 1. Overview
    s, overview = get("/api/v1/showcase/overview")
    if s == 200:
        require_fields(overview, {
            "showcase": str, "mode": str, "dataClassification": str,
            "setupState": str, "setupStep": (str, type(None)),
            "integrity": str, "integrityProblems": list,
            "integrityCheckedAt": str, "failureMessage": (str, type(None)),
            "backend": dict, "project": (dict, type(None)),
            "validation": (dict, type(None)),
            "rehearsal": (dict, type(None)), "cutover": (dict, type(None)),
            "money": (dict, type(None)), "verification": (dict, type(None)),
        }, "ShowcaseOverview")
        if overview.get("validation"):
            require_fields(overview["validation"], {
                "rawCustomers": int, "rawOrders": int,
                "acceptedCustomers": int, "acceptedOrders": int,
                "excludedCustomers": int, "excludedOrders": int,
            }, "ShowcaseOverview.validation")
        if overview.get("money"):
            require_fields(overview["money"], {
                "currency": str, "acceptedTotalYen": int, "writtenTotalYen": int,
            }, "ShowcaseOverview.money")
        if overview.get("verification"):
            require_fields(overview["verification"], {
                "verifiedAt": (str, type(None)),
                "cutoverReconciliationCompletedAt": (str, type(None)),
                "showcaseRegisteredAt": (str, type(None)),
                "manifestFingerprint": (str, type(None)),
                "manifestHash": (str, type(None)),
                "reportEvidenceHash": (str, type(None)),
            }, "ShowcaseOverview.verification")
        print("  ok Overview contract")

    # 2. Workflow
    s, wf = get("/api/v1/showcase/workflow")
    if s == 200:
        require_fields(wf, {"setupState": str, "stages": list}, "ShowcaseWorkflow")
        for stage in wf.get("stages", []):
            require_fields(stage, {
                "key": str, "title": str, "status": str,
                "occurredAt": (str, type(None)), "evidence": dict,
            }, "ShowcaseStage")
        print("  ok Workflow contract")

    # 3. Reconciliation (both scopes)
    for scope in ("rehearsal", "cutover"):
        s, rec = get(f"/api/v1/showcase/reconciliation?scope={scope}")
        if s == 200:
            require_fields(rec, {
                "scope": str, "runId": str, "status": str,
                "evidenceHash": str, "completedAt": str, "summary": dict,
            }, f"ReconciliationView[{scope}]")
            require_fields(rec["summary"], {
                "customers": dict, "orders": dict, "differences": list,
            }, f"ReconciliationView[{scope}].summary")
            require_fields(rec["summary"]["customers"], {
                "raw": int, "accepted": int, "excluded": int, "written": int,
            }, f"ReconciliationView[{scope}].summary.customers")
            require_fields(rec["summary"]["orders"], {
                "raw": int, "accepted": int, "excluded": int, "written": int,
                "acceptedAmountYen": int, "writtenAmountYen": int,
            }, f"ReconciliationView[{scope}].summary.orders")
            print(f"  ok Reconciliation[{scope}] contract")

    # 4. UAT
    s, uat = get("/api/v1/showcase/uat")
    if s == 200:
        require_fields(uat, {
            "cases": list, "defects": list, "uatEvidenceHash": str,
            "approvalStatus": (str, type(None)), "approvedByRole": (str, type(None)),
            "approvedAt": (str, type(None)),
        }, "UatView")
        for c in uat.get("cases", []):
            require_fields(c, {
                "caseKey": str, "title": str, "required": bool,
                "latestResult": str, "latestActualResult": str,
                "latestExecutedAt": (str, type(None)), "latestExecutedByRole": str,
            }, "UatCase")
        print("  ok UAT contract")

    # 5. Records
    for kind in ("customers", "orders"):
        s, recs = get(f"/api/v1/showcase/records?kind={kind}&size=3")
        if s == 200:
            require_fields(recs, {"content": list, "page": int, "size": int}, f"RecordPage[{kind}]")
            for r in recs.get("content", []):
                require_fields(r, {
                    "kind": str, "externalId": str, "source": dict,
                    "target": dict, "sourceFile": str, "sourceRowNumber": int,
                    "originRunId": str,
                }, f"RecordRow[{kind}]")
            print(f"  ok Records[{kind}] contract")

    # 6. Validation
    s, val = get("/api/v1/showcase/validation?size=5")
    if s == 200:
        require_fields(val, {"content": list, "page": int, "size": int}, "ValidationPage")
        for iss in val.get("content", []):
            require_fields(iss, {
                "kind": str, "dataRowNumber": int, "code": str, "field": str, "message": str,
                "decision": str, "decisionReason": str, "decidedByRole": str,
            }, "ValidationIssue")
        print("  ok Validation contract")

    # 7. Audit
    s, audit = get("/api/v1/showcase/audit?size=5")
    if s == 200:
        require_fields(audit, {"content": list, "page": int, "size": int}, "AuditPage")
        for a in audit.get("content", []):
            require_fields(a, {
                "action": str, "entityType": str, "role": str, "occurredAt": str,
            }, "AuditRow")
        print("  ok Audit contract")

    # 8. Evidence
    s, ev = get("/api/v1/showcase/evidence")
    if s == 200:
        require_fields(ev, {
            "showcase": str, "dataClassification": str, "label": str,
            "overview": dict, "workflow": dict, "reconciliationRehearsal": dict,
            "reconciliationCutover": dict, "uat": dict,
        }, "EvidenceBundle")
        print("  ok Evidence JSON contract")

    if errors:
        print("\nCONTRACT VERIFICATION FAILURES:")
        for err in errors:
            print(f"  - {err}")
        sys.exit(1)

    print("\nLive contract verification passed: all backend responses match frontend interfaces.")

if __name__ == "__main__":
    main()

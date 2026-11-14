#!/usr/bin/env python3
"""
MigrateFlow showcase bootstrap (Automated Showcase spec, section 4).

One-time, idempotent, resumable orchestration that produces the public
showcase scenario by driving the REAL authenticated MigrateFlow workflow
over HTTP - project, upload, mapping, validation, reviewer exclusions,
manifest freeze, Spring Batch rehearsal, scripted-but-real UAT checks,
reviewer approval, engineer cutover - and finally registering the
resulting artifact IDs in the showcase registry via the token-guarded
internal endpoint. The registry is a pointer only; the backend
re-verifies lineage before it will accept (or display) READY.

It never inserts into target/rehearsal/approval/report tables, never
resets anything, and fails closed: any failed gate records FAILED in the
registry and exits non-zero.

Prerequisites (operator/deployment side, spec 4.2):
  * Backend running with MIGRATEFLOW_SHOWCASE_BOOTSTRAP_ENABLED=true and
    MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN set to the same token given here.
  * Demo/operator accounts exist (the seeded demo users for local demos,
    or provisioned equivalents). Credentials come from the environment;
    the defaults below are the LOCAL DEMO seed defaults only - never use
    them on a public deployment.

Environment:
  MIGRATEFLOW_BASE_URL                 default http://localhost:8081
  MIGRATEFLOW_SHOWCASE_SLUG            default hinata-trading
  MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN required
  MIGRATEFLOW_ADMIN_EMAIL / _PASSWORD
  MIGRATEFLOW_ENGINEER_EMAIL / _PASSWORD
  MIGRATEFLOW_REVIEWER_EMAIL / _PASSWORD
  MIGRATEFLOW_FIXTURES_DIR             default <repo>/fixtures

Usage:  python3 scripts/bootstrap-showcase.py
State is kept in scripts/.showcase-bootstrap-state.json so an interrupted
run resumes from the last completed stage instead of starting over.
"""
import csv
import json
import os
import sys
import time
import urllib.request
import urllib.error
import http.cookiejar
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FIXTURES = Path(os.environ.get("MIGRATEFLOW_FIXTURES_DIR", REPO / "fixtures"))
BASE = os.environ.get("MIGRATEFLOW_BASE_URL", "http://localhost:8081").rstrip("/")
SLUG = os.environ.get("MIGRATEFLOW_SHOWCASE_SLUG", "hinata-trading")
TOKEN = os.environ.get("MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN", "")
STATE_FILE = Path(__file__).resolve().parent / ".showcase-bootstrap-state.json"
BOOTSTRAP_VERSION = "bootstrap-showcase.py/1.0"
PROJECT_NAME = "Hinata Trading Co. — Legacy Sales Migration"

CREDS = {
    "admin": (os.environ.get("MIGRATEFLOW_ADMIN_EMAIL", "admin@migrateflow.test"),
              os.environ.get("MIGRATEFLOW_ADMIN_PASSWORD", "admin-dev-pw")),
    "engineer": (os.environ.get("MIGRATEFLOW_ENGINEER_EMAIL", "engineer@migrateflow.test"),
                 os.environ.get("MIGRATEFLOW_ENGINEER_PASSWORD", "engineer-dev-pw")),
    "reviewer": (os.environ.get("MIGRATEFLOW_REVIEWER_EMAIL", "reviewer@migrateflow.test"),
                 os.environ.get("MIGRATEFLOW_REVIEWER_PASSWORD", "reviewer-dev-pw")),
    "auditor": (os.environ.get("MIGRATEFLOW_AUDITOR_EMAIL", "auditor@migrateflow.test"),
                os.environ.get("MIGRATEFLOW_AUDITOR_PASSWORD", "auditor-dev-pw")),
}


def die(msg):
    print(f"BOOTSTRAP FAILED: {msg}", file=sys.stderr)
    sys.exit(1)


class Client:
    """One actor's session: cookies + Spring CSRF cookie/header pattern."""

    def __init__(self, name):
        self.name = name
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def _csrf(self):
        for c in self.jar:
            if c.name == "XSRF-TOKEN":
                return c.value
        return ""

    def request(self, method, path, body=None, headers=None, form=None, raw=None, ctype=None):
        url = BASE + path
        data = raw
        hdrs = dict(headers or {})
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            hdrs["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"
        if ctype:
            hdrs["Content-Type"] = ctype
        if method in ("POST", "PUT", "PATCH", "DELETE"):
            hdrs["X-XSRF-TOKEN"] = self._csrf()
        req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
        try:
            with self.opener.open(req, timeout=60) as resp:
                text = resp.read().decode()
                return resp.status, json.loads(text) if text.strip().startswith(("{", "[")) else text
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            try:
                payload = json.loads(text)
            except Exception:
                payload = text
            return e.code, payload

    def login(self, email, password):
        self.request("GET", "/api/v1/auth/csrf")
        status, payload = self.request("POST", "/api/v1/auth/login",
                                       form={"username": email, "password": password})
        if status != 200:
            die(f"login failed for {self.name} ({email}): HTTP {status} {payload}")
        self.request("GET", "/api/v1/auth/csrf")
        print(f"  signed in as {self.name} ({email})")


import urllib.parse  # noqa: E402  (used by Client.request form encoding)


def expect(status, wanted, what, payload):
    if status != wanted:
        die(f"{what}: expected HTTP {wanted}, got {status}: {payload}")
    return payload


def load_state():
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text())
    return {}


def save_state(state):
    STATE_FILE.write_text(json.dumps(state, indent=2))


def register(engineer: Client, state, setup_state, step, failure=None):
    body = {
        "slug": SLUG,
        "projectId": state.get("projectId"), "bundleId": state.get("bundleId"),
        "mappingRevisionId": state.get("mappingRevisionId"),
        "ruleSetRevisionId": state.get("ruleSetRevisionId"),
        "snapshotId": state.get("snapshotId"), "manifestId": state.get("manifestId"),
        "rehearsalRunId": state.get("rehearsalRunId"), "approvalId": state.get("approvalId"),
        "cutoverRunId": state.get("cutoverRunId"),
        "setupState": setup_state, "setupStep": step,
        "bootstrapVersion": BOOTSTRAP_VERSION, "failureMessage": failure,
    }
    status, payload = engineer.request("POST", "/api/v1/internal/showcase/register", body=body,
                                       headers={"X-Showcase-Bootstrap-Token": TOKEN})
    if status == 404:
        die("showcase bootstrap is not enabled on the backend "
            "(set MIGRATEFLOW_SHOWCASE_BOOTSTRAP_ENABLED=true and restart it)")
    if status == 422:
        die(f"backend refused READY - lineage problems: {payload}")
    expect(status, 200, "register showcase scenario", payload)
    return payload


def wait_run(client: Client, run_id, label, timeout=900):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        status, run = client.request("GET", f"/api/v1/runs/{run_id}")
        expect(status, 200, f"poll {label} run", run)
        last = run["status"]
        if last in ("VERIFIED", "VERIFICATION_FAILED", "FAILED_TERMINAL"):
            return run
        time.sleep(3)
    die(f"{label} run {run_id} did not finish within {timeout}s (last status {last})")


def read_csv(path):
    with open(path, newline="", encoding="utf-8-sig") as fh:
        rows = list(csv.reader(fh))
    header, data = rows[0], rows[1:]
    return header, {row[0]: (i + 1, row) for i, row in enumerate(data)}, data


def multipart_upload(client: Client, path, files, fields):
    boundary = "----migrateflow-showcase-boundary"
    chunks = []
    for name, value in fields.items():
        chunks.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode())
    for name, file_path in files.items():
        content = Path(file_path).read_bytes()
        chunks.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; "
                       f"filename=\"{Path(file_path).name}\"\r\nContent-Type: text/csv\r\n\r\n").encode()
                      + content + b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode())
    return client.request("POST", path, raw=b"".join(chunks),
                          ctype=f"multipart/form-data; boundary={boundary}")


def main():
    if not TOKEN:
        die("MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN is not set")
    expected = json.loads((FIXTURES / "expected_results.json").read_text())
    state = load_state()

    print(f"[1/6] Signing in demo actors against {BASE} ...")
    admin = Client("admin"); admin.login(*CREDS["admin"])
    engineer = Client("engineer"); engineer.login(*CREDS["engineer"])
    reviewer = Client("reviewer"); reviewer.login(*CREDS["reviewer"])

    # Idempotency: an already-READY registry scenario ends the run here.
    status, existing = engineer.request(
        "GET", f"/api/v1/internal/showcase/scenario?slug={SLUG}",
        headers={"X-Showcase-Bootstrap-Token": TOKEN})
    if status == 200 and existing.get("setupState") == "READY":
        print(f"Showcase '{SLUG}' is already bootstrapped and READY "
              f"(project {existing.get('projectId')}, cutover {existing.get('cutoverRunId')}). Nothing to do.")
        return
    if status == 200:
        for key in ("projectId", "bundleId", "mappingRevisionId", "ruleSetRevisionId", "snapshotId",
                    "manifestId", "rehearsalRunId", "approvalId", "cutoverRunId"):
            if existing.get(key):
                state[key] = existing[key]
        save_state(state)
        print(f"  resuming from registry state {existing.get('setupState')} at step {existing.get('setupStep')}")

    try:
        run_stages(admin, engineer, reviewer, state, expected)
    except SystemExit:
        raise
    except Exception as exc:  # fail closed in the registry as well
        try:
            register(engineer, state, "FAILED", state.get("step"), failure=str(exc))
        except SystemExit:
            pass
        raise


def run_stages(admin, engineer, reviewer, state, expected):
    pid = state.get("projectId")

    print("[2/6] Project, intake, mapping, validation ...")
    if not pid:
        status, users = admin.request("GET", "/api/v1/admin/users")
        expect(status, 200, "list users", users)
        by_email = {u["email"]: u["id"] for u in users}
        status, project = engineer.request("POST", "/api/v1/projects", body={
            "name": PROJECT_NAME,
            "description": "Automated public showcase scenario: synthetic Hinata Trading Co. legacy sales data, "
                           "executed end-to-end by the real MigrateFlow workflow with automated demo actors."})
        expect(status, 201, "create project", project)
        pid = state["projectId"] = project["id"]
        for email_key, role in (("reviewer", "REVIEWER"), ("auditor", "AUDITOR")):
            email, _ = CREDS[email_key]
            s2, p2 = admin.request("POST", f"/api/v1/projects/{pid}/memberships",
                                   body={"userId": by_email[email], "role": role})
            expect(s2, 201, f"add {role} membership", p2)
        save_state(state)
        register(engineer, state, "PREPARING", "project")

    if not state.get("bundleId"):
        status, bundle = multipart_upload(
            engineer, f"/api/v1/projects/{pid}/bundles",
            files={"customers": FIXTURES / "customers_1000.csv", "orders": FIXTURES / "orders_2000.csv"},
            fields={"exportedAt": "2025-03-31T00:00:00Z"})
        expect(status, 201, "upload bundle", bundle)
        state["bundleId"] = bundle["bundleId"]
        row_counts = {f["kind"]: f["rowCount"] for f in bundle["files"]}
        assert row_counts.get("CUSTOMERS") == expected["customers"]["rawRows"], row_counts
        assert row_counts.get("ORDERS") == expected["orders"]["rawRows"], row_counts
        save_state(state)
        register(engineer, state, "PREPARING", "intake")
        print(f"  bundle {state['bundleId']} uploaded: {row_counts}")

    if not state.get("mappingRevisionId"):
        status, mapping = engineer.request("POST", f"/api/v1/projects/{pid}/mappings/publish", body={})
        expect(status, 201, "publish mapping", mapping)
        state["mappingRevisionId"] = mapping["revisionId"]
        state["ruleSetRevisionId"] = mapping["ruleSetRevisionId"]
        save_state(state)
        register(engineer, state, "PREPARING", "mapping")

    if not state.get("snapshotId"):
        status, started = engineer.request("POST", f"/api/v1/projects/{pid}/validations", body={
            "bundleId": state["bundleId"], "mappingRevisionId": state["mappingRevisionId"]})
        expect(status, 202, "start validation", started)
        state["snapshotId"] = started["snapshotId"]
        save_state(state)
    snapshot = None
    deadline = time.time() + 300
    while time.time() < deadline:
        status, snapshot = engineer.request(
            "GET", f"/api/v1/projects/{pid}/validation-snapshots/{state['snapshotId']}")
        expect(status, 200, "poll validation snapshot", snapshot)
        if snapshot["status"] in ("COMPLETED", "FAILED"):
            break
        time.sleep(2)
    if not snapshot or snapshot["status"] != "COMPLETED":
        die(f"validation did not complete: {snapshot}")
    counts = json.loads(snapshot["counts"])
    assert counts["customers"]["valid"] == expected["customers"]["acceptedRows"], counts
    assert counts["orders"]["valid"] == expected["orders"]["acceptedRows"], counts
    register(engineer, state, "PREPARING", "validation")
    print(f"  validation COMPLETED: {counts}")

    print("[3/6] Reviewer exclusions for every invalid row ...")
    issues, page = [], 0
    while True:
        status, result = reviewer.request(
            "GET", f"/api/v1/projects/{pid}/validation-snapshots/{state['snapshotId']}/issues?size=100&page={page}")
        expect(status, 200, "list validation issues", result)
        issues.extend(result["content"])
        if len(result["content"]) < 100:
            break
        page += 1
    invalid = {(i["kind"], i["sourceRowId"]): i for i in issues if i["severity"] == "ERROR"}
    decided = set(state.get("decidedRows", []))
    for (kind, row_id), issue in invalid.items():
        if f"{kind}:{row_id}" in decided:
            continue
        status, decision = reviewer.request(
            "POST", f"/api/v1/validation-snapshots/{state['snapshotId']}/exclusions",
            body={"dataset": kind, "sourceRowId": row_id, "decision": "EXCLUDE",
                  "reason": f"Automated showcase review: {issue['code']} at data row "
                            f"{issue['dataRowNumber']} - {issue['message']}"})
        expect(status, 201, "record exclusion decision", decision)
        decided.add(f"{kind}:{row_id}")
        state["decidedRows"] = sorted(decided)
        save_state(state)
    total_invalid = expected["customers"]["excludedRows"] + expected["orders"]["excludedRows"]
    assert len(invalid) == total_invalid, f"expected {total_invalid} invalid rows, saw {len(invalid)}"
    state["invalidRows"] = [{"kind": k[0], "dataRowNumber": v["dataRowNumber"]} for k, v in invalid.items()]
    save_state(state)
    register(engineer, state, "PREPARING", "exclusions")
    print(f"  {len(invalid)} invalid rows excluded by REVIEWER (none valid touched)")

    print("[4/6] Freeze manifest and run the real rehearsal ...")
    if not state.get("manifestId"):
        status, manifest = engineer.request("POST", f"/api/v1/projects/{pid}/manifests",
                                            body={"snapshotId": state["snapshotId"]})
        expect(status, 201, "freeze manifest", manifest)
        assert manifest["status"] == "FROZEN", manifest
        state["manifestId"] = manifest["manifestId"]
        state["manifestHash"] = manifest["manifestHash"]
        save_state(state)
        register(engineer, state, "PREPARING", "freeze")

    if not state.get("rehearsalRunId"):
        status, launched = engineer.request("POST", f"/api/v1/projects/{pid}/rehearsals",
                                            body={"manifestId": state["manifestId"]})
        expect(status, 202, "launch rehearsal", launched)
        state["rehearsalRunId"] = launched["runId"]
        save_state(state)
    run = wait_run(engineer, state["rehearsalRunId"], "rehearsal")
    if run["status"] != "VERIFIED":
        die(f"rehearsal did not verify: {run}")
    register(engineer, state, "PREPARING", "rehearsal")
    status, report = engineer.request("GET", f"/api/v1/runs/{state['rehearsalRunId']}/reconciliation")
    expect(status, 200, "rehearsal reconciliation", report)
    assert report["status"] == "PASS", report
    state["rehearsalReportId"] = report["reportId"]
    print(f"  rehearsal VERIFIED, reconciliation PASS ({report['reportId']})")

    print("[5/6] Scripted UAT - real checks, recorded by REVIEWER ...")
    run_uat_checks(engineer, reviewer, state, expected, json.loads(report["summary"]), counts)
    register(engineer, state, "PREPARING", "uat")

    if not state.get("approvalId"):
        status, uat_now = reviewer.request("GET", f"/api/v1/rehearsals/{state['rehearsalRunId']}/uat")
        expect(status, 200, "current UAT evidence", uat_now)
        status, approval = reviewer.request(
            "POST", f"/api/v1/manifests/{state['manifestId']}/approvals", body={
                "decision": "APPROVE", "rehearsalRunId": state["rehearsalRunId"],
                "reconciliationReportId": state["rehearsalReportId"],
                "uatEvidenceHash": uat_now["uatEvidenceHash"],
                "comment": "Automated demonstration approval (REVIEWER role): scripted UAT checks passed "
                           "against real rehearsal artifacts; all application gates enforced."})
        expect(status, 201, "submit approval", approval)
        state["approvalId"] = approval["approvalId"]
        save_state(state)
    register(engineer, state, "PREPARING", "approval")
    print(f"  approval recorded: {state['approvalId']}")

    print("[6/6] Final cutover and showcase registration ...")
    if not state.get("cutoverRunId"):
        status, launched = engineer.request(
            "POST", f"/api/v1/manifests/{state['manifestId']}/cutovers",
            body={"approvalId": state["approvalId"]},
            headers={"Idempotency-Key": f"showcase-{SLUG}-v1"})
        expect(status, 202, "launch cutover", launched)
        state["cutoverRunId"] = launched["runId"]
        save_state(state)
    run = wait_run(engineer, state["cutoverRunId"], "cutover")
    if run["status"] != "VERIFIED":
        die(f"cutover did not verify: {run}")
    status, final_report = engineer.request("GET", f"/api/v1/runs/{state['cutoverRunId']}/reconciliation")
    expect(status, 200, "cutover reconciliation", final_report)
    assert final_report["status"] == "PASS", final_report
    summary = json.loads(final_report["summary"])
    assert summary["orders"]["writtenAmountYen"] == expected["orders"]["acceptedAmountTotalYen"], summary

    register(engineer, state, "READY", "publish")
    print(f"Showcase READY: project={pid} manifest={state['manifestId']} "
          f"rehearsal={state['rehearsalRunId']} cutover={state['cutoverRunId']}")
    print(f"Public overview: {BASE}/api/v1/showcase/overview")


# ----------------------------------------------------------------------
# The five UAT checks (spec 4.4): each PASSes only on real assertions
# against live artifacts; a failed check is recorded FAIL and aborts.
# ----------------------------------------------------------------------

def count_rehearsal_rows(client, run_id, kind):
    total, page = 0, 0
    while True:
        status, result = client.request(
            "GET", f"/api/v1/rehearsals/{run_id}/{kind}?size=100&page={page}")
        expect(status, 200, f"count rehearsal {kind}", result)
        total += len(result["content"])
        if len(result["content"]) < 100:
            return total
        page += 1


def search_rehearsal(client, run_id, kind, q):
    status, result = client.request("GET", f"/api/v1/rehearsals/{run_id}/{kind}?q={q}")
    expect(status, 200, f"search rehearsal {kind}", result)
    return result["content"]


def run_uat_checks(engineer, reviewer, state, expected, rehearsal_summary, counts):
    pid, run_id = state["projectId"], state["rehearsalRunId"]
    status, uat = reviewer.request("GET", f"/api/v1/rehearsals/{run_id}/uat")
    expect(status, 200, "list UAT cases", uat)
    cases = {c["caseKey"]: c for c in uat["cases"]}

    _, cust_by_id, _ = read_csv(FIXTURES / "customers_1000.csv")
    _, order_by_id, _ = read_csv(FIXTURES / "orders_2000.csv")

    def check_counts():
        c = count_rehearsal_rows(engineer, run_id, "customers")
        o = count_rehearsal_rows(engineer, run_id, "orders")
        ok = (c == rehearsal_summary["customers"]["acceptedRows"] == counts["customers"]["valid"]
              and o == rehearsal_summary["orders"]["acceptedRows"] == counts["orders"]["valid"])
        return ok, (f"rehearsal target holds {c} customers and {o} orders; reconciliation report and "
                    f"validation snapshot agree ({counts['customers']['valid']}/{counts['orders']['valid']} accepted)")

    def check_customers():
        samples = search_rehearsal(engineer, run_id, "customers", "")[:3]
        if len(samples) < 3:
            return False, "fewer than 3 rehearsal customers available to spot-check"
        for row in samples:
            _, src = cust_by_id[row["externalCustomerId"]]
            if row["fullName"].strip() != src[1].strip():
                return False, f"{row['externalCustomerId']}: name {row['fullName']!r} != source {src[1]!r}"
            if row["email"] != src[2].strip().lower():
                return False, f"{row['externalCustomerId']}: email {row['email']!r} != normalized source {src[2]!r}"
            if row["joinedOn"] != src[3].strip().replace("/", "-"):
                return False, f"{row['externalCustomerId']}: joined_on {row['joinedOn']} != source {src[3]}"
        ids = ", ".join(r["externalCustomerId"] for r in samples)
        return True, f"spot-checked {ids}: normalized name/email/joined date match the accepted source rows"

    def check_orders():
        accepted_total = sum(int(r[3]) for _, r in order_by_id.values())
        invalid_numbers = {i["dataRowNumber"] for i in state["invalidRows"] if i["kind"] == "ORDERS"}
        expected_total = sum(int(r[3]) for n, r in order_by_id.values() if n not in invalid_numbers)
        ok = (rehearsal_summary["orders"]["acceptedAmountYen"] == expected_total
              == rehearsal_summary["orders"]["writtenAmountYen"])
        samples = search_rehearsal(engineer, run_id, "orders", "")[:3]
        for row in samples:
            _, src = order_by_id[row["externalOrderId"]]
            if row["amountYen"] != int(src[3]) or row["externalCustomerId"] != src[1]:
                return False, f"{row['externalOrderId']}: target row disagrees with source {src}"
        return ok, (f"order totals agree: source-derived expected ¥{expected_total:,}, report accepted "
                    f"¥{rehearsal_summary['orders']['acceptedAmountYen']:,}, written "
                    f"¥{rehearsal_summary['orders']['writtenAmountYen']:,}; 3 sampled orders match source amounts")

    def check_exclusions_absent():
        missing = []
        for item in state["invalidRows"]:
            by_id = cust_by_id if item["kind"] == "CUSTOMERS" else order_by_id
            kind_path = "customers" if item["kind"] == "CUSTOMERS" else "orders"
            external_id = next(ext for ext, (n, _) in by_id.items() if n == item["dataRowNumber"])
            if search_rehearsal(engineer, run_id, kind_path, external_id):
                missing.append(external_id)
        if missing:
            return False, f"excluded rows present in rehearsal target: {missing}"
        return True, (f"all {len(state['invalidRows'])} excluded source rows are absent from the rehearsal "
                      f"targets; no excluded key was found")

    def check_provenance():
        status, run = engineer.request("GET", f"/api/v1/runs/{run_id}")
        expect(status, 200, "run detail for provenance", run)
        if run["manifestId"] != state["manifestId"]:
            return False, f"run manifest {run['manifestId']} != frozen manifest {state['manifestId']}"
        for kind, by_id in (("customers", cust_by_id), ("orders", order_by_id)):
            for row in search_rehearsal(engineer, run_id, kind, "")[:3]:
                ext = row["externalCustomerId"] if kind == "customers" else row["externalOrderId"]
                expected_row_no = by_id[ext][0]
                if row["sourceRowNumber"] != expected_row_no:
                    return False, f"{ext}: source_row_number {row['sourceRowNumber']} != CSV row {expected_row_no}"
        return True, ("sampled rehearsal rows carry source row numbers matching the fixture CSV positions, "
                      f"and the run is bound to frozen manifest {state['manifestId']}")

    checks = {
        "COUNTS_MATCH": check_counts,
        "CUSTOMER_SPOT_CHECK": check_customers,
        "ORDER_TOTALS": check_orders,
        "EXCLUSIONS_ABSENT": check_exclusions_absent,
        "PROVENANCE_TRACE": check_provenance,
    }
    for key, fn in checks.items():
        case = cases.get(key)
        if case is None:
            die(f"required UAT case {key} was not seeded for the rehearsal")
        if case.get("latestResult") == "PASS":
            print(f"  UAT {key}: already PASS, skipping")
            continue
        ok, actual = fn()
        status, _ = reviewer.request(
            "POST", f"/api/v1/rehearsals/{run_id}/uat-executions",
            body={"caseKey": key, "result": "PASS" if ok else "FAIL", "actualResult": actual[:900]})
        expect(status, 201, f"record UAT execution {key}", _)
        print(f"  UAT {key}: {'PASS' if ok else 'FAIL'} - {actual}")
        if not ok:
            register(engineer, state, "FAILED", "uat", failure=f"UAT check {key} failed: {actual}")
            die(f"UAT check {key} FAILED (recorded as FAIL): {actual}")


if __name__ == "__main__":
    main()

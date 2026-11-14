#!/usr/bin/env python3
"""
MigrateFlow showcase bootstrap (Automated Showcase spec, section 4).

One-time, idempotent, resumable orchestration that produces the public
showcase scenario by driving the REAL authenticated MigrateFlow workflow
over HTTP - project, memberships, upload, mapping, validation, reviewer
exclusions, manifest freeze, Spring Batch rehearsal, scripted-but-real
UAT checks, reviewer approval, engineer cutover - and finally registering
the resulting artifact IDs in the showcase registry through the
token-guarded internal endpoint (ADMIN principal). The registry is a
pointer only; the backend re-verifies lineage before it will accept (or
display) READY.

It never inserts into target/rehearsal/approval/report tables, never
resets anything, and fails closed: any failure is recorded as FAILED in
the registry (best effort) and exits non-zero with the original reason.

Prerequisites (operator/deployment side, spec 4.2):
  * Backend running with MIGRATEFLOW_SHOWCASE_BOOTSTRAP_ENABLED=true and
    MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN set to the same token given here.
  * Demo/operator accounts exist (the seeded demo users for local demos,
    or provisioned equivalents). Credentials come from the environment;
    the defaults below match DemoSeedRunner's LOCAL DEMO defaults only -
    never use them on a public deployment.

Environment:
  MIGRATEFLOW_BASE_URL                 default http://localhost:8081
  MIGRATEFLOW_SHOWCASE_SLUG            default hinata-trading
  MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN required
  MIGRATEFLOW_SHOWCASE_STATE_FILE      default scripts/.showcase-bootstrap-state.json
  MIGRATEFLOW_ADMIN_EMAIL              default admin@example.test
  MIGRATEFLOW_ENGINEER_EMAIL           default engineer@example.test
  MIGRATEFLOW_REVIEWER_EMAIL           default reviewer@example.test
  MIGRATEFLOW_AUDITOR_EMAIL            default auditor@example.test
  MIGRATEFLOW_ADMIN_PASSWORD / MIGRATEFLOW_ENGINEER_PASSWORD /
  MIGRATEFLOW_REVIEWER_PASSWORD / MIGRATEFLOW_AUDITOR_PASSWORD
      (same variable names DemoSeedRunner reads; demo defaults otherwise)
  MIGRATEFLOW_FIXTURES_DIR             default <repo>/fixtures

Usage:  python3 scripts/bootstrap-showcase.py
The DB registry is the canonical progress record; the local state file
is only a resume cache and is discarded when it disagrees with the
registry or points at artifacts that no longer exist.
"""
import csv
import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
import http.cookiejar
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FIXTURES = Path(os.environ.get("MIGRATEFLOW_FIXTURES_DIR", REPO / "fixtures"))
BASE = os.environ.get("MIGRATEFLOW_BASE_URL", "http://localhost:8081").rstrip("/")
SLUG = os.environ.get("MIGRATEFLOW_SHOWCASE_SLUG", "hinata-trading")
TOKEN = os.environ.get("MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN", "")
STATE_FILE = Path(os.environ.get(
    "MIGRATEFLOW_SHOWCASE_STATE_FILE",
    Path(__file__).resolve().parent / ".showcase-bootstrap-state.json"))
BOOTSTRAP_VERSION = "bootstrap-showcase.py/2.0"
PROJECT_NAME = "Hinata Trading Co. — Legacy Sales Migration"
ARTIFACT_KEYS = ("projectId", "bundleId", "mappingRevisionId", "ruleSetRevisionId",
                 "snapshotId", "manifestId", "rehearsalRunId", "approvalId", "cutoverRunId")


class BootstrapError(Exception):
    """A stage failure: recorded as FAILED in the registry, then exit 1."""


def fail(msg):
    raise BootstrapError(msg)


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
        except urllib.error.URLError as e:
            return 0, str(e)

    def login(self, email, password):
        self.request("GET", "/api/v1/auth/csrf")
        status, payload = self.request("POST", "/api/v1/auth/login",
                                       body={"email": email, "password": password})
        if status != 200:
            fail(f"login failed for {self.name} ({email}): HTTP {status} {payload}")
        self.request("GET", "/api/v1/auth/csrf")
        print(f"  signed in as {self.name} ({email})")

    def me(self):
        status, payload = self.request("GET", "/api/v1/auth/me")
        expect(status, 200, f"{self.name} /auth/me", payload)
        return payload


def creds(role):
    email = os.environ.get(f"MIGRATEFLOW_{role}_EMAIL", f"{role.lower()}@example.test")
    password = os.environ.get(f"MIGRATEFLOW_{role}_PASSWORD", f"{role.lower()}-dev-pw")
    return email, password


def expect(status, wanted, what, payload):
    if status != wanted:
        fail(f"{what}: expected HTTP {wanted}, got {status}: {payload}")
    return payload


def wait_for_backend(timeout=180):
    """Bounded readiness polling (review S-18): honest failure details."""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(BASE + "/actuator/health", timeout=5) as resp:
                if resp.status == 200:
                    print(f"  backend healthy at {BASE}")
                    return
                last = f"HTTP {resp.status}"
        except Exception as exc:
            last = str(exc)
        time.sleep(3)
    fail(f"backend at {BASE} did not become healthy within {timeout}s (last: {last})")


# ----------------------------------------------------------------------
# Registry (canonical progress) + local resume cache
# ----------------------------------------------------------------------

def load_state_file():
    try:
        if STATE_FILE.exists():
            return json.loads(STATE_FILE.read_text())
    except Exception:
        return {}
    return {}


def save_state(state):
    try:
        STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
        STATE_FILE.write_text(json.dumps(state, indent=2))
    except Exception as exc:
        print(f"  warning: could not persist local state cache: {exc}")


def register(admin: Client, state, setup_state, step, failure=None):
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
    state["step"] = step
    status, payload = admin.request("POST", "/api/v1/internal/showcase/register", body=body,
                                    headers={"X-Showcase-Bootstrap-Token": TOKEN})
    if status == 404:
        fail("showcase bootstrap is not enabled on the backend "
             "(set MIGRATEFLOW_SHOWCASE_BOOTSTRAP_ENABLED=true and restart it)")
    if status == 422:
        fail(f"backend refused READY - lineage problems: {payload}")
    expect(status, 200, "register showcase scenario", payload)
    if isinstance(payload, dict) and payload.get("id"):
        state["registryId"] = payload["id"]
        save_state(state)
    return payload


def recover_state_from_backend(admin: Client, engineer: Client, reviewer: Client, state: dict, expected: dict):
    """Authoritative DB recovery (MF-003): recovers state from PostgreSQL
    rather than depending on local cache. Re-validates artifact IDs, loads
    validation counts and exclusion decisions from DB, reads upload profile
    fingerprints, and checks rehearsal/cutover status."""
    status, existing = admin.request(
        "GET", f"/api/v1/internal/showcase/scenario?slug={SLUG}",
        headers={"X-Showcase-Bootstrap-Token": TOKEN})
    if status == 200 and isinstance(existing, dict):
        if state.get("registryId") and state["registryId"] != existing.get("id"):
            print("  local state cache belongs to a different registry ID; discarding local cache")
            state.clear()
        for key in ARTIFACT_KEYS:
            if existing.get(key):
                state[key] = existing[key]
        state["registryId"] = existing.get("id")
        state["setupState"] = existing.get("setupState")
        state["setupStep"] = existing.get("setupStep")
    elif status not in (404,):
        fail(f"registry lookup failed: HTTP {status} {existing}")

    # Re-verify and recover bundle file profile
    bid = state.get("bundleId")
    if bid:
        s_prof, prof = engineer.request("GET", f"/api/v1/bundles/{bid}/profile")
        if s_prof == 200 and "files" in prof:
            for f in prof["files"]:
                k = f.get("kind")
                if k == "CUSTOMERS":
                    state["customersFileId"] = f.get("fileId")
                    state["customersSha256"] = f.get("sha256")
                elif k == "ORDERS":
                    state["ordersFileId"] = f.get("fileId")
                    state["ordersSha256"] = f.get("sha256")
        else:
            print(f"  warning: bundle {bid} profile unavailable (HTTP {s_prof}); clearing bundle state")
            state.pop("bundleId", None)

    # Re-verify and recover validation snapshot counts and decisions
    snid = state.get("snapshotId")
    if snid:
        s_snap, snap = engineer.request("GET", f"/api/v1/validation-snapshots/{snid}")
        if s_snap == 200 and snap.get("status") == "COMPLETED":
            cnts = json.loads(snap["counts"]) if isinstance(snap["counts"], str) else snap["counts"]
            state["counts"] = cnts

            # Recover exclusions
            s_excl, excl = reviewer.request("GET", f"/api/v1/validation-snapshots/{snid}/exclusions")
            if s_excl == 200 and "content" in excl:
                state["decidedRows"] = sorted({f"{e['kind']}:{e['sourceRowId']}" for e in excl["content"]})
            else:
                state["decidedRows"] = []

            # Recover invalid rows
            issues, p = [], 0
            seen_issue_ids = set()
            while True:
                s_iss, res_iss = reviewer.request(
                    "GET", f"/api/v1/validation-snapshots/{snid}/issues?size=50&page={p}")
                if s_iss != 200:
                    break
                batch = res_iss.get("content", [])
                for iss in batch:
                    iid = iss.get("issueId")
                    if iid not in seen_issue_ids:
                        seen_issue_ids.add(iid)
                        issues.append(iss)
                eff_sz = res_iss.get("size", 50)
                if len(batch) < eff_sz or p >= 10000:
                    break
                p += 1
            invalid = {(i["kind"], i["sourceRowId"]): i for i in issues if i.get("severity") == "ERROR"}
            state["invalidRows"] = [{"kind": k[0], "dataRowNumber": v["dataRowNumber"]} for k, v in invalid.items()]
        elif s_snap != 200:
            print(f"  warning: validation snapshot {snid} not found (HTTP {s_snap}); clearing snapshot state")
            state.pop("snapshotId", None)

    # Re-verify and recover manifest
    mid = state.get("manifestId")
    if mid:
        s_man, man = engineer.request("GET", f"/api/v1/manifests/{mid}")
        if s_man == 200:
            state["manifestHash"] = man.get("manifestHash")
            state["manifestStatus"] = man.get("status")
        else:
            print(f"  warning: manifest {mid} not found (HTTP {s_man}); clearing manifest state")
            state.pop("manifestId", None)

    # Re-verify rehearsal reconciliation
    r_run_id = state.get("rehearsalRunId")
    if r_run_id:
        s_rrec, rrec = engineer.request("GET", f"/api/v1/runs/{r_run_id}/reconciliation")
        if s_rrec == 200 and rrec.get("status") == "PASS":
            state["rehearsalReportId"] = rrec.get("reportId")

    save_state(state)
    return state, existing if (status == 200 and isinstance(existing, dict)) else None


def adopt_state(admin: Client, engineer: Client):
    """Backwards compatibility alias."""
    state = load_state_file()
    status, existing = admin.request(
        "GET", f"/api/v1/internal/showcase/scenario?slug={SLUG}",
        headers={"X-Showcase-Bootstrap-Token": TOKEN})
    if status == 200:
        if state.get("registryId") and state["registryId"] != existing.get("id"):
            state = {}
        for key in ARTIFACT_KEYS:
            if existing.get(key):
                state[key] = existing[key]
        state["registryId"] = existing.get("id")
        save_state(state)
        return state, existing
    return state, None


def verify_ready_publicly():
    """Anonymous re-verification for idempotent READY re-runs (S-14)."""
    anon = Client("anonymous")
    status, overview = anon.request("GET", "/api/v1/showcase/overview")
    expect(status, 200, "public overview check", overview)
    if overview.get("setupState") != "READY" or overview.get("integrity") != "OK":
        fail(f"registry claims READY but the public integrity check reports "
             f"state={overview.get('setupState')} integrity={overview.get('integrity')} "
             f"problems={overview.get('integrityProblems')}")
    print("  public overview re-verified: READY with integrity OK; no writes performed")


# ----------------------------------------------------------------------
# Workflow stages (all real HTTP operations)
# ----------------------------------------------------------------------

def wait_run(client: Client, run_id, label, engineer: Client = None, max_restarts=2, timeout=900):
    """Wait for a run to finish. Detects FAILED_RESTARTABLE and applies a bounded retry policy (MF-004)."""
    deadline = time.time() + timeout
    restarts_left = max_restarts
    last = None
    while time.time() < deadline:
        status, run = client.request("GET", f"/api/v1/runs/{run_id}")
        expect(status, 200, f"poll {label} run", run)
        last = run["status"]
        if last in ("VERIFIED", "VERIFICATION_FAILED", "FAILED_TERMINAL"):
            return run
        if last == "FAILED_RESTARTABLE":
            if restarts_left > 0 and engineer is not None:
                print(f"  {label} run {run_id} is FAILED_RESTARTABLE; requesting restart ({restarts_left} left) ...")
                s_rst, r_rst = engineer.request("POST", f"/api/v1/runs/{run_id}/restart")
                expect(s_rst, 202, f"restart {label} run", r_rst)
                restarts_left -= 1
                time.sleep(3)
                continue
            else:
                fail(f"{label} run {run_id} is FAILED_RESTARTABLE but restart budget exhausted")
        time.sleep(3)
    fail(f"{label} run {run_id} did not finish within {timeout}s (last status {last})")


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


def run_stages(admin, engineer, reviewer, auditor, state, expected):
    pid = state.get("projectId")

    print("[2/6] Project, intake, mapping, validation ...")
    if not pid:
        status, project = engineer.request("POST", "/api/v1/projects", body={
            "name": PROJECT_NAME,
            "description": "Automated public showcase scenario: synthetic Hinata Trading Co. legacy sales data, "
                           "executed end-to-end by the real MigrateFlow workflow with automated demo actors."})
        expect(status, 201, "create project", project)
        pid = state["projectId"] = project["id"]
        # Memberships: actor IDs from their own /auth/me, granted by ADMIN
        # through the existing PUT members endpoint (review S-04).
        for actor, role in ((reviewer, "REVIEWER"), (auditor, "AUDITOR")):
            user_id = actor.me()["id"]
            s2, p2 = admin.request("PUT", f"/api/v1/projects/{pid}/members/{user_id}",
                                   body={"active": True})
            expect(s2, 200, f"grant {role} membership", p2)
        save_state(state)
        register(admin, state, "PREPARING", "project")

    if not state.get("bundleId"):
        status, bundle = multipart_upload(
            engineer, f"/api/v1/projects/{pid}/bundles",
            files={"customers": FIXTURES / "customers_1000.csv", "orders": FIXTURES / "orders_2000.csv"},
            fields={"exportedAt": "2025-03-31T00:00:00Z"})
        expect(status, 201, "upload bundle", bundle)
        state["bundleId"] = bundle["bundleId"]
        state["customersFileId"] = bundle["customers"]["fileId"]
        state["ordersFileId"] = bundle["orders"]["fileId"]
        state["customersSha256"] = bundle["customers"]["sha256"]
        state["ordersSha256"] = bundle["orders"]["sha256"]
        row_counts = {"CUSTOMERS": bundle["customers"]["rowCount"], "ORDERS": bundle["orders"]["rowCount"]}
        if row_counts["CUSTOMERS"] != expected["customers"]["rawRows"] or row_counts["ORDERS"] != expected["orders"]["rawRows"]:
            fail(f"uploaded row counts disagree with fixtures: {row_counts}")
        save_state(state)
        register(admin, state, "PREPARING", "intake")
        print(f"  bundle {state['bundleId']} uploaded: {row_counts}")

    if not state.get("mappingRevisionId"):
        status, mapping = engineer.request("POST", f"/api/v1/projects/{pid}/mapping-revisions")
        expect(status, 201, "publish mapping", mapping)
        state["mappingRevisionId"] = mapping["revisionId"]
        state["ruleSetRevisionId"] = mapping["ruleSetRevisionId"]
        save_state(state)
        register(admin, state, "PREPARING", "mapping")

    if not state.get("snapshotId"):
        status, started = engineer.request("POST", f"/api/v1/projects/{pid}/validation-snapshots", body={
            "bundleId": state["bundleId"], "mappingRevisionId": state["mappingRevisionId"],
            "ruleSetRevisionId": state["ruleSetRevisionId"]})
        expect(status, 202, "start validation", started)
        state["snapshotId"] = started["snapshotId"]
        save_state(state)
    snapshot = None
    deadline = time.time() + 300
    while time.time() < deadline:
        status, snapshot = engineer.request("GET", f"/api/v1/validation-snapshots/{state['snapshotId']}")
        expect(status, 200, "poll validation snapshot", snapshot)
        if snapshot["status"] in ("COMPLETED", "FAILED"):
            break
        time.sleep(2)
    if not snapshot or snapshot["status"] != "COMPLETED":
        fail(f"validation did not complete: {snapshot}")
    counts = json.loads(snapshot["counts"]) if isinstance(snapshot["counts"], str) else snapshot["counts"]
    if counts["customers"]["valid"] != expected["customers"]["acceptedRows"] or counts["orders"]["valid"] != expected["orders"]["acceptedRows"]:
        fail(f"validation counts disagree with fixture expectations: {counts}")
    state["counts"] = counts
    save_state(state)
    register(admin, state, "PREPARING", "validation")
    print(f"  validation COMPLETED: {counts}")

    print("[3/6] Reviewer exclusions for every invalid row ...")
    if state.get("manifestStatus") == "FROZEN" or (state.get("manifestId") and state.get("step") in ("freeze", "rehearsal", "uat", "approval", "cutover", "publish")):
        print(f"  manifest {state.get('manifestId')} is already FROZEN; skipping new exclusions and using persisted decisions")
    else:
        issues, page = [], 0
        seen_issue_ids = set()
        while True:
            status, result = reviewer.request(
                "GET", f"/api/v1/validation-snapshots/{state['snapshotId']}/issues?size=50&page={page}")
            expect(status, 200, "list validation issues", result)
            batch = result.get("content", [])
            ret_page = result.get("page", page)
            if ret_page != page:
                fail(f"server page clamping or mismatch detected for issues at page={page}, got {ret_page}")
            for issue in batch:
                issue_id = issue.get("issueId")
                if issue_id not in seen_issue_ids:
                    seen_issue_ids.add(issue_id)
                    issues.append(issue)
            effective_size = result.get("size", 50)
            if len(batch) < effective_size:
                break
            page += 1
            if page > 10000:
                fail("exceeded maximum page count (10,000) during validation issues scan")
        invalid = {(i["kind"], i["sourceRowId"]): i for i in issues if i.get("severity") == "ERROR"}
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
        if len(invalid) != total_invalid:
            fail(f"expected {total_invalid} invalid rows, saw {len(invalid)}")
        state["invalidRows"] = [{"kind": k[0], "dataRowNumber": v["dataRowNumber"]} for k, v in invalid.items()]
        save_state(state)
        register(admin, state, "PREPARING", "exclusions")
        print(f"  {len(invalid)} invalid rows excluded by REVIEWER (none valid touched)")

    print("[4/6] Freeze manifest and run the real rehearsal ...")
    if not state.get("manifestId"):
        status, manifest = engineer.request("POST", f"/api/v1/projects/{pid}/manifests", body={
            "bundleId": state["bundleId"], "mappingRevisionId": state["mappingRevisionId"],
            "ruleSetRevisionId": state["ruleSetRevisionId"], "validationSnapshotId": state["snapshotId"]})
        expect(status, 201, "freeze manifest", manifest)
        if manifest["status"] != "FROZEN":
            fail(f"manifest is not FROZEN: {manifest}")
        state["manifestId"] = manifest["manifestId"]
        state["manifestHash"] = manifest["manifestHash"]
        state["manifestStatus"] = "FROZEN"
        save_state(state)
        register(admin, state, "PREPARING", "freeze")

    if not state.get("rehearsalRunId"):
        status, launched = engineer.request("POST", f"/api/v1/manifests/{state['manifestId']}/rehearsals")
        expect(status, 202, "launch rehearsal", launched)
        state["rehearsalRunId"] = launched["runId"]
        save_state(state)
    run = wait_run(engineer, state["rehearsalRunId"], "rehearsal", engineer=engineer)
    if run["status"] != "VERIFIED":
        fail(f"rehearsal did not verify: {run}")
    register(admin, state, "PREPARING", "rehearsal")
    status, report = engineer.request("GET", f"/api/v1/runs/{state['rehearsalRunId']}/reconciliation")
    expect(status, 200, "rehearsal reconciliation", report)
    if report["status"] != "PASS":
        fail(f"rehearsal reconciliation is not PASS: {report}")
    state["rehearsalReportId"] = report["reportId"]
    save_state(state)
    print(f"  rehearsal VERIFIED, reconciliation PASS ({report['reportId']})")

    print("[5/6] Scripted UAT - real checks, recorded by REVIEWER ...")
    run_uat_checks(engineer, reviewer, state, json.loads(report["summary"]))
    register(admin, state, "PREPARING", "uat")

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
    register(admin, state, "PREPARING", "approval")
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
    run = wait_run(engineer, state["cutoverRunId"], "cutover", engineer=engineer)
    if run["status"] != "VERIFIED":
        fail(f"cutover did not verify: {run}")
    status, final_report = engineer.request("GET", f"/api/v1/runs/{state['cutoverRunId']}/reconciliation")
    expect(status, 200, "cutover reconciliation", final_report)
    if final_report["status"] != "PASS":
        fail(f"cutover reconciliation is not PASS: {final_report}")
    summary = json.loads(final_report["summary"])
    exp_total = json.loads((FIXTURES / "expected_results.json").read_text())["orders"]["acceptedAmountTotalYen"]
    if summary["orders"]["writtenAmountYen"] != exp_total:
        fail(f"cutover written total disagrees with fixtures: {summary}")

    register(admin, state, "READY", "publish")
    print(f"Showcase READY: project={pid} manifest={state['manifestId']} "
          f"rehearsal={state['rehearsalRunId']} cutover={state['cutoverRunId']}")
    print(f"Public overview: {BASE}/api/v1/showcase/overview")


# ----------------------------------------------------------------------
# The five UAT checks (spec 4.4): each PASSes only on real assertions
# against live artifacts; a failed check is recorded FAIL and aborts.
# ----------------------------------------------------------------------

def rehearsal_rows(client, run_id, kind):
    rows, page = [], 0
    seen_ids = set()
    while True:
        status, result = client.request("GET", f"/api/v1/rehearsals/{run_id}/{kind}?size=50&page={page}")
        expect(status, 200, f"list rehearsal {kind}", result)
        batch = result.get("content", [])
        ret_page = result.get("page", page)
        if ret_page != page:
            fail(f"server page clamping or mismatch detected at page={page}, got {ret_page}")
        for r in batch:
            row_id = r.get("id")
            if row_id in seen_ids:
                fail(f"duplicate record detected during rehearsal pagination at page {page}: {row_id}")
            seen_ids.add(row_id)
            rows.append(r)
        effective_size = result.get("size", 50)
        if len(batch) < effective_size:
            return rows
        page += 1
        if page > 10000:
            fail(f"exceeded maximum page count (10,000) during rehearsal {kind} scan")


def search_rehearsal(client, run_id, kind, q):
    status, result = client.request("GET", f"/api/v1/rehearsals/{run_id}/{kind}?q={q}")
    expect(status, 200, f"search rehearsal {kind}", result)
    return result["content"]


def run_uat_checks(engineer, reviewer, state, rehearsal_summary):
    run_id = state["rehearsalRunId"]
    counts = state["counts"]
    status, uat = reviewer.request("GET", f"/api/v1/rehearsals/{run_id}/uat")
    expect(status, 200, "list UAT cases", uat)
    cases = {c["caseKey"]: c for c in uat["cases"]}

    _, cust_by_id, _ = read_csv(FIXTURES / "customers_1000.csv")
    _, order_by_id, _ = read_csv(FIXTURES / "orders_2000.csv")
    invalid_numbers = {"CUSTOMERS": set(), "ORDERS": set()}
    for item in state["invalidRows"]:
        invalid_numbers[item["kind"]].add(item["dataRowNumber"])
    expected_customers = {ext for ext, (n, _) in cust_by_id.items() if n not in invalid_numbers["CUSTOMERS"]}
    expected_orders = {ext for ext, (n, _) in order_by_id.items() if n not in invalid_numbers["ORDERS"]}

    cache = {}

    def target_ids(kind):
        if kind not in cache:
            id_key = "externalCustomerId" if kind == "customers" else "externalOrderId"
            cache[kind] = {r[id_key] for r in rehearsal_rows(engineer, run_id, kind)}
        return cache[kind]

    def check_counts():
        c_ids, o_ids = target_ids("customers"), target_ids("orders")
        ok = (c_ids == expected_customers and o_ids == expected_orders
              and rehearsal_summary["customers"]["accepted"] == counts["customers"]["valid"]
              and rehearsal_summary["customers"]["written"] == counts["customers"]["valid"]
              and rehearsal_summary["orders"]["accepted"] == counts["orders"]["valid"]
              and rehearsal_summary["orders"]["written"] == counts["orders"]["valid"])
        return ok, (f"rehearsal target key sets equal the accepted snapshot exactly "
                    f"({len(c_ids)} customers, {len(o_ids)} orders; no missing, no extra); "
                    f"reconciliation accepted/written and validation counts agree")

    def check_customers():
        samples = rehearsal_rows(engineer, run_id, "customers")[:3]
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
        expected_total = sum(int(r[3]) for n, r in order_by_id.values() if n not in invalid_numbers["ORDERS"])
        ok = (rehearsal_summary["orders"]["acceptedAmountYen"] == expected_total
              == rehearsal_summary["orders"]["writtenAmountYen"])
        samples = rehearsal_rows(engineer, run_id, "orders")[:3]
        for row in samples:
            _, src = order_by_id[row["externalOrderId"]]
            if row["amountYen"] != int(src[3]) or row["externalCustomerId"] != src[1]:
                return False, f"{row['externalOrderId']}: target row disagrees with source {src}"
        return ok, (f"order totals agree: source-derived expected ¥{expected_total:,}, report accepted "
                    f"¥{rehearsal_summary['orders']['acceptedAmountYen']:,}, written "
                    f"¥{rehearsal_summary['orders']['writtenAmountYen']:,}; 3 sampled orders match source amounts")

    def check_exclusions_absent():
        c_ids, o_ids = target_ids("customers"), target_ids("orders")
        leaked = []
        for item in state["invalidRows"]:
            by_id = cust_by_id if item["kind"] == "CUSTOMERS" else order_by_id
            target = c_ids if item["kind"] == "CUSTOMERS" else o_ids
            external_id = next(ext for ext, (n, _) in by_id.items() if n == item["dataRowNumber"])
            if external_id in target:
                leaked.append(external_id)
        if leaked:
            return False, f"excluded rows present in rehearsal target: {leaked}"
        return True, (f"all {len(state['invalidRows'])} excluded source rows are absent from the rehearsal "
                      f"targets (full key-set comparison, not sampling)")

    def check_provenance():
        # Source-file identity: the stored SHA-256 from the upload response
        # must equal the hash of the checked-in fixture bytes.
        for kind, fixture, key in (("customers", "customers_1000.csv", "customersSha256"),
                                   ("orders", "orders_2000.csv", "ordersSha256")):
            digest = hashlib.sha256((FIXTURES / fixture).read_bytes()).hexdigest()
            if state.get(key) != digest:
                return False, f"{kind}: stored file sha256 {state.get(key)} != fixture bytes {digest}"
        status, run = engineer.request("GET", f"/api/v1/runs/{run_id}")
        expect(status, 200, "run detail for provenance", run)
        if run["manifestId"] != state["manifestId"]:
            return False, f"run manifest {run['manifestId']} != frozen manifest {state['manifestId']}"
        for kind, by_id in (("customers", cust_by_id), ("orders", order_by_id)):
            for row in rehearsal_rows(engineer, run_id, kind)[:3]:
                ext = row["externalCustomerId"] if kind == "customers" else row["externalOrderId"]
                if row["sourceRowNumber"] != by_id[ext][0]:
                    return False, f"{ext}: source_row_number {row['sourceRowNumber']} != CSV row {by_id[ext][0]}"
        return True, ("source files verified by SHA-256 against the checked-in fixture bytes; sampled rows "
                      "carry source row numbers matching their CSV positions; the run is bound to the frozen "
                      f"manifest {state['manifestId']}")

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
            fail(f"required UAT case {key} was not seeded for the rehearsal")
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
            fail(f"UAT check {key} FAILED (recorded as FAIL): {actual}")


# ----------------------------------------------------------------------

def main():
    if not TOKEN:
        print("BOOTSTRAP FAILED: MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN is not set", file=sys.stderr)
        sys.exit(1)
    expected = json.loads((FIXTURES / "expected_results.json").read_text())

    admin = Client("admin")
    state = load_state_file()

    try:
        print(f"[1/6] Waiting for backend and signing in demo actors against {BASE} ...")
        wait_for_backend()
        admin.login(*creds("ADMIN"))
        engineer = Client("engineer"); engineer.login(*creds("ENGINEER"))
        reviewer = Client("reviewer"); reviewer.login(*creds("REVIEWER"))
        auditor = Client("auditor"); auditor.login(*creds("AUDITOR"))

        state, existing = recover_state_from_backend(admin, engineer, reviewer, state, expected)
        if existing and existing.get("setupState") == "READY":
            print(f"Showcase '{SLUG}' is already bootstrapped and READY "
                  f"(project {existing.get('projectId')}, cutover {existing.get('cutoverRunId')}).")
            verify_ready_publicly()
            return
        if existing:
            print(f"  resuming from registry state {existing.get('setupState')} at step {existing.get('setupStep')}")

        run_stages(admin, engineer, reviewer, auditor, state, expected)
    except Exception as exc:
        # Fail closed (MF-012): record FAILED with the original reason in registry if possible
        try:
            register(admin, state, "FAILED", state.get("step", "init"), failure=str(exc))
        except Exception as reg_exc:
            print(f"  warning: could not record FAILED in the registry: {reg_exc}", file=sys.stderr)
        print(f"BOOTSTRAP FAILED: {exc}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()

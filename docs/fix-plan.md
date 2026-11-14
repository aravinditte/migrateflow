# MigrateFlow — Implementation-Ready Fix Plan

**Repository:** https://github.com/aravinditte/migrateflow  
**Review baseline:** `main` at [`11d4403de9dffd5b63e6d325619ef988e80d80db`](https://github.com/aravinditte/migrateflow/commit/11d4403de9dffd5b63e6d325619ef988e80d80db)  
**Date:** 14 November 2026  
**Scope:** Repair the public automated showcase and prevent regressions in the existing migration workbench.  
**Source of findings:** Source and controller review plus the failing [GitHub Actions run #43618831542](https://github.com/aravinditte/migrateflow/actions/runs/43618831542).  
**Status:** Fix specification only. **No code changes, CI runs, or production executions are claimed by this document.** Recheck `main` before making changes.

## 1. Objective and non-negotiable requirements

MigrateFlow is a bounded legacy CSV-to-PostgreSQL migration workbench for the fictional **Hinata Trading Co.** The public showcase must let a recruiter immediately inspect a **completed, genuine, auditable** migration without logging in, uploading files, switching roles, or initiating cutover.

> **Only the input business dataset may be synthetic.** Spring Boot, Spring Batch, PostgreSQL writes, validation, reviewer exclusions, reconciliation, automated UAT checks, approval gates, cutover, audit trail, hashes, and public evidence must be executed and persisted by the real application. Do **not** replace the backend with mock responses, hardcoded metrics, fabricated reports, fake reviewer sign-off, or static “success” screenshots.

Preserve the full 11-stage operator workflow, role separation, immutable manifests, source provenance, safe restart, idempotent cutover, and the public **GET-only** evidence experience. The showcase registry stores pointers to real domain artifacts; it is **not** an independent source of business metrics. Automated actors must remain explicitly labeled as demonstration actors rather than human approval.

Expected **fixture-derived** assertions (not UI constants):

| Measure | Expected value |
|---|---:|
| Raw customers | 1,000 |
| Raw orders | 2,000 |
| Accepted customers | 995 |
| Accepted orders | 1,990 |
| Excluded customers / orders | 5 / 10 |
| Total accepted target records | 2,985 |
| Accepted and written order value | ¥21,998,650 |
| Required UAT cases | 5 passing |

The public page must obtain its actual numbers from persisted backend results and expose evidence hashes, timestamps, and provenance. Fixture expectations are for tests and independent verification only.

## 2. Baseline findings and severity

The previous review was against `main` commit `11d4403`. PR #5 merged many earlier repairs, including the `UatService.CaseView` fix, backend endpoint alignment, reconciliation JSON keys, approval state rendering, writable Docker bootstrap state, and initial public lineage gates. **Do not re-open those as unfixed without comparing the latest source.**

The latest reviewed Actions run had **frontend PASS, backend FAIL before tests**. Its backend compiler log reported **15 errors** from the shared pagination change:

| Priority | ID | Finding | File(s) |
|---|---|---|---|
| **P0** | MF-001 | 15 Java compilation errors: missing `validatePage` / `validateSize`; `long`-to-`int` conversions | `PageRequestUtil`, 4 controllers |
| **P0** | MF-002 | Python UAT full-set scan stops after first 50 rows because backend caps `size=100` to 50 | `scripts/bootstrap-showcase.py` |
| **P1** | MF-003 | Bootstrap resume depends on local cache; missing metadata or exclusion history causes invalid/repeated operations | Bootstrap + existing read APIs |
| **P1** | MF-004 | Bootstrap does not recover a restartable rehearsal/cutover run | Bootstrap + `RunController` |
| **P1** | MF-005 | Public Nginx forwards all `/api/` paths, including authenticated operator APIs | Nginx + deployment configuration |
| **P1** | MF-006 | Default demo users/passwords are unsafe when public operator endpoints can be reached | Compose + seed + deployment guidance |
| **P1** | MF-007 | Real Python bootstrap is not running in a merged CI workflow | `.github/workflows/` |
| **P2** | MF-008 | Current integrity verification does not explicitly check that linked approval decision is `APPROVED` | `ShowcaseQueryService` |
| **P2** | MF-009 | Public records include email fields; source disclosure is controlled by field names but should also be bound to the synthetic showcase dataset | `ShowcaseQueryService` + deployment rules |
| **P2** | MF-010 | STALE overview renders many detail sections even though detail API correctly returns HTTP 503 | `PublicShowcase.tsx` |
| **P2** | MF-011 | Frontend contract test is an authored fixture, not live backend contract verification | Frontend tests + E2E |
| **P2** | MF-012 | Bootstrap failure reporting covers `run_stages` errors but not all preflight/login/adoption errors; independent verifier lacks deeper evidence checks | Bootstrap + verifier |

**Verification labels:** “Source-confirmed” means a mismatch or code behavior is visible in the reviewed source; “CI-confirmed” means observed in Actions logs; “Not executed” means runtime behavior remains unproved until a real integration run.

---

## 3. P0 — Restore build and genuine UAT

### MF-001 — Align shared pagination utility with all callers

**Evidence:** [latest failed CI run](https://github.com/aravinditte/migrateflow/actions/runs/43618831542). `PageRequestUtil.java` defines `clampPage(int)`, `clampSize(int)`, `safeOffset(int,int): long`. The following controllers still invoke nonexistent `validatePage(int)`, `validateSize(int)` and assign the `long` offset to `int`:

- `backend/src/main/java/com/migrateflow/execution/RunController.java` — two endpoints, six errors, around lines 106–108 and 135–137.
- `backend/src/main/java/com/migrateflow/intake/IntakeController.java` — three errors around lines 93–95.
- `backend/src/main/java/com/migrateflow/project/ProjectController.java` — two endpoints, six errors, around lines 57–59 and 182–184.
- `backend/src/main/java/com/migrateflow/validation/PlanController.java` — three errors around lines 146–148.

**Required fix:** Pick one shared pagination contract and update every caller. The least disruptive fix for the current implementation is:

```java
int validPage = PageRequestUtil.clampPage(page);
int validSize = PageRequestUtil.clampSize(size);
long offset = PageRequestUtil.safeOffset(validPage, validSize);
```

Pass `offset` to prepared SQL/JDBC as a `long`. Do **not** cast it to `int` or modify the utility to hide the compilation error. Search **all** Java call sites of `PageRequestUtil`, not only the four files listed. Review whether clamping versus rejecting invalid request parameters is intentional and document one consistent behavior. Add boundary tests for negative page, zero size, excessive size, very large page, and offset arithmetic. Inspect whether a 10,000-page cap would silently return the same page repeatedly to clients; prefer explicit rejection or a clearly documented cap when correctness matters.

**Acceptance:** `./mvnw -B verify` compiles and runs on Java 21 + PostgreSQL 16; zero compiler errors. All pagination endpoints return bounded, correct, non-overlapping pages, and old operator behavior is not silently broken.

### MF-002 — Fix Python full-dataset pagination (critical to authentic UAT)

**Evidence:** `scripts/bootstrap-showcase.py` around lines 486–494 requests `size=100` and stops when `len(content) < 100`; `PageRequestUtil.MAX_PAGE_SIZE == 50`. A successful first response is therefore 50 records and is falsely treated as the last page. The downstream UAT `COUNTS_MATCH` compares the resulting 50 IDs with the expected **995/1,990** complete ID sets and fails despite valid backend data.

**Required fix:** Request at most 50 items and derive the termination threshold from the **effective `result["size"]`**, not the requested size. Ensure that the returned page number advances and never repeats indefinitely. Example:

```python
def rehearsal_rows(client, run_id, kind):
    rows = []
    page = 0
    while True:
        status, result = client.request(
            "GET",
            f"/api/v1/rehearsals/{run_id}/{kind}?size=50&page={page}"
        )
        expect(status, 200, f"list rehearsal {kind}", result)
        batch = result["content"]
        rows.extend(batch)
        effective_size = result["size"]
        if len(batch) < effective_size:
            return rows
        page += 1
```

Also replace the `size=100` assumption in the validation-issues scan around lines 373–381 with the same pagination pattern. **Important:** the `MAX_PAGE` cap currently truncates navigation at 10,000; implement a guard that detects clamped/repeated pages rather than silently claiming completeness.

Do not weaken `COUNTS_MATCH`, `EXCLUSIONS_ABSENT`, or source-file SHA checks; they must continue to assert **complete** accepted key sets and live evidence. Add tests with >50, exactly 50, 51, 995, and 1,990 records; ensure no duplicate or missing keys and predictable termination on a backend error.

**Acceptance:** Real bootstrap's full-set UAT processes every accepted record (995 customers + 1,990 orders) and records five genuine PASS executions only after checks actually succeed.

---

## 4. P1 — Runtime recovery, deployment security, and CI

### MF-003 — Make recovery authoritative from PostgreSQL, not the local JSON cache

**Evidence:** `adopt_state()` (bootstrap around lines 215–246) repopulates registry artifact IDs, but local `decidedRows`, `invalidRows`, validation `counts`, file SHA-256 values, and other metadata can be absent or stale. After freeze, replaying exclusions fails with `MANIFEST_ALREADY_FROZEN`; missing SHA hashes fail provenance checks. A cached registry ID match is not proof that cached stage fields are complete or current.

**Required fix:** Implement `recover_state_from_backend()` with a clearly defined schema:

1. Use the authorized `/api/v1/internal/showcase/scenario?slug=...` registry pointer as the canonical step/artifact source.
2. Fetch snapshot status/counts from `GET /api/v1/validation-snapshots/{snapshotId}` when present.
3. Read actual decisions through `GET /api/v1/validation-snapshots/{snapshotId}/exclusions`; rebuild `decidedRows` and `invalidRows` by joining persisted decisions with validation issues. Never repeat a previously persisted decision just because local JSON was lost.
4. Read upload fingerprints from `GET /api/v1/bundles/{bundleId}/profile` (per-kind source file SHA-256, IDs and row counts). Compare those hashes to the **fixture bytes** before trusting them; never manufacture provenance values.
5. Recover `rehearsalReportId` from `GET /api/v1/runs/{runId}/reconciliation` and check PASS. Recover live run states through `/runs/{id}`.
6. Validate each registry reference belongs to the same project/manifest/snapshot; never silently combine cached IDs from unrelated databases.
7. Make local state an optional **atomic-write cache** only. Resume must work when the volume is missing, empty, corrupt, or stale.
8. If a manifest is already frozen, skip the exclusion/freeze mutations after verifying persisted decisions and manifest bindings; report a useful error if evidence is inconsistent.

Test interruptions after each stage; clear the state volume, restart the script against the original DB, and verify no duplicate decisions, second manifest, second cutover, or invented PASS statuses.

**Acceptance:** At every recoverable checkpoint, a fresh bootstrap container with **no local state file** safely resumes from DB evidence or fails with a precise actionable diagnostic.

### MF-004 — Handle `FAILED_RESTARTABLE` runs safely

**Evidence:** `wait_run()` polls until `VERIFIED`, `VERIFICATION_FAILED`, or `FAILED_TERMINAL`; `FAILED_RESTARTABLE` is not treated as a restart action. It eventually reaches timeout instead of calling the supported `POST /api/v1/runs/{runId}/restart`.

**Required fix:** For rehearsal and cutover, detect `FAILED_RESTARTABLE`, log original error, apply a **bounded retry policy** (e.g., maximum 1–2 restarts), call the existing restart endpoint as ENGINEER, and poll the **same run ID**. Respect server-side eligibility and separation-of-duties gates. Distinguish terminal failures (`VERIFICATION_FAILED`, `FAILED_TERMINAL`) from recoverable ones; never reset or claim a second cutover, and never bypass the existing applied-rows ledger.

**Acceptance:** Fault-injected failure followed by application restart yields either a safely resumed VERIFIED run under the same run ID or a clearly recorded FAILED scenario. Re-running the bootstrap never creates duplicate target records.

### MF-005 — Isolate the anonymous showcase from operator endpoints

**Evidence:** `infra/nginx/nginx.conf` forwards every `/api/` request to the backend, despite Docker host port 8081 being loopback-bound. When the frontend is exposed via a public TLS proxy, users may still reach operator endpoints on the same public hostname.

**Required fix:** Provide a distinct **public deployment** Nginx configuration and, preferably, separate network or hostname boundary. The public host should allow only the exact permitted anonymous `GET /api/v1/showcase/...` routes required by the UI, plus static frontend assets as intended. Explicitly reject `/api/v1/auth/**`, `/api/v1/projects/**`, `/api/v1/internal/**`, other operator APIs, mutation methods, and unnecessary actuator routes. Keep operator access on a private/admin interface protected by stronger authentication and network policy. Preserve local development workbench behavior via a separate development configuration.

Review the **actual reverse-proxy path and method matching**, not just Spring's role checks. Confirm the operator sign-in button on a public deployment does not advertise a login flow that is blocked or unsafe; hide it or route to a separately secured operator origin.

**Acceptance:** On the publicly exposed origin, anonymous showcase GETs work; POST/PUT/PATCH/DELETE to showcase and any access to operator, auth, internal-bootstrap, or health endpoints is denied by the proxy. A separate authorized operator workflow remains functional in the correct private environment.

### MF-006 — Remove predictable credentials from public deployments

**Evidence:** `docker-compose.yml` and `.env.example` default to `MIGRATEFLOW_DEMO_SEED_ENABLED=true` and development `*-dev-pw` credentials. Seeding occurs only when no users exist, so disabling seed **does not deactivate already-created demo accounts**.

**Required fix:** Create distinct `dev` and `public` deployment profiles/configuration with secure fail-closed defaults. Require secrets from a managed secret source or securely provisioned environment variables; no default login passwords in a public profile. On existing databases, rotate credentials or explicitly disable/delete seeded demo accounts through an authorized administrative process, without breaking historical actor foreign keys or audit evidence. Turn bootstrap flag/token off after the successful one-time initialization. Add a production startup/deployment check that refuses insecure seed defaults.

**Acceptance:** Public deployment cannot be started using documented sample credentials, historical demo users are not login-capable from the public internet, and public anonymous showcase reads still work.

### MF-007 — Add real deployment-level Python bootstrap CI

**Evidence:** Repository tree contains `.github/workflows/ci.yml`, but **not** `.github/workflows/showcase.yml`; PR #5 notes that the intended workflow was not pushed because GitHub workflow permission was unavailable. Existing `ShowcaseBootstrapTest.java` drives the backend through Java test helpers, **not** the actual Python file.

**Required fix:** Add a workflow through GitHub's web editor or a token that has **Actions workflow write permission**. Do not pretend that a workflow mentioned in PR prose is merged. Its jobs must:

1. Check out the exact PR commit and start a fresh PostgreSQL 16 instance and real Spring Boot backend (Java 21).
2. Supply isolated CI-only actor passwords and bootstrap token, start app/Flyway and await `/actuator/health`.
3. Run **the actual** `python3 scripts/bootstrap-showcase.py` against the backend and checked-in CSV fixtures.
4. Run **the actual** `python3 scripts/verify-showcase.py` as an anonymous client.
5. Verify both full key sets, 11 completed stages, five UAT PASSes, approved decision, rehearsal/cutover PASS reports, final target row counts and ¥21,998,650 sum, lineage, hashes and cutover ledger.
6. Re-run the actual bootstrap with the same database. Assert no extra project/manifest/cutover/target writes; accepted READY state persists.
7. Simulate a controlled interruption and loss of local state in another test/isolated database, then resume; verify no duplicates.
8. Include anonymous browser smoke testing against the served frontend: initial showcase, evidence panels, before/after records, evidence JSON, backend-not-ready/error states.
9. Upload logs/artifacts, redact passwords and tokens, fail loudly on any skipped scenario or missing evidence. No `continue-on-error` for showcase acceptance.

A separate job may verify public-proxy denial of operator routes. Keep the existing Java backend tests, frontend Vitest/TypeScript/build, and the new deployment workflow all required for release.

**Acceptance:** A GitHub Actions run from the latest intended commit completes all **actual** bootstrap, verifier, repeat-run and browser tests with green status. Include the run link in the release notes.

---

## 5. P2 — Integrity, disclosure, UI, and verification quality

### MF-008 — Explicitly verify persisted approval decision and lineage

**Evidence:** `ShowcaseQueryService.verifyLineage()` checks approval references and UAT hash, but the current query does not check `decision = 'APPROVED'`; `ApprovalController` persists `APPROVED` for a submitted `APPROVE` request.

**Required fix:** Add `decision` to the approval lookup and require `APPROVED`. Check the approval's reviewer, manifest/rehearsal/report references and consistency as already intended. Add a regression that changes only the approval decision and confirms the overview becomes `STALE` / `INTEGRITY_ALERT`, while detail APIs fail closed. Prefer a predictable API error rather than a raw 500 if an artifact disappears.

### MF-009 — Protect source/target disclosures

**Evidence:** Public `records()` filters source JSON by known field names and cuts target rows down to the linked `cutoverRunId`, which is an improvement. But whitelisted customer fields include email addresses, and a field allowlist does not itself prove the data is synthetic.

**Required fix:** Require the published showcase's dataset to be a designated synthetic fixture and verify its uploaded file hashes/classification before exposing per-record fields. Do not expose arbitrary operator projects, paths, credentials or private source content through the public registry. For extra defense, consider masking customer emails even in the synthetic dataset if the portfolio does not need them. Add tests with a non-showcase project and unexpected source fields; no leakage permitted.

### MF-010 — Make STALE user experience coherent

**Evidence:** `PublicShowcase.tsx` renders timeline, validation, before/after, reconciliation, UAT and audit even when the overview says `STALE`; their backend `readyScenario()` deliberately returns HTTP 503 when lineage is inconsistent.

**Required fix:** On STALE/INTEGRITY_ALERT, display a clear integrity warning, last verified-at timestamp and available non-sensitive diagnostic information. Hide or explicitly mark detail panels unavailable rather than emitting a pageful of unrelated 503 errors. Do not relabel past verification as current success; do not fall back to cached fixture metrics. Add UI tests for `NOT_READY`, `PREPARING`, `FAILED`, `STALE` and `READY`.

### MF-011 — Test real backend/TypeScript response contracts

**Evidence:** `frontend/src/lib/showcase.contract.test.ts` verifies a **manually authored** reconciliation object against a TypeScript interface. This protects the expected key spelling but cannot detect backend drift by itself.

**Required fix:** Keep the fixture test, but supplement it with a live backend contract/e2e job that fetches public overview, reconciliation, UAT, records, mapping, validation, audit, and evidence JSON after actual bootstrap. Assert schemas, required fields, status values, and meaningful timestamps. Optionally use generated OpenAPI/JSON-schema contracts if maintained by the backend.

### MF-012 — Complete failure reporting and independent evidence verification

**Evidence:** The `try/except BootstrapError` in `main()` surrounds `run_stages`, but backend readiness, login and `adopt_state()` happen before it. Some early errors therefore exit without a persisted FAILED registration. `verify-showcase.py` validates top-level expected counts/status but is not an exhaustive independent database or per-record hash verifier.

**Required fix:** Move the recoverable bootstrap control flow under a centralized exception boundary while keeping secrets out of logs. Record FAILED whenever an authenticated, token-authorized registry write is possible; if the service is down or auth is impossible, emit a clear nonzero error and explicitly state why registry persistence was unavailable. Expand the anonymous verifier to check full key-set pagination, provenance samples, both report hashes, cross-view IDs and the expected 11-stage and UAT evidence invariants; add direct database assertions in the CI-only verification stage. **Do not claim that anonymous API reads alone independently prove an untampered target at per-row level.**

---

## 6. Implementation sequence (do not skip gates)

| Gate | Change set | Required evidence before proceeding |
|---|---|---|
| **G0** | Fresh branch from latest `main`, capture commit SHA | Branch is isolated; no direct writes to `main` |
| **G1** | MF-001 Java pagination fix | Backend compiles; all existing unit/integration tests run and pass |
| **G2** | MF-002 pagination and Python static tests | Pagination unit tests and Python syntax checks pass |
| **G3** | MF-003 / MF-004 bootstrap recovery | Interruption, deleted cache, restart, and repeated-bootstrap tests pass |
| **G4** | MF-008 / MF-009 integrity and disclosure | Negative tests for rejected approval, other-project rows, invalid/stale evidence |
| **G5** | MF-005 / MF-006 public deployment protection | Anonymous public routes work; operator paths denied; no demo-password login |
| **G6** | MF-007 / MF-011 / MF-012 live integration workflow | Real Python bootstrap + verifier + browser smoke test pass |
| **G7** | Documentation and PR review | Clear CI links, test results, verified limitations, deploy/run/recovery instructions |

Create a **draft PR** for review. Preserve existing backend API contracts and fix the client against those contracts rather than modifying working controllers merely to accommodate a mistaken script. Use no direct SQL insertion of fabricated application results; permitted scripts may inspect DB state and provision isolated test infrastructure.

## 7. Required regression-test matrix

| Test ID | Scenario | Expected result |
|---|---|---|
| T-FIX-01 | Java 21 compile after pagination change | Zero compiler errors |
| T-FIX-02 | Page `-1`, size `0`, size `100`, and large page | Explicitly defined bounded/rejected behavior; no overflow or duplicate-loop trap |
| T-FIX-03 | Rehearsal API returns 50-row pages | Python fetches 995 customers and 1,990 orders, without missing/duplicate IDs |
| T-FIX-04 | Validation issues span more than 50 entries | Python fetches all pages and distinct invalid row IDs |
| T-FIX-05 | Fresh DB, real CSV upload to final cutover | 11 completed stages, 5 genuine UAT PASSes, expected counts/sum, real hashes |
| T-FIX-06 | Run Python bootstrap twice | Second run performs no new cutover or duplicate target writes |
| T-FIX-07 | Delete state file after exclusions/freeze | DB-backed recovery skips already persisted decisions and freeze mutation |
| T-FIX-08 | Restartable rehearsal and cutover failure | Same run ID restarts within bounded budget, or records actionable FAILED |
| T-FIX-09 | Malformed/stale cache referencing another database | Cache discarded; never cross-wire artifacts |
| T-FIX-10 | Approval not `APPROVED`, or UAT hash altered | Public overview flags integrity; details return controlled unavailable result |
| T-FIX-11 | Other project/unsafe field inserted in source data | Anonymous APIs cannot reveal unrelated private data |
| T-FIX-12 | Public host requests `/api/v1/auth/login`, `/api/v1/internal/showcase/...`, operator APIs | Denied at proxy even with known dev credentials |
| T-FIX-13 | Anonymous visitor on READY DB | Immediately sees real metrics, timeline, provenance and downloadable JSON |
| T-FIX-14 | Anonymous visitor on FAILED/STALE/NOT_READY/backend-down DB | Honest state, no fabricated successes or misleading green status |
| T-FIX-15 | Latest intended commit | Backend, frontend, Python E2E, anonymous browser and public proxy checks all green |

## 8. Definition of done / release gate

A release is **not** ready until all of the following hold:

- [ ] Backend compiles and the full Java suite passes with Java 21 + PostgreSQL 16.
- [ ] Frontend tests, strict TypeScript checks, and production build pass.
- [ ] The actual `scripts/bootstrap-showcase.py` completes on a **fresh** PostgreSQL instance.
- [ ] The bootstrap executes real Spring Batch rehearsal and final cutover, without mocked results.
- [ ] All five UAT cases are real tests executed against persisted results, clearly labeled **automated demonstration**.
- [ ] `scripts/verify-showcase.py` passes anonymously, with independent CI DB/evidence checks.
- [ ] Counts and monetary totals match the checked-in fixture expectations through DB reports, **not** frontend constants.
- [ ] Restart recovery, lost local state, and idempotent repeat-bootstrap are proven against running services.
- [ ] The public frontend loads a completed scenario without sign-in and truthfully handles failure/staleness.
- [ ] Public routing blocks operator/internal APIs and uses no guessable demo credentials.
- [ ] New automated E2E workflow is **present in GitHub** and has a green run URL.
- [ ] Deployment runbook identifies safe bootstrap enablement, post-bootstrap token disablement, verification, and rollback/recovery limits.
- [ ] Draft PR explains fixes, tests actually executed, and any remaining unverified conditions.

## 9. Evidence/reporting format for the implementing developer

In the PR description and an updated `VERIFICATION-REPORT.md`, report each issue as:

```text
ID: MF-002
Status: FIXED IN SOURCE / CI-PASSED / E2E-PASSED / NOT VERIFIED
Files changed: ...
What changed: ...
Actual command/job run: ...
Actual result and log link: ...
Negative/regression tests: ...
Residual risk: ...
```

Never use “all 24 issues fixed” or “showcase works” as a substitute for current green CI and a completed genuine bootstrap. A clean frontend build is not proof that the backend runs. A green Java integration test does not prove that the actual Python bootstrap works. A green public verifier does not by itself prove that every target record was rehashed independently.

## 10. Source pointers (pinned to reviewed commit)

- [Latest reviewed commit `11d4403`](https://github.com/aravinditte/migrateflow/commit/11d4403de9dffd5b63e6d325619ef988e80d80db)
- [Backend CI failure — run #43618831542](https://github.com/aravinditte/migrateflow/actions/runs/43618831542)
- [Shared pagination utility](https://github.com/aravinditte/migrateflow/blob/11d4403de9dffd5b63e6d325619ef988e80d80db/backend/src/main/java/com/migrateflow/common/PageRequestUtil.java)
- [Run controller](https://github.com/aravinditte/migrateflow/blob/11d4403de9dffd5b63e6d325619ef988e80d80db/backend/src/main/java/com/migrateflow/execution/RunController.java)
- [Python bootstrap script](https://github.com/aravinditte/migrateflow/blob/11d4403de9dffd5b63e6d325619ef988e80d80db/scripts/bootstrap-showcase.py)
- [Independent anonymous verifier](https://github.com/aravinditte/migrateflow/blob/11d4403de9dffd5b63e6d325619ef988e80d80db/scripts/verify-showcase.py)
- [Showcase integrity/read model](https://github.com/aravinditte/migrateflow/blob/11d4403de9dffd5b63e6d325619ef988e80d80db/backend/src/main/java/com/migrateflow/showcase/ShowcaseQueryService.java)
- [Public showcase UI](https://github.com/aravinditte/migrateflow/blob/11d4403de9dffd5b63e6d325619ef988e80d80db/frontend/src/features/showcase/PublicShowcase.tsx)
- [Nginx public gateway currently used by frontend](https://github.com/aravinditte/migrateflow/blob/11d4403de9dffd5b63e6d325619ef988e80d80db/infra/nginx/nginx.conf)
- [Docker Compose](https://github.com/aravinditte/migrateflow/blob/11d4403de9dffd5b63e6d325619ef988e80d80db/docker-compose.yml)
- [Existing CI workflow](https://github.com/aravinditte/migrateflow/blob/11d4403de9dffd5b63e6d325619ef988e80d80db/.github/workflows/ci.yml)

---

**Target outcome:** A recruiter opens MigrateFlow, sees a completed migration within seconds, and can inspect authentic Spring Batch execution, real PostgreSQL reconciliation, validation/exclusion decisions, automated UAT, approval evidence, provenance, and audit events — with **no interaction required to create demo results** and no false claims about production validation.

# Verification report

What was actually executed, where, and what was not run. No claim in this
file goes beyond the evidence listed.

## Verified locally (this machine, 2026-11-14)

### Fixtures — PASS
`fixtures/generate_fixtures.py` (deterministic, no randomness) generated
`customers_1000.csv`, `orders_2000.csv` and `expected_results.json`, and its
built-in assertions passed:

- 1,000 customers / 2,000 orders
- **995 customers / 1,990 orders accepted; accepted order total ¥21,998,650**
- Planted defects spot-checked row by row against spec §13.1 (customer rows
  7, 13, 20+21, 34; order rows 9–18 including the C9999 reference and the
  references to invalid customers C0007/C0013/C0020/C0034).

### Frontend — PASS
- `npm ci` — OK
- `npm test` (Vitest) — **3/3 tests passed**
- `npm run build` (`tsc -b` strict + Vite production build) — OK,
  `dist/` produced (index.js ≈ 249 kB / 76 kB gzip)

### Database connectivity — PASS (probe only)
A JDBC probe from the JVM connected to the locally installed PostgreSQL
16.15 and returned its version string. This proves JVM→PostgreSQL
connectivity in this environment only; it is not an application test.

## NOT run locally — read this before quoting any backend result

The backend has **not been compiled or tested on this machine**. The
sandbox this code was written in blocks outbound TCP from JVM processes to
Maven Central, so Maven could not download the Spring Boot dependency tree
here. Rather than weaken the claim, the suite is wired to run where the
network is normal:

- **CI** (`.github/workflows/ci.yml`) runs `./mvnw -B verify` on JDK 21
  with a `postgres:16` service — this executes the unit tests
  (`CsvParserTest`, `TransformEngineTest`, `FixtureValidationTest`,
  `ReconciliationEngineTest`, `CommonTest`) and the PostgreSQL integration
  tests (`FullMigrationFlowTest`, `CrashRestartTest`), plus the frontend
  `npm ci` / `npm test` / `npm run build` job.
- Until a green CI run exists, treat all backend behaviour as
  **implemented and reviewed, but unverified by execution**.

## Not verified anywhere yet

- Docker Compose end-to-end startup (no Docker daemon on the build
  machine; the Compose file, Dockerfiles and nginx config are provided
  and internally consistent, but unexecuted).
- A live browser smoke of the UI against the running backend.
- Any throughput or capacity figure — none is claimed anywhere in this
  repository.
- GitHub Actions CI itself, until the first run completes on the pushed
  branch.

## Fix round — external repository review, 2026-11-14

An external review of the merged `main` branch was triaged claim by claim
against the source. The following findings were **confirmed in the code and
fixed** in this round (branch `migrateflow-review-fixes`):

1. **Compile blocker:** `ManifestController.get()` built a 13-pair
   `Map.of(...)` (Java allows at most 10). Now `Map.ofEntries(...)`. A scan
   confirmed this was the only oversized `Map.of` in the codebase.
2. **Demo password mismatch:** the seed runner read
   `MIGRATEFLOW_SEED_*_PASSWORD` while Compose/`.env.example` pass
   `MIGRATEFLOW_*_PASSWORD`. The runner now uses the documented names.
3. **Test login CSRF:** the integration-test login posts carried no CSRF
   token and would have received 403. Test login and the invalid-credential
   post now send one, mirroring the SPA.
4. **Cutover slot deleted by reset:** both the test reset and
   `scripts/reset-demo.sql` truncate `control.runs` with CASCADE, which
   also empties `target_cutover_slot` (it references `runs`). Both now
   restore the singleton row with INSERT ... ON CONFLICT.
5. **Cutover preflight now revalidates UAT:** inside the preflight
   transaction, the approval's stored UAT evidence hash is compared with a
   freshly computed hash and the blocker gate is re-run. A case re-executed
   after approval, or a blocking defect opened after approval, now fails
   the cutover launch (`UAT_EVIDENCE_STALE` / `UAT_NOT_PASSED`).
6. **Cross-project references:** starting a validation now verifies the
   bundle, mapping revision and rule set belong to the URL's project
   (foreign ids are 404).
7. **Validation transaction:** the `@Transactional` annotation was bypassed
   by self-invocation from the executor lambda. Validation writes now run
   in an explicit `TransactionTemplate`; failures roll back and mark the
   snapshot FAILED separately.
8. **Launch/restart races:** rehearsal launch serialises on the project
   row inside one transaction; restart is an atomic conditional-update
   claim; `executeRun` itself claims QUEUED → RUNNING atomically.
9. **Frozen-plan exclusions:** exclusion decisions are rejected once a
   FROZEN manifest binds the snapshot (`MANIFEST_ALREADY_FROZEN`).
10. **Process recovery:** a new startup recovery abandons stale batch
    executions left by a terminated process and marks their runs
    FAILED_RESTARTABLE; QUEUED runs are executed. Covered by
    `ProcessRecoveryTest`.

Still true after this round: the backend has **not** been compiled or
test-run by the author of this report — the sandbox JVM-network block
described above is unchanged, and these fixes are source-reviewed only.
They execute in CI exactly like the original suite, once
`.github/workflows/ci.yml` is present (see the note in the README PR: the
file remains blocked by the GitHub integration's Workflows permission and
must be added manually). A true OS-process kill-and-restart test (beyond
the recovery state-transition test above) is still not part of the suite.

## Fix round 2 — forensic spec + workspace review, 2026-11-14

A second external review covered SPEC.md itself as well as the code.
Triaged claim by claim; the following were **confirmed and fixed**
(branch `migrateflow-review-fixes-2`):

**Implementation**
1. **Nginx upload cap:** `infra/nginx/nginx.conf` now sets
   `client_max_body_size 30M` — the 1 MB default would have rejected
   legitimate bundle uploads (spec allows 10 MiB/file) with 413.
2. **UAT evidence hash timezone:** the hash input rendered `executed_at`
   with PostgreSQL's session-timezone-dependent cast. It now formats in
   UTC (`to_char(... AT TIME ZONE 'UTC', ...)`), so the approval-time and
   preflight-time digests cannot diverge by connection settings.
3. **Live step tracking:** batch listeners stamp
   `batch_job_instance_id`/`batch_job_execution_id` at job start and
   `current_step` at each step start; previously both were set only
   after the job finished, so `GET /runs/{id}` reported no step while
   RUNNING. `runView` also falls back to the stamped column.
4. **Restart concurrency:** `restart()` now takes the project row lock
   and refuses while a sibling run is QUEUED/RUNNING — the
   one-worker-per-project rule (spec 3.3) previously held only for
   launches.
5. **Download authorization:** `GET /files/{fileId}/download` requires
   the ENGINEER or AUDITOR role, not mere membership (spec 10.2).
6. **Rule-set surface completed:** `GET
   /projects/{projectId}/rule-set-revisions/current` added; defect views
   now carry `caseKey`, `resolvedAt`, `retestRequired` and
   `retestPassed` for the UI's retest guidance.

**Specification (SPEC.md)**
7. §10.3 documents the pinned rule-set contract (immutable per-project
   revisions, auto-seeded at mapping publish, optional
   `ruleSetRevisionId` on validation start, project-ownership enforced).
8. §6 gains exact canonical digest contracts for field-map digests, the
   exclusion-set hash, the target-contract descriptor string, and the
   UAT evidence hash — independently reproducible.
9. §4.4 pins exclusion eligibility: invalid rows only; a valid row can
   never be excluded (`ROW_NOT_INVALID`).
10. §10.2 resolves the download role ambiguity (ENGINEER/AUDITOR; no
    separate export-permission flag exists).
11. §19 ADR table now mirrors `docs/adr/` (ADR-001..007) with the
    standing design decisions listed separately; the deterministic-UUID
    question is recorded as pinned (ADR-004), not outstanding.
12. §13.2 aligned with ADR-006: real PostgreSQL managed outside the
    test JVM instead of Testcontainers.
13. §8.3 gains the explicit rehearsal-table DDL with the composite
    primary/foreign keys, matching Flyway V007.

**Frontend**
14. `Workspace.tsx` (469 lines) is split into the §15 feature layout
    (`features/{auth,projects,intake,mappings,validation,rehearsal,
    reconciliation,uat,cutover,audit,workspace}`); App.tsx is a shell.
15. UAT tab gains the rehearsal data browser (searchable run-scoped
    customers/orders) and defect retest guidance (retest state shown;
    close is guided, not a blind 409). Intake gains a paginated raw-row
    preview with physical line numbers and per-file download links.
    **Verified in this round:** `tsc -b` + Vite build pass and Vitest
    3/3 passes with the new layout.

**Tests**
16. New `AcceptanceGapTest`: T11 defect gate end-to-end (open BLOCKER
    blocks approval; resolve alone still blocks; close without retest
    is 409; retest PASS then close; approval then succeeds), T14 racing
    cutovers across two projects (exactly one 202, one 409), T18
    post-cutover tamper detection via re-reconciliation (FAIL report),
    T20 audit-trail completeness/attribution/ordering over a full
    journey. T01 is covered by `scripts/smoke-compose.sh` (requires
    Docker; not executed in the author's sandbox).

Backend items remain **source-reviewed only**, for the same reason as
before: the sandbox JVM cannot reach Maven Central, so the Java suite
executes in CI once `ci.yml` is added. The frontend items above ARE
executed and verified locally.

## Fix round 3 — showcase implementation review (S-01..S-24), 2026-11-14

An external review of the merged showcase (main @36ec92c) was triaged
claim by claim against the sources, including the CI logs it cited.
Confirmed and fixed:

- **S-01 (compile error, confirmed by CI run 43615176867):**
  ShowcaseQueryService referenced a nonexistent `UatService.UatCase`;
  the record is `CaseView`. This also explains the earlier undiagnosed
  backend CI failure mode: compilation stops before any test runs.
- **S-02..S-05 (bootstrap contracts):** the script's login (form
  username/password), seed emails (@migrateflow.test), membership calls
  (nonexistent /admin/users + POST memberships), bundle response shape,
  mapping/validation/snapshot/issues paths and the freeze body all
  disagreed with the real controllers. The script was rewritten against
  the verified contracts: JSON email/password login, @example.test seed
  identities, actor IDs from /auth/me + PUT /projects/{id}/members/{id},
  POST /projects/{id}/mapping-revisions, POST/GET under
  /validation-snapshots/{id}, freeze with the full four-ID body,
  POST /manifests/{id}/rehearsals.
- **S-06:** the Compose bootstrap could not write its state file (the
  scripts mount is read-only). State now lives on a named volume via
  MIGRATEFLOW_SHOWCASE_STATE_FILE.
- **S-07/S-11:** reconciliation summaries use accepted/written, not
  acceptedRows/writtenRows — fixed in the script, the verifier and the
  frontend types, and pinned by a new frontend contract fixture test
  carrying the real backend JSON shape.
- **S-08:** Compose now binds backend/frontend to 127.0.0.1 with a
  reverse-proxy note for public hosting; bootstrap actor passwords are
  wired from the same variables the seed reads.
- **S-09/S-10:** persisted approval decision is APPROVED (the request
  verb is APPROVE) — fixed in the workflow stage and the verifier; the
  all-stages-COMPLETED assertion in ShowcaseBootstrapTest covers it.
- **S-12:** UAT checks deepened — COUNTS_MATCH and EXCLUSIONS_ABSENT now
  compare FULL target key sets against the source-derived accepted sets;
  PROVENANCE_TRACE verifies the stored source-file SHA-256 against the
  checked-in fixture bytes in addition to row numbers and manifest
  binding.
- **S-13:** script failures now raise a BootstrapError that is recorded
  as FAILED in the registry before exit (die()/SystemExit bypass fixed;
  assert statements converted to the same path).
- **S-14:** a READY re-run re-verifies the anonymous public overview
  (state + integrity) before claiming success; it performs no writes.
- **S-15:** public detail endpoints now require a READY scenario whose
  lineage re-verifies at request time (503 otherwise); the overview keeps
  showing truthful PREPARING/FAILED/NOT_READY states.
- **S-16:** public records are restricted to the showcase cutover run
  (origin_run_id) and the source payload is allowlisted to the known
  synthetic fixture columns.
- **S-17/S-23:** the overview now distinguishes historical reconciliation
  (the stored PASS reports) from the bounded current-integrity check and
  labels it with its check time; verification exposes three distinct
  timestamps (cutover reconciliation completion, registry verification,
  registry creation) and the hero shows the cutover one.
- **S-18:** the bootstrap polls /actuator/health with a bounded timeout
  and honest failure output before signing in.
- **S-19:** `.github/workflows/showcase.yml` added — it runs the REAL
  Python bootstrap against a live backend + PostgreSQL 16, the anonymous
  verifier, an idempotency re-run and a restart-persistence check. Like
  ci.yml originally, it must be added via the GitHub web UI: the API
  token lacks the Workflows permission, so it is not part of the API
  push (it is in the source tree and the zip).
- **S-20:** SecurityConfig.requireUser now rejects disabled accounts, so
  disabling cuts off live sessions, with a regression test. (Triage
  note: the missing check predates the showcase — the showcase diff to
  SecurityConfig only added the GET permitAll matcher — but the fix is
  correct regardless.)
- **S-21:** pagination offsets now go through a new
  common.PageRequestUtil (the codebase had none; the spec and the
  review both assumed one) with clamped page/size and long arithmetic.
- **S-22:** the bootstrap treats the DB registry as canonical: a state
  cache naming a different registry row is discarded, and cached
  artifact IDs are validated against the live database before use.
- **S-24:** the register endpoint now requires the ADMIN principal in
  addition to session + token + flag; the script registers as admin.

Verification status: frontend re-verified locally (strict tsc + Vite
build; Vitest now 4/4 including the new contract fixture). The rewritten
bootstrap script is py_compile-checked and every route/body/response key
was re-derived from the controller sources cited above; it has still not
executed against a live backend in the authoring sandbox. The Java fixes
are source-reviewed; CI (./mvnw verify on the PR) is the first real
execution, and showcase.yml becomes the first real bootstrap execution
once added.

## Showcase — Automated Showcase spec implementation, 2026-11-14

Implemented the Automated Showcase (SPEC section 20): V012 scenario
registry; `com.migrateflow.showcase` read model (overview, workflow,
validation, mapping, reconciliation, UAT, audit, records, evidence) with
lineage re-verification on READY reads; token-guarded internal register
endpoint that refuses READY unless lineage verifies; anonymous GET-only
access scoped in SecurityConfig; `scripts/bootstrap-showcase.py`
(authenticated end-to-end orchestration with five real UAT assertions,
idempotent + resumable, fail-closed) and `scripts/verify-showcase.py`
(anonymous read-only re-check); a public showcase frontend (default
anonymous entry; operator login is an explicit detour) with hero, metrics,
11-stage timeline, validation/exclusions, before/after records,
reconciliation, UAT/approval, audit and architecture sections; Compose
`showcase` profile for the one-time bootstrap; docs updated (SPEC 20,
README, architecture, api-contracts, runbook, testing, limitations).

Verification status, stated plainly:

- **Frontend: VERIFIED locally** — strict `tsc` + Vite build passes with
  the showcase components; Vitest 3/3 passes.
- **Scripts: syntax-verified** (`py_compile`) and reviewed line-by-line
  against the live controller contracts (paths, bodies and status codes
  checked against the Java sources; two draft mistakes — the exclusions
  endpoint shape and the approvals/UAT paths — were caught and fixed this
  way). They have **NOT** been executed against a running backend in the
  authoring sandbox (no Docker; JVM networking restricted), so the
  bootstrap's first real run happens on the operator's host or in
  deployment.
- **Backend: source-reviewed only**, like the rest of the Java suite. The
  new tests below run under the existing CI backend job (`./mvnw verify`)
  once this branch is merged; they had not executed at the time of
  writing.
- Migration numbering: the showcase migration is V012 because main
  already carries V011 (`frozen_manifest_unique_index`, added outside
  this branch); the local tree was synced with that file.

## Test inventory (what CI will execute)

| Suite | Covers |
|---|---|
| `CsvParserTest` | RFC 4180 quoting, BOM/CRLF, duplicate headers, NUL, malformed quotes, physical line numbers |
| `TransformEngineTest` | Every allow-listed mapping op, strict dates, unknown-op rejection |
| `FixtureValidationTest` | The checked-in fixtures through the real transform+validation engines: 995/1,990/¥21,998,650, exact invalid rows, duplicates, UNKNOWN_CUSTOMER propagation, conservation |
| `ReconciliationEngineTest` | Pass, missing, extra, changed-hash, amount-mismatch |
| `CommonTest` | Canonical hash stability, CSV formula escaping |
| `FullMigrationFlowTest` | Whole journey over HTTP on real PostgreSQL: exclusion gate, freeze, rehearsal isolation, reconciliation, UAT gate, separation of duties, idempotent cutover, exact final counts, second-cutover refusal, auth/CSRF/membership guards, malformed upload |
| `CrashRestartTest` | Test-only fault injection kills rehearsal and cutover after committed chunks; same-identity restart finishes with exact counts and no duplicates |
| `ProcessRecoveryTest` | A run left RUNNING by a terminated process is recovered at startup to FAILED_RESTARTABLE (stale batch executions abandoned); recovery is idempotent |
| `ShowcaseSecurityTest` | Anonymous overview NOT_READY with no fabricated payload, data endpoints 503 pre-bootstrap, anonymous mutations 401 everywhere, register endpoint requires session + bootstrap token |
| `ShowcaseBootstrapTest` | Full real journey registered READY; anonymous overview serves genuine 995/1,990/¥21,998,650 with integrity OK and 11/11 stages COMPLETED; re-registration idempotent; broken-lineage READY refused 422 and persisted FAILED |
| `AcceptanceGapTest` | T11 defect gate (blocker blocks approval; close needs a post-resolution passing retest), T14 racing cutovers (exactly one winner), T18 tampered target row fails re-reconciliation, T20 audit trail completeness and order |
| Frontend Vitest | Formatting helpers (currency, hashes, instants, polling) |

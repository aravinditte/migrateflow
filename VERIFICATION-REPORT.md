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
| Frontend Vitest | Formatting helpers (currency, hashes, instants, polling) |

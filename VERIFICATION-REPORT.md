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
| Frontend Vitest | Formatting helpers (currency, hashes, instants, polling) |

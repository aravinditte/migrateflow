# Testing

## Unit tests (no database)

- `CsvParserTest` — RFC 4180 quoting, BOM, CRLF, malformed quotes, duplicate
  headers, NUL rejection, physical line tracking.
- `TransformEngineTest` — every allow-listed op, strict date parsing,
  unknown-op rejection.
- `FixtureValidationTest` — validates the checked-in fixtures through the
  real transform + validation engines and asserts the spec contract:
  **995 customers / 1,990 orders accepted, ¥21,998,650**, the exact invalid
  rows, duplicate-key handling, and `UNKNOWN_CUSTOMER` propagation.
- `ReconciliationEngineTest` — pass, missing, extra, changed-hash and
  amount-mismatch cases.
- `CommonTest` — canonical hashing stability, CSV formula escaping.
- Frontend: Vitest over formatting helpers; TypeScript strict build.

## Integration tests (real PostgreSQL)

`FullMigrationFlowTest` drives the whole journey over HTTP against a real
PostgreSQL database: upload → validate → exclusion gate → freeze →
rehearsal isolation → reconciliation → UAT gate → approval separation of
duties → idempotent cutover → final counts → second-cutover refusal, plus
auth/CSRF/membership guards and malformed-upload rejection.

`CrashRestartTest` enables the test-only fault injection
(`migrateflow.test-fail-after-chunks`) so runs die after committed chunks;
the same run identity restarts to exact counts with no duplicates — checked
for both rehearsal and cutover.

`ProcessRecoveryTest` covers startup recovery: a run left `RUNNING` by a
terminated process is marked `FAILED_RESTARTABLE` (its stale batch
executions updated to `FAILED` in the Spring Batch repository) so the ordinary restart path can resume it. Interrupted validation snapshots fail closed to `FAILED` and require re-initiation. A test
that kills a real OS process mid-batch is not part of the suite.

`AcceptanceGapTest` covers the acceptance rows the first suite left thin:
T11 in full (an unresolved BLOCKER defect blocks approval; closing a
defect requires a passing retest recorded after resolution), T14 (two
projects racing cutover launches — exactly one is accepted, the other
conflicts), T18 (a target row altered after a verified cutover makes a
fresh reconciliation FAIL), and T20 (the audit trail of a full journey is
complete, attributed, and chronologically ordered).

T01 (clean Docker startup) is covered by `scripts/smoke-compose.sh`,
which builds the Compose stack, waits for the health endpoint through
the nginx proxy, checks the SPA is served and the demo login works, then
tears the stack down. It runs where Docker is available; it is not part
of the JUnit suite.

Integration tests need a reachable PostgreSQL:

```bash
# local
psql -U postgres -c "CREATE DATABASE migrateflow_test;"
cd backend && ./mvnw verify
# or point elsewhere:
MIGRATEFLOW_TEST_DATASOURCE_URL=jdbc:postgresql://host:5432/migrateflow_test ./mvnw verify
```

CI provides the database as a service container (see
`.github/workflows/ci.yml`). Testcontainers is deliberately not used: the CI
service and local PostgreSQL exercise the same real database without a
Docker daemon dependency inside tests (see ADR-006).

## What is NOT covered

- Docker Compose end-to-end execution (the compose file is provided;
  verify on a Docker host).
- Concurrent cutover racing beyond the transactional slot test path.
- Browser-level UI automation; the frontend is verified by typecheck,
  unit tests and a live smoke against the real backend.

## Showcase tests (SPEC section 20)

- `ShowcaseSecurityTest`: anonymous overview answers NOT_READY with no
  fabricated payload, data endpoints answer 503 before bootstrap,
  anonymous mutations are 401 everywhere (including the private API), and
  the internal register endpoint requires session + token.
- `ShowcaseBootstrapTest`: a full real journey (upload → validate →
  exclude → freeze → rehearse → UAT → approve → cutover over HTTP) is
  registered READY; the anonymous overview then serves 995 / 1,990 /
  ¥21,998,650 with integrity OK and all 11 workflow stages COMPLETED;
  re-registration is idempotent (target counts and registry cardinality
  unchanged); a READY claim with a deliberately wrong rehearsal pointer is
  refused 422 and persisted FAILED.
- `scripts/verify-showcase.py`: anonymous, read-only re-verification of a
  running instance against `fixtures/expected_results.json`.
- The bootstrap script itself is exercised by running it against a live
  deployment (Compose profile `showcase`); it is not unit-tested, and its
  UAT PASS records exist only where its real assertions passed.

## Showcase end-to-end CI (`.github/workflows/showcase.yml`)

A dedicated workflow boots the real backend against PostgreSQL 16 and
runs the ACTUAL `scripts/bootstrap-showcase.py` (the Java integration
tests deliberately do not re-implement it), then the anonymous verifier,
an idempotency re-run, anonymous-mutation denials and a
restart-persistence check. It must be added via the GitHub web UI (the
API token lacks the Workflows permission), like ci.yml originally.

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
executions abandoned) so the ordinary restart path can resume it. A test
that kills a real OS process mid-batch is not part of the suite.

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

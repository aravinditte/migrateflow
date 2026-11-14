# MigrateFlow

A legacy data migration, reconciliation and cutover workbench. MigrateFlow
moves a bounded dataset — customers and orders exported as CSV from a legacy
system at the fictional Hinata Trading Co. — into a simulated order-management
database, through a controlled workflow where **no row reaches the final
target unless it passed through an approved, frozen, rehearsed and
reconciled plan**.

```
intake → mapping → validation → exception decisions → frozen manifest
       → rehearsal → reconciliation → business UAT → approval
       → cutover → final reconciliation
```

Built with Java 21, Spring Boot 3.5, Spring Batch 5.2, PostgreSQL 16,
React 19 and TypeScript. Full specification: [SPEC.md](SPEC.md).
Design notes: [docs/](docs/) (architecture, data contracts, schema,
API contracts, runbook, testing, limitations, ADRs).

## Public showcase (no login)

The default page is a **public, read-only showcase**: one complete Hinata
Trading migration that was executed end-to-end by the real backend —
validation, reviewer exclusions, frozen manifest, Spring Batch rehearsal,
scripted UAT, approval and final cutover — with every displayed figure read
live from the persisted PostgreSQL evidence. The business data is
synthetic; the execution is authentic; the UAT and approval actors are
automated demo accounts and are labeled as such. The operator workbench
(sign-in, roles, uploads) sits behind the "Operator sign in" button and is
unchanged.

To create the scenario on a fresh database, enable the one-time bootstrap
and run it once (it is idempotent and fails closed):

```bash
# .env: MIGRATEFLOW_SHOWCASE_BOOTSTRAP_ENABLED=true
#       MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN=<choose-a-secret>
docker compose up -d
docker compose --profile showcase up showcase-bootstrap
python3 scripts/verify-showcase.py http://localhost:8081   # anonymous re-check
```

Details: SPEC.md section 20 and `docs/runbook.md` ("Public showcase").

## Why it is built this way

- **Immutable inputs.** Source files, mapping revisions and frozen manifests
  are never edited after creation; every downstream object binds the exact
  hashes of its inputs, so stale evidence cannot be silently reused.
- **No waived-valid rows.** An invalid row can only be *excluded* by a
  reviewer, with a reason. Conservation always holds:
  `raw = accepted + excluded`.
- **Rehearsal before reality.** Every plan runs first into run-isolated
  rehearsal tables and must reconcile exactly — business keys, row hashes,
  counts and the ¥ order total — while the final target stays untouched.
- **Restartable, insert-only cutover.** Chunk transactions write target rows
  and their applied-rows ledger entries together; deterministic target ids
  make a same-run restart exact after a crash. A singleton slot, reserved in
  the same transaction as the preflight checks, serialises competing
  cutovers.
- **Separation of duties.** The manifest creator cannot approve it; the
  approver cannot launch the cutover. Enforced in the services, not the UI.

## Quick start (Docker Compose)

```bash
cp .env.example .env
docker compose up --build
# UI http://localhost:8080 · API http://localhost:8081
```

## Quick start (local processes)

Prerequisites: JDK 21, Node 22, PostgreSQL 16.

```bash
psql -U postgres -c "CREATE DATABASE migrateflow;"
psql -U postgres -c "CREATE USER migrateflow WITH PASSWORD 'migrateflow-dev-pw';"
psql -U postgres -c "GRANT ALL ON DATABASE migrateflow TO migrateflow;"

cd backend && MIGRATEFLOW_DEMO_SEED_ENABLED=true ./mvnw spring-boot:run
cd frontend && npm ci && npm run dev   # http://localhost:5173
```

Windows (PowerShell): use `mvnw.cmd` instead of `./mvnw`, and set the seed
variable with `$env:MIGRATEFLOW_DEMO_SEED_ENABLED="true"` before running.

Demo accounts (seeded only into an empty users table; development only,
passwords overridable via `MIGRATEFLOW_*_PASSWORD` in `.env`):
`engineer@example.test`, `reviewer@example.test`, `auditor@example.test`,
`admin@example.test` with `*-dev-pw` passwords.

## Demo script

1. Sign in as the engineer; create a project. As admin, add the reviewer
   and auditor as members.
2. Upload `fixtures/customers_1000.csv` and `fixtures/orders_2000.csv`.
3. Publish the default mapping; run validation — expect **995 customers /
   1,990 orders valid**, 15 invalid rows.
4. As the reviewer, exclude each invalid row with a reason.
5. Freeze the manifest; launch a rehearsal. Reconciliation must PASS with
   order total **¥21,998,650** and the final target still empty.
6. As the reviewer, pass all five UAT cases, then approve.
7. As the engineer, launch the cutover. Final reconciliation PASS; the
   target now holds exactly the accepted dataset.

Resetting the demo is an explicit psql action (`scripts/reset-demo.sql`) —
there is no reset endpoint, by design.

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Spring Boot application (Flyway migrations in `src/main/resources/db/migration`) |
| `frontend/` | React + TypeScript operator UI |
| `fixtures/` | Deterministic 1,000/2,000-row dataset, its generator, and expected results |
| `docs/` | Architecture, contracts, schema, runbook, testing, limitations, ADRs |
| `infra/nginx/` | Same-origin reverse-proxy config for the Compose deployment |
| `scripts/` | Demo reset SQL |

## Verification

See [VERIFICATION-REPORT.md](VERIFICATION-REPORT.md) for exactly what was
executed and verified, where, and what was not run. In short: the frontend
is type-checked, unit-tested and built; the fixtures are generated and
asserted against the spec counts; the backend's unit and PostgreSQL
integration tests run in CI (`.github/workflows/ci.yml`, postgres:16
service) via `./mvnw verify`.

## License

MIT — see [LICENSE](LICENSE).

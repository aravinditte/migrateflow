# Runbook

## Start (Docker Compose)

```bash
cp .env.example .env
docker compose up --build
# UI: http://localhost:8080   API: http://localhost:8081
```

## Start (local processes)

Prerequisites: JDK 21, Node 22, PostgreSQL 16.

```powershell
# PowerShell (Windows): create the database once
psql -U postgres -c "CREATE DATABASE migrateflow;"
psql -U postgres -c "CREATE USER migrateflow WITH PASSWORD 'migrateflow-dev-pw';"
psql -U postgres -c "GRANT ALL ON DATABASE migrateflow TO migrateflow;"
```

```bash
cd backend
MIGRATEFLOW_DEMO_SEED_ENABLED=true ./mvnw spring-boot:run   # mvnw.cmd on Windows
cd ../frontend && npm ci && npm run dev                      # http://localhost:5173
```

Demo accounts (seeded only when the users table is empty; passwords are
overridable via `MIGRATEFLOW_*_PASSWORD`): `engineer@`, `reviewer@`,
`auditor@`, `admin@example.test` with `*-dev-pw` passwords. **Development
only.**

## Demo script

1. Sign in as the engineer, create a project.
2. As admin, add the reviewer and auditor as project members.
3. Upload `fixtures/customers_1000.csv` + `fixtures/orders_2000.csv`.
4. Publish the default mapping, run validation: expect 995 / 1,990 valid.
5. As the reviewer, exclude all 15 invalid rows (each with a reason).
6. Freeze the manifest; launch a rehearsal; reconciliation should PASS
   with order total ¥21,998,650 and the final target still empty.
7. As the reviewer, execute all five UAT cases to PASS.
8. Approve (reviewer). Launch cutover (engineer). Final reconciliation
   PASS; the target now holds exactly the accepted dataset.

## Verifying a fresh deployment

`scripts/smoke-compose.sh` performs the T01 clean-startup check: build
and start the Compose stack, wait for the backend health endpoint via
the proxy, verify the SPA and a demo login, then tear everything down.

## Public showcase deployment

For public / demonstration environments:

```bash
# 1. Start using the public overlay: backend ports are NOT published to the host,
# and frontend exposes port 80 only via nginx-public.conf (which forbids operator/auth/internal APIs).
docker compose -f docker-compose.yml -f docker-compose.public.yml up --build -d

# 2. Ensure all demo role accounts are disabled in production:
python3 scripts/disable-demo-users.py "postgresql://migrateflow:migrateflow-dev-pw@127.0.0.1:5432/migrateflow"

# 3. Bootstrap the showcase scenario:
python3 scripts/bootstrap-showcase.py http://127.0.0.1:8081

# 4. Verify public read model:
python3 scripts/verify-showcase.py http://localhost:80
```

## Failure handling

- **Backend restarted mid-run** — at startup, `RunRecovery` automatically recovers stale executions:
  - Spring Batch executions left running are updated to `FAILED` in the job repository;
  - Runs left `RUNNING` or `BATCH_COMPLETED` (unverified) become `FAILED_RESTARTABLE` for safe resume via the restart ledger;
  - Interrupted `QUEUED` runs are dispatched;
  - In-flight `RUNNING`/`QUEUED` validation snapshots are marked `FAILED` (fail-closed; snapshots must be re-initiated).
- **Run FAILED_RESTARTABLE** — fix the cause, then `POST /runs/{id}/restart`.
  The same run identity resumes; already-applied rows are recognised via the
  ledger and skipped. A restart never creates a second run row.
- **Cutover slot stuck RESERVED** — the owning run is active or
  restartable. Finish or restart that run; do not edit the slot by hand
  outside the documented demo reset.
- **Reconciliation FAIL** — the run is `VERIFICATION_FAILED`; its report
  lists missing/extra/changed keys. Do not approve anything from it.
- **Reset the demo** — `psql "$DATABASE_URL" -f scripts/reset-demo.sql`.
  There is no reset endpoint by design.

## Health

`GET /actuator/health` reports application and database status.

## Public showcase (SPEC section 20)

**Set up (once per fresh database).** Set `MIGRATEFLOW_SHOWCASE_BOOTSTRAP_ENABLED=true`
and a `MIGRATEFLOW_SHOWCASE_BOOTSTRAP_TOKEN` in `.env`, start the stack
(`docker compose up -d`), then run the one-time bootstrap:
`docker compose --profile showcase up showcase-bootstrap`. The script
drives the real workflow with the seeded demo actors and registers the
verified scenario; watch for `Showcase READY` in its output. Afterwards
the token/enable flag can be removed again — the public read API does not
depend on them. Never reuse the `*-dev-pw` demo passwords on a public
deployment; provision real operator accounts instead (runbook: demo seed).

**Verify.** `python3 scripts/verify-showcase.py http://localhost:8081`
re-checks the anonymous API against the fixture expectations and the
lineage invariants; the UI shows the same integrity state on the overview
(`integrity: OK`). A STALE/INTEGRITY_ALERT overview means a linked
artifact changed after registration (new manifest frozen, target tampered,
defect reopened) — the page lists the exact problems; investigate before
re-registering anything.

**Redeploy.** Safe: the registry, artifacts and uploaded files persist in
the database/volume. Redeploys never re-run the bootstrap automatically,
and no step truncates or resets the demo data.

**Recover from a FAILED bootstrap.** Read `failure_message` in
`control.showcase_scenarios` (or the script output). Fix the cause
(usually credentials, token mismatch, or a genuinely failed gate), then
re-run the same command: it resumes from the recorded artifact IDs and
completes the remaining stages. If the failure happened after the cutover
verified, the registry simply needs the final READY registration — the
script detects the existing runs. Starting over requires a **fresh
database**; there is deliberately no in-place reset for a consumed
cutover slot.

**Public hosting hardening.** Compose binds the backend (8081) and
frontend (8080) to 127.0.0.1. For a public deployment, terminate TLS in
a reverse proxy in front of the frontend only, keep the backend port
private, and replace the seeded demo passwords (or disable the demo
seed and provision real operator accounts) — the public read-only page
must never share a public host with a known-credential workbench.

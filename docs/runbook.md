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

## Failure handling

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

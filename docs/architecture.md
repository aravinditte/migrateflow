# MigrateFlow architecture

MigrateFlow is a controlled workbench for a one-time, offline CSV migration:
legacy customers and orders are validated, approved, rehearsed, reconciled
and cut over into a simulated order-management database. It is built around
one idea: **no row reaches the final target unless it passed through an
approved, frozen, rehearsed and reconciled plan.**

## Components

| Component | Technology | Responsibility |
|---|---|---|
| `backend/` | Java 21, Spring Boot 3.5, Spring Batch 5.2, JDBC | All workflow state, execution and evidence |
| `frontend/` | React 19, TypeScript, Vite | Operator UI (same origin, session auth) |
| PostgreSQL 16 | Flyway-managed schemas | Control, staging, rehearsal and target data |
| `fixtures/` | Checked-in CSV + generator | Deterministic 1,000/2,000-row demo dataset |

## Workflow state machine

```
intake -> mapping -> validation -> exceptions decided -> manifest FROZEN
       -> rehearsal -> reconciliation PASS -> UAT PASS -> approval
       -> cutover (reserved slot) -> final reconciliation PASS -> VERIFIED
```

Every transition is an API call guarded by role + project membership and
recorded in an append-only audit log. Downstream objects bind the exact
hashes of their inputs, so changing any upstream input makes downstream
evidence stale instead of silently reusable.

## Data planes

- **control** — users, projects, bundles, revisions, snapshots, manifests,
  runs, reconciliation reports, UAT, approvals, audit, the singleton cutover
  slot, and the Spring Batch job repository (pinned DDL, prefix
  `control.BATCH_`).
- **stage** — raw parsed rows (as JSONB, with physical line numbers) and
  validated rows with per-field errors. Nothing here is ever the target.
- **rehearsal** — run-isolated copies of the target tables. Every row carries
  its `rehearsal_run_id`; a rehearsal can never write anywhere else.
- **target** — the final simulated system. Insert-only during cutover.

## Execution model

Rehearsal and cutover are Spring Batch jobs with two chunk-oriented steps
(customers, then orders). Each chunk transaction writes target rows **and**
their `control.applied_rows` ledger entries together, so a crash can never
leave a row written but unrecorded. Target ids are deterministic UUIDs
derived from `(manifest, kind, business key)`, which makes the ledger check
on restart exact: same hash means "already applied, skip", a different hash
fails the run closed.

Cutover preflight is a single transaction: it validates the approval, the
frozen manifest, a verified rehearsal and its PASS reconciliation report,
checks the approver differs from the cutover requester, proves the final
target is empty, and reserves the singleton target slot with
`SELECT ... FOR UPDATE`. A competing cutover for any project then conflicts
instead of racing.

## Security model

- Session authentication (BCrypt passwords), SPA-readable CSRF cookie.
- Effective permission = global role ∩ active project membership.
  Non-members receive 404 for project resources (no existence leak).
- Separation of duties is enforced in services, not just the UI: the
  manifest creator cannot approve it; the approver cannot launch cutover.
- Uploaded files are stored outside the database under random keys, never
  under client-supplied names; downloads re-check membership.

## Public showcase layer

The showcase (SPEC section 20) is a presentation/read-model layer over the
existing workflow, not a second engine. `control.showcase_scenarios`
(V012) holds pointers to one verified journey's artifacts.
`ShowcaseQueryService` builds every public figure from those artifacts on
each request and re-verifies lineage (project coherence, manifest binding,
run/report status, approval-to-UAT-hash binding, slot state, target counts
and sums, applied-rows ledger) before presenting READY; a mismatch yields
STALE/INTEGRITY_ALERT, never a stale green. `scripts/bootstrap-showcase.py`
is a deployment-side orchestrator that produces the scenario through the
ordinary authenticated HTTP workflow with the synthetic demo actors, then
registers the pointers through a token-guarded internal endpoint that
re-runs the same lineage verification before accepting READY. Guest and
operator modes share the database but not the API surface: anonymous
traffic can only reach the GET-only showcase namespace.

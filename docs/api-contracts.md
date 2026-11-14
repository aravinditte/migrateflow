# API contracts

Base path `/api/v1`. Session cookie auth; state-changing calls require the
`X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie (fetch it via
`GET /api/v1/auth/csrf`). Errors are RFC 9457 `application/problem+json`
with a stable machine-readable `code`.

| Method & path | Role | Purpose |
|---|---|---|
| `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` | any | Session lifecycle |
| `POST /admin/users`, `PUT /projects/{id}/members/{userId}` | ADMIN | User and membership admin |
| `POST /projects`, `GET /projects`, `GET /projects/{id}`, `POST /projects/{id}/archive` | member | Project lifecycle (archive blocked while a run is active) |
| `POST /projects/{id}/bundles` (multipart `customers`, `orders`) | ENGINEER | Paired CSV intake |
| `GET /bundles/{bundleId}/profile` | member | Bundle profile (hashes, counts) |
| `GET /files/{fileId}/rows` | member | Paged raw-row preview |
| `GET /files/{fileId}/download` | member | Authorised source download |
| `POST /projects/{id}/mapping-revisions` | ENGINEER | Publish immutable mapping (empty body = default contract) |
| `POST /projects/{id}/validation-snapshots` | ENGINEER | Start async validation (202) |
| `GET /validation-snapshots/{id}`, `GET /validation-snapshots/{id}/issues` | member | Snapshot status/counts, filtered issues |
| `POST /validation-snapshots/{id}/exclusions` | REVIEWER | Exclude an invalid row with a reason |
| `POST /projects/{id}/manifests` | ENGINEER | Freeze manifest (409 until all invalid rows are excluded) |
| `POST /manifests/{id}/rehearsals` | ENGINEER | Launch rehearsal run (202) |
| `POST /manifests/{id}/cutovers` (+ `Idempotency-Key` header) | ENGINEER | Transactional cutover preflight + launch (202) |
| `GET /runs/{id}`, `POST /runs/{id}/restart` | member / ENGINEER | Run status; same-identity restart of `FAILED_RESTARTABLE` |
| `GET /runs/{id}/reconciliation` | member | Latest reconciliation report |
| `GET /runs/{id}/report?format=json\|csv` | member | Evidence download (CSV is formula-escaped) |
| `GET /rehearsals/{runId}/customers|orders` | member | Run-scoped rehearsal search |
| `GET /rehearsals/{runId}/uat` | member | UAT cases, latest results, defects, evidence hash |
| `POST /rehearsals/{runId}/uat-executions`, `POST .../defects`, `PATCH .../defects/{id}` | REVIEWER | UAT execution and defect lifecycle |
| `POST /manifests/{id}/approvals` | REVIEWER | Approve/reject with evidence hashes (creator cannot approve) |
| `GET /projects/{id}/audit-events` | member | Append-only audit trail |

Key error codes: `CSV_MALFORMED`, `HEADER_MISMATCH`, `PLAN_CHANGED`,
`EXCLUSIONS_UNRESOLVED`, `MANIFEST_NOT_FROZEN`, `SLOT_NOT_FREE`,
`TARGET_NOT_EMPTY`, `SELF_APPROVAL_FORBIDDEN`, `UAT_NOT_PASSED`,
`IDEMPOTENCY_CONFLICT`, `RUN_NOT_RESTARTABLE`, `VALIDATION_FAILED` (422).

## Public showcase API (anonymous, GET-only)

Base: `/api/v1/showcase`. No session, no mutations, pagination hard-capped
at size 50. Before bootstrap, `/overview` returns 200 with
`setupState = NOT_READY`; the other endpoints return 503
`SHOWCASE_NOT_READY`. No emails, password hashes, storage keys or raw file
downloads are exposed; actor references are roles, not identities.

| Route | Returns |
|---|---|
| `GET /overview` | setup/integrity state, project, snapshot counts, run statuses, money totals, verification hashes/timestamps |
| `GET /workflow` | 11 stages with status, real timestamps and artifact evidence |
| `GET /validation?page&size&code?` | ERROR issues of the showcase snapshot with the latest exclusion decision per row |
| `GET /mapping` | mapping revision + rule set definitions and hashes |
| `GET /reconciliation?scope=rehearsal\|cutover` | the stored report (status, summary, evidence hash) for the exact run |
| `GET /uat` | cases with latest executions, defects, approval summary, UAT evidence hash |
| `GET /audit?page&size` | sanitized audit events for the showcase project |
| `GET /records?kind=customers\|orders&page&size` | source→target joined rows with provenance |
| `GET /evidence` | JSON download bundling overview, workflow, both reports and UAT |

Internal (not public): `POST /api/v1/internal/showcase/register` and
`GET /api/v1/internal/showcase/scenario?slug=` — authenticated session +
`X-Showcase-Bootstrap-Token` header + server-side bootstrap-enabled flag;
a READY registration is refused with 422 `LINEAGE_INVALID` unless lineage
verification passes, in which case the scenario is recorded FAILED.

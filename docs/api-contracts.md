# API contracts

Base path `/api/v1`. Session cookie auth; state-changing calls require the
`X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie (fetch it via
`GET /api/v1/auth/csrf`). Errors are RFC 9457 `application/problem+json`
with a stable machine-readable `code`.

| Method & path | Role | Purpose |
|---|---|---|
| `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` | any | Session lifecycle |
| `POST /admin/users`, `PUT /projects/{id}/members/{userId}` | ADMIN | User and membership admin |
| `POST /projects`, `GET /projects`, `GET /projects/{id}`, `POST /projects/{id}/archive` | ENGINEER / member | Project lifecycle (archive blocked while a run is active) |
| `GET /projects/{id}/workflow` | member | Restores coherent workflow stage IDs across page refreshes |
| `POST /projects/{id}/bundles` (multipart `customers`, `orders`) | ENGINEER | Paired CSV intake |
| `GET /bundles/{bundleId}/profile` | member | Bundle profile (hashes, counts) |
| `GET /files/{fileId}/rows` | ENGINEER, AUDITOR | Paged raw-row preview with boundary checks |
| `GET /files/{fileId}/download` | ENGINEER, AUDITOR | Verified source download (SHA-256 integrity checked) |
| `POST /projects/{id}/mapping-revisions` | ENGINEER | Publish immutable mapping (empty body = default contract) |
| `POST /projects/{id}/validation-snapshots` | ENGINEER | Start async validation (202) |
| `GET /validation-snapshots/{id}`, `GET /validation-snapshots/{id}/issues` | member | Snapshot status/counts, paginated issues |
| `POST /validation-snapshots/{id}/exclusions` | REVIEWER | Exclude an invalid row with a reason |
| `POST /projects/{id}/manifests` | ENGINEER | Freeze manifest (409 until all invalid rows are excluded) |
| `POST /manifests/{id}/rehearsals` | ENGINEER | Launch rehearsal run (202) |
| `POST /manifests/{id}/cutovers` (+ `Idempotency-Key` header) | ENGINEER | Transactional cutover preflight + launch (202) |
| `GET /runs/{id}`, `POST /runs/{id}/restart` | member / ENGINEER | Run status; same-identity restart of `FAILED_RESTARTABLE` |
| `GET /runs/{id}/reconciliation` | member | Latest reconciliation report |
| `GET /runs/{id}/report?format=json\|csv` | member | Evidence download (CSV is formula-escaped) |
| `GET /rehearsals/{runId}/customers\|orders` | member | Run-scoped rehearsal search |
| `GET /rehearsals/{runId}/uat` | member | UAT cases, latest results, defects, evidence hash |
| `POST /rehearsals/{runId}/uat-executions`, `POST .../defects`, `PATCH .../defects/{id}` | REVIEWER | UAT execution and defect lifecycle |
| `POST /manifests/{id}/approvals` | REVIEWER | Approve/reject with evidence hashes (creator cannot approve) |
| `GET /projects/{id}/audit-events` | member | Append-only audit trail |

Key error codes: `CSV_MALFORMED`, `HEADER_MISMATCH`, `PLAN_CHANGED`,
`EXCLUSIONS_UNRESOLVED`, `MANIFEST_NOT_FROZEN`, `SLOT_NOT_FREE`,
`TARGET_NOT_EMPTY`, `SELF_APPROVAL_FORBIDDEN`, `UAT_NOT_PASSED`,
`IDEMPOTENCY_CONFLICT`, `RUN_NOT_RESTARTABLE`, `VALIDATION_FAILED` (422).

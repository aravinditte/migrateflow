# Schema overview

Four PostgreSQL schemas, created by Flyway migrations `V001`–`V012` in
`backend/src/main/resources/db/migration/`. Flyway owns all DDL; the
application never runs Hibernate-style auto-DDL. All source business records
are synthetic.

## control

- `users(id, email UNIQUE, password_hash, display_name, role, enabled)`
- `projects(id, name, status ACTIVE|ARCHIVED, created_by)`
- `project_memberships(project_id, user_id, active)` — effective access =
  role ∩ active membership (ADMIN has global access)
- `dataset_bundles(id, project_id, exported_at, status, uploaded_by)`
- `source_files(id, bundle_id, kind CUSTOMERS|ORDERS, filename, storage_key UNIQUE, bytes, sha256, row_count, uploaded_by)` — immutable
- `mapping_revisions(id, project_id, revision_no, definition JSONB, definition_hash)`
- `rule_set_revisions(id, project_id, revision_no, rules JSONB, rule_hash)` — immutable-revision pattern
- `validation_snapshots(id, project_id, bundle_id, mapping_revision_id, rule_set_revision_id, status, counts JSONB)`
- `validation_issues(id, snapshot_id, kind, source_row_id, data_row_number, field_name, code, message)`
- `exclusion_decisions(id, snapshot_id, kind, source_row_id, decision EXCLUDE|KEEP, reason, reviewer_id, decided_at)` — latest decision per (kind, source_row_id) wins (ordered by `decided_at, id`); append-only
- `manifests(id, project_id, bundle_id, mapping_revision_id, rule_set_revision_id, snapshot_id, manifest_hash UNIQUE, status FROZEN|SUPERSEDED, accepted counts)` — binds bundle file hashes, mapping/rule hashes, exclusion-decision hash, target-contract hash
- `runs(id, project_id, manifest_id, kind REHEARSAL|CUTOVER, status, request_key, approval_id, processed_rows, expected_accepted_rows, current_step, error_message, started_at, finished_at)`
- `applied_rows(id UUID PK, run_id, table_name, business_key, row_hash, target_id, applied_at)` — the restart ledger, unique index `(run_id, table_name, business_key)`
- `reconciliation_reports(id, run_id, status PASS|FAIL, summary JSONB, evidence_hash, completed_at)`
- `uat_cases / uat_executions / defects` — five required cases per verified rehearsal
- `approvals(id, manifest_id, rehearsal_run_id, reconciliation_report_id, uat_evidence_hash, decision, comment, reviewer_id, signed_at)`
- `target_cutover_slot(slot_id = 1, status FREE|RESERVED|VERIFIED, active_run_id, verified_run_id)` — singleton
- `audit_events(id UUID PK, project_id, actor_id, action, entity_type, entity_id, metadata_json, occurred_at)` — append-only
- `showcase_scenarios(id UUID PK, slug UNIQUE, project_id, bundle_id, mapping_revision_id, rule_set_revision_id, snapshot_id, manifest_id, rehearsal_run_id, approval_id, cutover_run_id, setup_state, setup_step, bootstrap_version, failure_message, verified_at, created_at, updated_at)`
- `BATCH_*` — Spring Batch 5.2 job repository, DDL pinned in `V010`

## stage

- `*_input_rows(bundle_id, file_id, row_number, physical_line_start, raw JSONB)` — parsed source, never mutated
- `*_validated_rows(snapshot_id, input_row_id, external_id, normalized fields..., raw_hash, row_hash, is_valid)`

## rehearsal

- `customers(rehearsal_run_id, external_customer_id, ...)` and
  `orders(rehearsal_run_id, external_order_id, ...)` — composite PKs and FKs
  scoped by run, so rehearsals are isolated and an order can only reference
  a customer from the same run.

## target

- `customers(id UUID PK, external_customer_id UNIQUE, ...)`,
  `orders(id UUID PK, external_order_id UNIQUE, customer_id FK → customers(id), ...)`
  with provenance columns (`source_bundle_id`, `source_row_id`,
  `manifest_id`, run id, row hash). Insert-only from the application.

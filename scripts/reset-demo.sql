-- MigrateFlow demo reset (spec 11): explicit operator action via psql.
-- There is intentionally NO reset HTTP endpoint.
--
-- Usage:
--   psql "$DATABASE_URL" -f scripts/reset-demo.sql
--
-- Removes all migration work and target data and returns the cutover slot
-- to FREE. Users are kept so the demo accounts still sign in; delete users
-- too if you also want the demo seed to recreate them on next start.
BEGIN;
TRUNCATE control.audit_events, control.approvals, control.defects,
         control.uat_executions, control.uat_cases, control.reconciliation_reports,
         control.applied_rows, control.runs, control.manifests,
         control.exclusion_decisions, control.validation_issues, control.validation_snapshots,
         control.rule_set_revisions, control.mapping_revisions, control.source_files,
         control.dataset_bundles, control.project_memberships, control.projects,
         stage.customer_input_rows, stage.order_input_rows,
         stage.customer_validated_rows, stage.order_validated_rows,
         rehearsal.customers, rehearsal.orders,
         target.customers, target.orders,
         control.batch_job_execution_params, control.batch_job_execution_context,
         control.batch_step_execution_context, control.batch_step_execution,
         control.batch_job_execution, control.batch_job_instance
         RESTART IDENTITY CASCADE;
-- The TRUNCATE ... CASCADE above also empties target_cutover_slot (it
-- references control.runs), so restore the singleton row explicitly.
INSERT INTO control.target_cutover_slot (slot_id, status, active_run_id, verified_run_id)
VALUES (1, 'FREE', NULL, NULL)
ON CONFLICT (slot_id) DO UPDATE SET
    status = 'FREE', active_run_id = NULL, verified_run_id = NULL, updated_at = now();
COMMIT;

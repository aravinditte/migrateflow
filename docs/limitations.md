# Known limitations

Stated plainly, by design or by scope:

- **Demonstration system.** MigrateFlow is a portfolio-grade workbench for
  one bounded migration. It is not a hosted migration service and must not
  be presented as production-proven.
- **Files only.** No direct database, SAP, Oracle or ERP connectors; source
  data arrives as two CSV extracts.
- **Single final target, insert-only cutover.** No rollback of a verified
  cutover, no multi-target fan-out, no near-zero-downtime replication.
  Recovery from a bad verified cutover is a new corrective migration.
- **Modular monolith.** One backend process; validation and run execution
  use a bounded in-process executor. No distributed workers.
- **Local file storage** for uploaded CSVs (single-node). No object storage.
- **Session auth** with four fixed roles; no SSO/OIDC, no MFA.
- **Verification environment.** Backend tests run against a real PostgreSQL
  (local or CI service). Docker Compose packaging is provided but its
  execution depends on the host; see `VERIFICATION-REPORT.md` for exactly
  what was executed where, and what was not run.
- **Throughput** figures, if any are quoted in the verification report, are
  local measurements of the fixture workload only — not capacity claims.
- **Public showcase UAT and approval are scripted**, executed by automated
  demo actors through the real gates. They demonstrate the controls, not
  human business judgement, and are labeled accordingly in the UI.
- **One showcase per database**: the singleton final target allows exactly
  one verified cutover; the showcase consumes it permanently. Rebuilding
  the demo means a fresh database, not a reset endpoint.
- **Public read model, not a sandbox**: anonymous visitors cannot run,
  reset or modify anything. An isolated interactive sandbox is out of
  scope (SPEC 20.5).

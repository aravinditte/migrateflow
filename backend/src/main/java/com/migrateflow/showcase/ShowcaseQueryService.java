package com.migrateflow.showcase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrateflow.common.ApiException;
import com.migrateflow.showcase.ShowcaseRegistryService.Scenario;
import com.migrateflow.uat.UatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read model for the public showcase (spec 5.2-5.4). Every figure is read
 * live from the persisted domain artifacts the registry points to; the
 * registry row itself is never treated as evidence. Before a scenario is
 * presented as READY, lineage across project, snapshot, manifest, runs,
 * reports, UAT, approval, slot, target and ledger is re-verified; any
 * mismatch downgrades the response to INTEGRITY_ALERT with the problems
 * listed, never a silent green.
 */
@Service
public class ShowcaseQueryService {

    private final JdbcTemplate jdbc;
    private final ShowcaseRegistryService registry;
    private final UatService uat;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String slug;

    public ShowcaseQueryService(JdbcTemplate jdbc, ShowcaseRegistryService registry, UatService uat,
                                @Value("${migrateflow.showcase.slug:hinata-trading}") String slug) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.uat = uat;
        this.slug = slug;
    }

    public String slug() { return slug; }

    public Scenario scenarioOrNotReady() {
        return registry.findBySlug(slug).orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "SHOWCASE_NOT_READY",
                "The showcase has not been bootstrapped yet"));
    }

    /**
     * Detail endpoints serve only a scenario whose registration is READY
     * AND whose lineage still verifies right now (review S-15). A
     * PREPARING or FAILED scenario exposes nothing but the overview state.
     */
    public Scenario readyScenario() {
        Scenario s = scenarioOrNotReady();
        if (!"READY".equals(s.setupState())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHOWCASE_NOT_READY",
                    "The showcase is " + s.setupState() + "; public evidence is served only for a READY scenario");
        }
        List<String> problems = verifyLineage(s);
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHOWCASE_INTEGRITY_ALERT",
                    "Current lineage check failed: " + String.join("; ", problems));
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Overview
    // ------------------------------------------------------------------

    public ShowcaseDto.Overview overview() {
        var maybe = registry.findBySlug(slug);
        if (maybe.isEmpty()) {
            return new ShowcaseDto.Overview(slug, "PUBLIC_READ_ONLY", "SYNTHETIC", "NOT_READY", null,
                    "NOT_CHECKED", List.of(), String.valueOf(java.time.Instant.now()), null,
                    new ShowcaseDto.BackendRef(true), null, null, null, null, null, null);
        }
        Scenario s = maybe.get();
        List<String> problems = "READY".equals(s.setupState()) ? verifyLineage(s) : List.of();
        String integrity = !"READY".equals(s.setupState()) ? "NOT_CHECKED"
                : problems.isEmpty() ? "OK" : "INTEGRITY_ALERT";
        String state = "READY".equals(s.setupState()) && !problems.isEmpty() ? "STALE" : s.setupState();

        ShowcaseDto.ProjectRef project = s.projectId() == null ? null : jdbc.query(
                "SELECT name, created_at FROM control.projects WHERE id = ?",
                (rs, n) -> new ShowcaseDto.ProjectRef(rs.getString("name"), "Hinata Trading Co.", ts(rs.getTimestamp("created_at"))),
                s.projectId()).stream().findFirst().orElse(null);

        ShowcaseDto.ValidationCounts counts = null;
        if (s.snapshotId() != null) {
            counts = snapshotCounts(s.snapshotId());
        }
        ShowcaseDto.RunRef rehearsal = runRef(s.rehearsalRunId());
        ShowcaseDto.RunRef cutover = runRef(s.cutoverRunId());

        ShowcaseDto.Money money = null;
        String cutoverEvidenceHash = null;
        String cutoverReportCompletedAt = null;
        if (s.cutoverRunId() != null) {
            Map<String, Object> report = latestReport(s.cutoverRunId());
            if (report != null) {
                cutoverEvidenceHash = (String) report.get("evidence_hash");
                cutoverReportCompletedAt = ts((Timestamp) report.get("completed_at"));
                JsonNode orders = summaryNode(report).path("orders");
                money = new ShowcaseDto.Money("JPY",
                        orders.path("acceptedAmountYen").asLong(0),
                        orders.path("writtenAmountYen").asLong(0));
            }
        }
        String manifestHash = s.manifestId() == null ? null : jdbc.queryForObject(
                "SELECT manifest_hash FROM control.manifests WHERE id = ?", String.class, s.manifestId());
        ShowcaseDto.Verification verification = new ShowcaseDto.Verification(
                s.verifiedAt(), cutoverReportCompletedAt, s.createdAt(),
                manifestHash == null ? null : manifestHash.trim().substring(0, Math.min(12, manifestHash.trim().length())),
                manifestHash == null ? null : manifestHash.trim(),
                cutoverEvidenceHash == null ? null : cutoverEvidenceHash.trim());

        return new ShowcaseDto.Overview(slug, "PUBLIC_READ_ONLY", "SYNTHETIC", state, s.setupStep(),
                integrity, problems, String.valueOf(java.time.Instant.now()), s.failureMessage(),
                new ShowcaseDto.BackendRef(true), project, counts, rehearsal, cutover, money, verification);
    }

    private ShowcaseDto.RunRef runRef(UUID runId) {
        if (runId == null) return null;
        String status = jdbc.queryForObject("SELECT status FROM control.runs WHERE id = ?", String.class, runId);
        Map<String, Object> report = latestReport(runId);
        return new ShowcaseDto.RunRef(status, report == null ? null : (String) report.get("status"));
    }

    private ShowcaseDto.ValidationCounts snapshotCounts(UUID snapshotId) {
        String json = jdbc.queryForObject(
                "SELECT source_counts_json::text FROM control.validation_snapshots WHERE id = ?", String.class, snapshotId);
        try {
            JsonNode root = mapper.readTree(json);
            return new ShowcaseDto.ValidationCounts(
                    root.path("customers").path("raw").asLong(), root.path("orders").path("raw").asLong(),
                    root.path("customers").path("valid").asLong(), root.path("orders").path("valid").asLong(),
                    root.path("customers").path("invalid").asLong(), root.path("orders").path("invalid").asLong());
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable snapshot counts", e);
        }
    }

    private Map<String, Object> latestReport(UUID runId) {
        return jdbc.queryForList(
                "SELECT id, status, summary_json::text AS summary, evidence_hash, completed_at "
                        + "FROM control.reconciliation_reports WHERE run_id = ? ORDER BY completed_at DESC LIMIT 1",
                runId).stream().findFirst().orElse(null);
    }

    private JsonNode summaryNode(Map<String, Object> report) {
        try {
            return mapper.readTree(String.valueOf(report.get("summary")));
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable reconciliation summary", e);
        }
    }

    // ------------------------------------------------------------------
    // Workflow timeline (11 stages, each backed by its real artifact)
    // ------------------------------------------------------------------

    public ShowcaseDto.Workflow workflow() {
        Scenario s = readyScenario();
        List<ShowcaseDto.Stage> stages = new ArrayList<>();

        stages.add(stage("project", "Project created", s.projectId() != null ? "COMPLETED" : "PENDING",
                s.projectId() == null ? null : tsField("SELECT created_at FROM control.projects WHERE id = ?", s.projectId()),
                Map.of("projectId", str(s.projectId()))));
        stages.add(stage("intake", "Intake — paired CSV upload", s.bundleId() != null ? "COMPLETED" : "PENDING",
                s.bundleId() == null ? null : tsField("SELECT created_at FROM control.dataset_bundles WHERE id = ?", s.bundleId()),
                Map.of("bundleId", str(s.bundleId()))));
        stages.add(stage("mapping", "Mapping revision published", s.mappingRevisionId() != null ? "COMPLETED" : "PENDING",
                s.mappingRevisionId() == null ? null : tsField("SELECT created_at FROM control.mapping_revisions WHERE id = ?", s.mappingRevisionId()),
                Map.of("mappingRevisionId", str(s.mappingRevisionId()), "ruleSetRevisionId", str(s.ruleSetRevisionId()))));

        String snapshotStatus = s.snapshotId() == null ? null : jdbc.queryForObject(
                "SELECT status FROM control.validation_snapshots WHERE id = ?", String.class, s.snapshotId());
        stages.add(stage("validation", "Validation snapshot",
                "COMPLETED".equals(snapshotStatus) ? "COMPLETED" : "FAILED".equals(snapshotStatus) ? "FAILED" : s.snapshotId() == null ? "PENDING" : "IN_PROGRESS",
                s.snapshotId() == null ? null : tsField("SELECT validated_at FROM control.validation_snapshots WHERE id = ?", s.snapshotId()),
                Map.of("snapshotId", str(s.snapshotId()), "status", str(snapshotStatus))));

        long excluded = s.snapshotId() == null ? 0 : jdbc.queryForObject(
                "SELECT count(DISTINCT (kind, source_row_id)) FROM control.exclusion_decisions WHERE snapshot_id = ? AND decision = 'EXCLUDE'",
                Long.class, s.snapshotId());
        long invalidTotal = 0;
        if (s.snapshotId() != null) {
            ShowcaseDto.ValidationCounts c = snapshotCounts(s.snapshotId());
            invalidTotal = c.excludedCustomers() + c.excludedOrders();
        }
        stages.add(stage("exclusions", "Reviewer exclusion decisions",
                invalidTotal > 0 && excluded >= invalidTotal ? "COMPLETED" : excluded > 0 ? "IN_PROGRESS" : "PENDING",
                s.snapshotId() == null ? null : tsField("SELECT max(decided_at) FROM control.exclusion_decisions WHERE snapshot_id = ?", s.snapshotId()),
                Map.of("excludedRows", String.valueOf(excluded), "invalidRows", String.valueOf(invalidTotal))));

        String manifestHash = s.manifestId() == null ? null : jdbc.queryForObject(
                "SELECT manifest_hash FROM control.manifests WHERE id = ?", String.class, s.manifestId());
        stages.add(stage("freeze", "Manifest frozen", s.manifestId() != null ? "COMPLETED" : "PENDING",
                s.manifestId() == null ? null : tsField("SELECT created_at FROM control.manifests WHERE id = ?", s.manifestId()),
                Map.of("manifestId", str(s.manifestId()), "manifestFingerprint",
                        manifestHash == null ? "" : manifestHash.trim().substring(0, Math.min(12, manifestHash.trim().length())))));

        stages.add(runStage("rehearsal", "Rehearsal (isolated Spring Batch run)", s.rehearsalRunId()));
        Map<String, Object> rehearsalReport = s.rehearsalRunId() == null ? null : latestReport(s.rehearsalRunId());
        stages.add(stage("reconciliation", "Rehearsal reconciliation",
                rehearsalReport == null ? "PENDING" : "PASS".equals(rehearsalReport.get("status")) ? "COMPLETED" : "FAILED",
                rehearsalReport == null ? null : ts((Timestamp) rehearsalReport.get("completed_at")),
                Map.of("status", rehearsalReport == null ? "" : str(rehearsalReport.get("status")),
                        "evidenceHash", rehearsalReport == null ? "" : str(rehearsalReport.get("evidence_hash")).trim())));

        stages.add(uatStage(s));
        Map<String, Object> approval = s.approvalId() == null ? null : jdbc.queryForMap(
                "SELECT decision, created_at FROM control.approvals WHERE id = ?", s.approvalId());
        stages.add(stage("approval", "Reviewer approval (automated demo actor)",
                approval == null ? "PENDING" : "APPROVED".equals(approval.get("decision")) ? "COMPLETED" : "FAILED",
                approval == null ? null : ts((Timestamp) approval.get("created_at")),
                Map.of("approvalId", str(s.approvalId()))));
        stages.add(runStage("cutover", "Final cutover", s.cutoverRunId()));

        return new ShowcaseDto.Workflow(s.setupState(), stages);
    }

    private ShowcaseDto.Stage runStage(String key, String title, UUID runId) {
        if (runId == null) return stage(key, title, "PENDING", null, Map.of());
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT status, finished_at, current_step, processed_rows FROM control.runs WHERE id = ?", runId);
        String status = (String) run.get("status");
        String mapped = switch (status) {
            case "VERIFIED" -> "COMPLETED";
            case "VERIFICATION_FAILED", "FAILED_TERMINAL" -> "FAILED";
            case "RUNNING", "QUEUED", "BATCH_COMPLETED", "FAILED_RESTARTABLE" -> "IN_PROGRESS";
            default -> "PENDING";
        };
        return stage(key, title, mapped, ts((Timestamp) run.get("finished_at")),
                Map.of("runId", runId.toString(), "runStatus", status,
                        "processedRows", str(run.get("processed_rows"))));
    }

    private ShowcaseDto.Stage uatStage(Scenario s) {
        if (s.rehearsalRunId() == null) return stage("uat", "Automated synthetic UAT", "PENDING", null, Map.of());
        List<UatService.CaseView> cases = uat.cases(s.rehearsalRunId());
        long passed = cases.stream().filter(c -> c.required() && "PASS".equals(c.latestResult())).count();
        long required = cases.stream().filter(UatService.CaseView::required).count();
        boolean anyFail = cases.stream().anyMatch(c -> "FAIL".equals(c.latestResult()) || "BLOCKED".equals(c.latestResult()));
        String status = required > 0 && passed == required ? "COMPLETED" : anyFail ? "FAILED" : passed > 0 ? "IN_PROGRESS" : "PENDING";
        String at = tsField("SELECT max(e.executed_at) FROM control.uat_executions e JOIN control.uat_cases c ON c.id = e.case_id WHERE c.rehearsal_run_id = ?", s.rehearsalRunId());
        return stage("uat", "Automated synthetic UAT", status, at,
                Map.of("passedRequired", String.valueOf(passed), "requiredCases", String.valueOf(required)));
    }

    private ShowcaseDto.Stage stage(String key, String title, String status, String at, Map<String, String> evidence) {
        return new ShowcaseDto.Stage(key, title, status, at, evidence);
    }

    // ------------------------------------------------------------------
    // Detail read models
    // ------------------------------------------------------------------

    public ShowcaseDto.IssuePage validationIssues(int page, int size, String code) {
        Scenario s = readyScenario();
        String sql = "SELECT i.kind, i.code, i.field, i.message, "
                + "(SELECT r.data_row_number FROM stage.customer_input_rows r WHERE r.id = i.source_row_id AND i.kind = 'CUSTOMERS' "
                + " UNION ALL SELECT r.data_row_number FROM stage.order_input_rows r WHERE r.id = i.source_row_id AND i.kind = 'ORDERS') AS row_no, "
                + "d.decision, d.reason, u.role AS reviewer_role "
                + "FROM control.validation_issues i "
                + "LEFT JOIN LATERAL (SELECT decision, reason, reviewer_id FROM control.exclusion_decisions dd "
                + "  WHERE dd.snapshot_id = i.snapshot_id AND dd.kind = i.kind AND dd.source_row_id = i.source_row_id "
                + "  ORDER BY dd.decided_at DESC LIMIT 1) d ON true "
                + "LEFT JOIN control.users u ON u.id = d.reviewer_id "
                + "WHERE i.snapshot_id = ? AND i.severity = 'ERROR'"
                + (code == null || code.isBlank() ? "" : " AND i.code = ?")
                + " ORDER BY i.kind, row_no NULLS LAST, i.code LIMIT ? OFFSET ?";
        Object[] args = code == null || code.isBlank()
                ? new Object[]{s.snapshotId(), size, com.migrateflow.common.PageRequestUtil.safeOffset(page, size)}
                : new Object[]{s.snapshotId(), code, size, com.migrateflow.common.PageRequestUtil.safeOffset(page, size)};
        List<ShowcaseDto.IssueRow> rows = jdbc.query(sql, (rs, n) -> new ShowcaseDto.IssueRow(
                rs.getString("kind"), rs.getInt("row_no"), rs.getString("code"),
                rs.getString("field") == null ? "" : rs.getString("field"), rs.getString("message"),
                rs.getString("decision") == null ? "" : rs.getString("decision"),
                rs.getString("reason") == null ? "" : rs.getString("reason"),
                rs.getString("reviewer_role") == null ? "" : rs.getString("reviewer_role")), args);
        return new ShowcaseDto.IssuePage(rows, page, size);
    }

    public ShowcaseDto.MappingView mapping() {
        Scenario s = readyScenario();
        Map<String, Object> m = jdbc.queryForMap(
                "SELECT id, revision_no, definition_hash, definition_json::text AS def FROM control.mapping_revisions WHERE id = ?",
                s.mappingRevisionId());
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT id, rule_hash, rule_json::text AS rules FROM control.rule_set_revisions WHERE id = ?",
                s.ruleSetRevisionId());
        return new ShowcaseDto.MappingView(m.get("id").toString(), ((Number) m.get("revision_no")).intValue(),
                ((String) m.get("definition_hash")).trim(), (String) m.get("def"),
                r.get("id").toString(), ((String) r.get("rule_hash")).trim(), (String) r.get("rules"));
    }

    public ShowcaseDto.ReconciliationView reconciliation(String scope) {
        Scenario s = readyScenario();
        UUID runId = "cutover".equals(scope) ? s.cutoverRunId() : s.rehearsalRunId();
        if (runId == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHOWCASE_NOT_READY",
                    "No " + scope + " run is linked to the showcase yet");
        }
        Map<String, Object> report = latestReport(runId);
        if (report == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHOWCASE_NOT_READY",
                    "No reconciliation report exists for the " + scope + " run yet");
        }
        Map<String, Object> summary;
        try {
            summary = mapper.convertValue(summaryNode(report), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable reconciliation summary", e);
        }
        return new ShowcaseDto.ReconciliationView(scope, runId.toString(), (String) report.get("status"),
                ((String) report.get("evidence_hash")).trim(), ts((Timestamp) report.get("completed_at")), summary);
    }

    public ShowcaseDto.UatView uat() {
        Scenario s = readyScenario();
        List<ShowcaseDto.UatCaseView> cases = jdbc.query(
                "SELECT c.case_key, c.title, c.required, e.result, e.actual_result, e.executed_at, u.role AS exec_role "
                        + "FROM control.uat_cases c "
                        + "LEFT JOIN LATERAL (SELECT result, actual_result, executed_at, executed_by FROM control.uat_executions ee "
                        + "  WHERE ee.case_id = c.id ORDER BY ee.executed_at DESC LIMIT 1) e ON true "
                        + "LEFT JOIN control.users u ON u.id = e.executed_by "
                        + "WHERE c.rehearsal_run_id = ? ORDER BY c.case_key",
                (rs, n) -> new ShowcaseDto.UatCaseView(
                        rs.getString("case_key"), rs.getString("title"), rs.getBoolean("required"),
                        rs.getString("result") == null ? "" : rs.getString("result"),
                        rs.getString("actual_result") == null ? "" : rs.getString("actual_result"),
                        ts(rs.getTimestamp("executed_at")),
                        rs.getString("exec_role") == null ? "" : rs.getString("exec_role")),
                s.rehearsalRunId());
        List<ShowcaseDto.DefectView> defects = jdbc.query(
                "SELECT d.severity, d.status, d.description, c.case_key FROM control.defects d "
                        + "LEFT JOIN control.uat_cases c ON c.id = d.uat_case_id "
                        + "WHERE d.rehearsal_run_id = ? ORDER BY d.created_at",
                (rs, n) -> new ShowcaseDto.DefectView(rs.getString("severity"), rs.getString("status"),
                        rs.getString("description"), rs.getString("case_key") == null ? "" : rs.getString("case_key")),
                s.rehearsalRunId());
        String approvalStatus = "", approvedByRole = "", approvedAt = null;
        if (s.approvalId() != null) {
            Map<String, Object> a = jdbc.queryForMap(
                    "SELECT a.decision, a.created_at, u.role FROM control.approvals a "
                            + "JOIN control.users u ON u.id = a.reviewer_id WHERE a.id = ?", s.approvalId());
            approvalStatus = (String) a.get("decision");
            approvedByRole = (String) a.get("role");
            approvedAt = ts((Timestamp) a.get("created_at"));
        }
        return new ShowcaseDto.UatView(cases, defects,
                s.rehearsalRunId() == null ? "" : uat.uatEvidenceHash(s.rehearsalRunId()),
                approvalStatus, approvedByRole, approvedAt);
    }

    public ShowcaseDto.AuditPage audit(int page, int size) {
        Scenario s = readyScenario();
        List<ShowcaseDto.AuditRow> rows = jdbc.query(
                "SELECT a.action, a.entity_type, a.occurred_at, u.role FROM control.audit_events a "
                        + "LEFT JOIN control.users u ON u.id = a.actor_id WHERE a.project_id = ? "
                        + "ORDER BY a.occurred_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> new ShowcaseDto.AuditRow(rs.getString("action"), rs.getString("entity_type"),
                        rs.getString("role") == null ? "" : rs.getString("role"), ts(rs.getTimestamp("occurred_at"))),
                s.projectId(), size, com.migrateflow.common.PageRequestUtil.safeOffset(page, size));
        return new ShowcaseDto.AuditPage(rows, page, size);
    }

    public ShowcaseDto.RecordPage records(String kind, int page, int size) {
        Scenario s = readyScenario();
        if (s.cutoverRunId() == null) return new ShowcaseDto.RecordPage(List.of(), page, size);
        boolean customers = !"orders".equals(kind);
        String table = customers ? "target.customers" : "target.orders";
        String stageTable = customers ? "stage.customer_input_rows" : "stage.order_input_rows";
        String idCol = customers ? "external_customer_id" : "external_order_id";
        List<ShowcaseDto.RecordRow> rows = jdbc.query(
                "SELECT t." + idCol + " AS ext_id, t.source_row_number, t.origin_run_id, f.filename, r.raw_json::text AS raw, "
                        + (customers
                                ? "t.full_name, t.email, t.joined_on"
                                : "t.external_order_id, t.order_date, t.amount_yen, t.status, "
                                  + "(SELECT c.external_customer_id FROM target.customers c WHERE c.id = t.customer_id) AS cust_ext")
                        + " FROM " + table + " t "
                        + "JOIN control.source_files f ON f.id = t.source_file_id "
                        + "JOIN " + stageTable + " r ON r.file_id = t.source_file_id AND r.data_row_number = t.source_row_number "
                        // Only the showcase cutover's own rows (S-16): an
                        // operator's other work in the same target tables
                        // must never leak into the public read model.
                        + "WHERE t.origin_run_id = ? "
                        + "ORDER BY t." + idCol + " LIMIT ? OFFSET ?",
                (rs, n) -> {
                    Map<String, String> source;
                    try {
                        Map<String, String> parsed = mapper.readValue(rs.getString("raw"), new TypeReference<>() {});
                        // Explicit source-field allowlist (S-16): only the
                        // known synthetic fixture columns are publishable.
                        List<String> allowed = customers
                                ? List.of("Cust_No", "Customer_Name", "Email", "Join_Date")
                                : List.of("Order_No", "Cust_No", "Order_Dt", "Amount_JPY", "State");
                        Map<String, String> filtered = new LinkedHashMap<>();
                        for (String key : allowed) {
                            if (parsed.containsKey(key)) filtered.put(key, parsed.get(key));
                        }
                        source = filtered;
                    } catch (Exception e) {
                        source = Map.of();
                    }
                    Map<String, Object> target = new LinkedHashMap<>();
                    if (customers) {
                        target.put("externalCustomerId", rs.getString("ext_id"));
                        target.put("fullName", rs.getString("full_name"));
                        target.put("email", rs.getString("email") == null ? "" : rs.getString("email"));
                        target.put("joinedOn", rs.getDate("joined_on") == null ? "" : rs.getDate("joined_on").toString());
                    } else {
                        target.put("externalOrderId", rs.getString("ext_id"));
                        target.put("externalCustomerId", rs.getString("cust_ext"));
                        target.put("orderDate", rs.getDate("order_date").toString());
                        target.put("amountYen", rs.getLong("amount_yen"));
                        target.put("status", rs.getString("status"));
                    }
                    return new ShowcaseDto.RecordRow(customers ? "customers" : "orders", rs.getString("ext_id"),
                            source, target, rs.getString("filename"), rs.getInt("source_row_number"),
                            rs.getObject("origin_run_id", UUID.class).toString());
                }, s.cutoverRunId(), size, com.migrateflow.common.PageRequestUtil.safeOffset(page, size));
        return new ShowcaseDto.RecordPage(rows, page, size);
    }

    public Map<String, Object> evidence() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("showcase", slug);
        doc.put("dataClassification", "SYNTHETIC");
        doc.put("label", "Synthetic source data; authentic backend execution; automated demonstration actors");
        doc.put("overview", overview());
        doc.put("workflow", workflow());
        doc.put("reconciliationRehearsal", reconciliation("rehearsal"));
        doc.put("reconciliationCutover", reconciliation("cutover"));
        doc.put("uat", uat());
        return doc;
    }

    // ------------------------------------------------------------------
    // Lineage verification (spec 5.4) - used on READY reads and by the
    // bootstrap register gate. Returns human-readable problems; empty
    // means the linked artifacts form one coherent, verified journey.
    // ------------------------------------------------------------------

    public List<String> verifyLineage(Scenario s) {
        List<String> problems = new ArrayList<>();
        if (s.projectId() == null || s.bundleId() == null || s.snapshotId() == null || s.manifestId() == null
                || s.rehearsalRunId() == null || s.cutoverRunId() == null || s.approvalId() == null) {
            problems.add("showcase registry is missing one or more artifact links");
            return problems;
        }
        check(problems, "bundle belongs to another project",
                "SELECT count(*) FROM control.dataset_bundles WHERE id = ? AND project_id = ?", s.bundleId(), s.projectId());
        check(problems, "mapping revision belongs to another project",
                "SELECT count(*) FROM control.mapping_revisions WHERE id = ? AND project_id = ?", s.mappingRevisionId(), s.projectId());
        check(problems, "snapshot belongs to another project",
                "SELECT count(*) FROM control.validation_snapshots WHERE id = ? AND project_id = ?", s.snapshotId(), s.projectId());
        check(problems, "manifest does not bind the showcase bundle/mapping/rules/snapshot",
                "SELECT count(*) FROM control.manifests WHERE id = ? AND project_id = ? AND bundle_id = ? "
                        + "AND mapping_revision_id = ? AND rule_set_revision_id = ? AND snapshot_id = ?",
                s.manifestId(), s.projectId(), s.bundleId(), s.mappingRevisionId(), s.ruleSetRevisionId(), s.snapshotId());
        check(problems, "snapshot is not COMPLETED",
                "SELECT count(*) FROM control.validation_snapshots WHERE id = ? AND status = 'COMPLETED'", s.snapshotId());
        check(problems, "manifest is not FROZEN",
                "SELECT count(*) FROM control.manifests WHERE id = ? AND status = 'FROZEN'", s.manifestId());
        check(problems, "rehearsal run is not a VERIFIED rehearsal of the manifest",
                "SELECT count(*) FROM control.runs WHERE id = ? AND kind = 'REHEARSAL' AND status = 'VERIFIED' AND manifest_id = ?",
                s.rehearsalRunId(), s.manifestId());
        check(problems, "cutover run is not a VERIFIED cutover of the manifest under the approval",
                "SELECT count(*) FROM control.runs WHERE id = ? AND kind = 'CUTOVER' AND status = 'VERIFIED' AND manifest_id = ? AND approval_id = ?",
                s.cutoverRunId(), s.manifestId(), s.approvalId());

        Map<String, Object> rehearsalReport = latestReport(s.rehearsalRunId());
        Map<String, Object> cutoverReport = latestReport(s.cutoverRunId());
        if (rehearsalReport == null || !"PASS".equals(rehearsalReport.get("status"))) {
            problems.add("latest rehearsal reconciliation report is not PASS");
        }
        if (cutoverReport == null || !"PASS".equals(cutoverReport.get("status"))) {
            problems.add("latest cutover reconciliation report is not PASS");
        }
        if (rehearsalReport != null) {
            Map<String, Object> approval = jdbc.queryForMap(
                    "SELECT manifest_id, rehearsal_run_id, reconciliation_report_id, uat_evidence_hash FROM control.approvals WHERE id = ?",
                    s.approvalId());
            if (!s.manifestId().equals(approval.get("manifest_id"))
                    || !s.rehearsalRunId().equals(approval.get("rehearsal_run_id"))
                    || !rehearsalReport.get("id").equals(approval.get("reconciliation_report_id"))) {
                problems.add("approval does not bind this manifest, rehearsal and reconciliation report");
            }
            String approvedHash = approval.get("uat_evidence_hash") == null ? ""
                    : String.valueOf(approval.get("uat_evidence_hash")).trim();
            if (!uat.uatEvidenceHash(s.rehearsalRunId()).equals(approvedHash)) {
                problems.add("approval UAT evidence hash no longer matches current UAT evidence");
            }
        }
        String blocker = uat.approvalBlocker(s.rehearsalRunId());
        if (blocker != null) {
            problems.add("UAT gate: " + blocker);
        }
        check(problems, "cutover slot is not VERIFIED by the showcase cutover run",
                "SELECT count(*) FROM control.target_cutover_slot WHERE slot_id = 1 AND status = 'VERIFIED' AND verified_run_id = ?",
                s.cutoverRunId());

        ShowcaseDto.ValidationCounts counts = snapshotCounts(s.snapshotId());
        long customers = jdbc.queryForObject("SELECT count(*) FROM target.customers", Long.class);
        long orders = jdbc.queryForObject("SELECT count(*) FROM target.orders", Long.class);
        if (customers != counts.acceptedCustomers()) {
            problems.add("target customer count " + customers + " != accepted " + counts.acceptedCustomers());
        }
        if (orders != counts.acceptedOrders()) {
            problems.add("target order count " + orders + " != accepted " + counts.acceptedOrders());
        }
        if (cutoverReport != null) {
            long written = summaryNode(cutoverReport).path("orders").path("writtenAmountYen").asLong(-1);
            long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount_yen),0) FROM target.orders", Long.class);
            if (written != sum) {
                problems.add("target order total " + sum + " != reconciled written total " + written);
            }
        }
        long applied = jdbc.queryForObject(
                "SELECT count(*) FROM control.applied_rows WHERE run_id = ?", Long.class, s.cutoverRunId());
        if (applied != counts.acceptedCustomers() + counts.acceptedOrders()) {
            problems.add("applied-rows ledger for the cutover holds " + applied + " rows, expected "
                    + (counts.acceptedCustomers() + counts.acceptedOrders()));
        }
        return problems;
    }

    private void check(List<String> problems, String problem, String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        if (n == null || n == 0) problems.add(problem);
    }

    private String tsField(String sql, UUID id) {
        return jdbc.query(sql, (rs, n) -> ts(rs.getTimestamp(1)), id).stream().findFirst().orElse(null);
    }

    private static String ts(Timestamp t) {
        return t == null ? null : String.valueOf(t.toInstant());
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}

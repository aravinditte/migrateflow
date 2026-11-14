package com.migrateflow.uat;

import com.migrateflow.common.ApiException;
import com.migrateflow.common.CanonicalHash;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** Scenario-specific UAT checklist, append-only executions, defects (spec 10.4). */
@Service
public class UatService {
    private final JdbcTemplate jdbc;

    public UatService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static final List<String[]> DEFAULT_CASES = List.of(
            new String[]{"COUNTS_MATCH", "Rehearsal row counts match reconciliation",
                    "Visible customer/order counts for this rehearsal equal the reconciliation report"},
            new String[]{"CUSTOMER_SPOT_CHECK", "Spot-check migrated customers",
                    "Sampled customers show the normalized name, email and joining date from the source export"},
            new String[]{"ORDER_TOTALS", "Spot-check order values and totals",
                    "Sampled orders show correct dates, statuses and yen amounts; totals match reconciliation"},
            new String[]{"EXCLUSIONS_ABSENT", "Excluded rows are absent",
                    "Rows with approved exclusions do not appear in the rehearsal data"},
            new String[]{"PROVENANCE_TRACE", "Provenance is traceable",
                    "Sampled rows trace back to their source file and source row number"});

    public void createDefaultCases(UUID rehearsalRunId) {
        for (String[] c : DEFAULT_CASES) {
            jdbc.update("INSERT INTO control.uat_cases (id, rehearsal_run_id, case_key, title, expected_result, required) "
                            + "VALUES (?,?,?,?,?,TRUE) ON CONFLICT (rehearsal_run_id, case_key) DO NOTHING",
                    UUID.randomUUID(), rehearsalRunId, c[0], c[1], c[2]);
        }
    }

    public record CaseView(UUID caseId, String caseKey, String title, String expectedResult,
                           boolean required, String latestResult) {}

    public List<CaseView> cases(UUID rehearsalRunId) {
        return jdbc.query(
                "SELECT c.id, c.case_key, c.title, c.expected_result, c.required, "
                        + "(SELECT e.result FROM control.uat_executions e WHERE e.case_id = c.id ORDER BY e.executed_at DESC, e.created_at DESC LIMIT 1) AS latest_result "
                        + "FROM control.uat_cases c WHERE c.rehearsal_run_id = ? ORDER BY c.case_key",
                (rs, n) -> new CaseView(rs.getObject("id", UUID.class), rs.getString("case_key"),
                        rs.getString("title"), rs.getString("expected_result"), rs.getBoolean("required"),
                        rs.getString("latest_result") == null ? "NOT_RUN" : rs.getString("latest_result")),
                rehearsalRunId);
    }

    public List<Map<String, Object>> defects(UUID rehearsalRunId) {
        return jdbc.query(
                "SELECT id, uat_case_id, severity, status, description, resolution_note, created_at FROM control.defects "
                        + "WHERE rehearsal_run_id = ? ORDER BY created_at",
                (rs, n) -> Map.<String, Object>of(
                        "defectId", rs.getObject("id", UUID.class).toString(),
                        "uatCaseId", rs.getObject("uat_case_id", UUID.class) == null ? "" : rs.getObject("uat_case_id", UUID.class).toString(),
                        "severity", rs.getString("severity"),
                        "status", rs.getString("status"),
                        "description", rs.getString("description"),
                        "resolutionNote", rs.getString("resolution_note") == null ? "" : rs.getString("resolution_note")),
                rehearsalRunId);
    }

    /** Canonical digest of the current UAT evidence for a rehearsal (spec 6). */
    public String uatEvidenceHash(UUID rehearsalRunId) {
        List<String> lines = new ArrayList<>();
        jdbc.query("SELECT c.case_key, "
                        + "(SELECT e.result || '|' || e.executed_by || '|' || e.executed_at FROM control.uat_executions e "
                        + " WHERE e.case_id = c.id ORDER BY e.executed_at DESC, e.created_at DESC LIMIT 1) AS latest "
                        + "FROM control.uat_cases c WHERE c.rehearsal_run_id = ? ORDER BY c.case_key",
                (rs, n) -> {
                    lines.add(rs.getString("case_key") + "=" + rs.getString("latest"));
                    return null;
                }, rehearsalRunId);
        jdbc.query("SELECT id, severity, status FROM control.defects WHERE rehearsal_run_id = ? ORDER BY id",
                (rs, n) -> {
                    lines.add("defect:" + rs.getObject("id", UUID.class) + "=" + rs.getString("severity") + ":" + rs.getString("status"));
                    return null;
                }, rehearsalRunId);
        return CanonicalHash.sha256Hex(String.join("\n", lines));
    }

    /** Approval gate: required cases all PASS, no unclosed blocking defect. Returns a blocker or null. */
    public String approvalBlocker(UUID rehearsalRunId) {
        for (CaseView c : cases(rehearsalRunId)) {
            if (c.required() && !"PASS".equals(c.latestResult())) {
                return "Required UAT case " + c.caseKey() + " has not passed (latest: " + c.latestResult() + ")";
            }
        }
        Long open = jdbc.queryForObject(
                "SELECT count(*) FROM control.defects WHERE rehearsal_run_id = ? AND severity IN ('BLOCKER','CRITICAL') AND status <> 'CLOSED'",
                Long.class, rehearsalRunId);
        if (open != null && open > 0) {
            return open + " blocking defect(s) are not closed (resolution requires a passing retest)";
        }
        return null;
    }
}

package com.migrateflow.showcase;

import java.util.List;
import java.util.Map;

/**
 * Allowlisted public payloads for the read-only showcase (Automated
 * Showcase spec 5.2/5.3). Every value is computed from persisted domain
 * artifacts by ShowcaseQueryService; nothing here is a stored copy of a
 * business result, and no field exposes emails, password hashes, storage
 * keys, or internal file paths.
 */
public final class ShowcaseDto {
    private ShowcaseDto() {}

    public record Overview(
            String showcase,
            String mode,
            String dataClassification,
            String setupState,
            String setupStep,
            String integrity,
            List<String> integrityProblems,
            String failureMessage,
            BackendRef backend,
            ProjectRef project,
            ValidationCounts validation,
            RunRef rehearsal,
            RunRef cutover,
            Money money,
            Verification verification) {}

    public record BackendRef(boolean connected) {}

    public record ProjectRef(String name, String organization, String createdAt) {}

    public record ValidationCounts(
            long rawCustomers, long rawOrders,
            long acceptedCustomers, long acceptedOrders,
            long excludedCustomers, long excludedOrders) {}

    public record RunRef(String status, String reconciliation) {}

    public record Money(String currency, long acceptedTotalYen, long writtenTotalYen) {}

    public record Verification(String verifiedAt, String manifestFingerprint,
                               String manifestHash, String reportEvidenceHash) {}

    public record Stage(String key, String title, String status, String occurredAt,
                        Map<String, String> evidence) {}

    public record Workflow(String setupState, List<Stage> stages) {}

    public record IssueRow(String kind, int dataRowNumber, String code, String field, String message,
                           String decision, String decisionReason, String decidedByRole) {}

    public record IssuePage(List<IssueRow> content, int page, int size) {}

    public record MappingView(String revisionId, int revisionNo, String definitionHash,
                              String definition, String ruleSetRevisionId, String ruleHash, String rules) {}

    public record ReconciliationView(String scope, String runId, String status, String evidenceHash,
                                     String completedAt, Map<String, Object> summary) {}

    public record UatCaseView(String caseKey, String title, boolean required, String latestResult,
                              String latestActualResult, String latestExecutedAt, String latestExecutedByRole) {}

    public record DefectView(String severity, String status, String description, String caseKey) {}

    public record UatView(List<UatCaseView> cases, List<DefectView> defects, String uatEvidenceHash,
                          String approvalStatus, String approvedByRole, String approvedAt) {}

    public record AuditRow(String action, String entityType, String actorRole, String occurredAt) {}

    public record AuditPage(List<AuditRow> content, int page, int size) {}

    public record RecordRow(String kind, String externalId, Map<String, String> source,
                            Map<String, Object> target, String sourceFile, int sourceRowNumber,
                            String originRunId) {}

    public record RecordPage(List<RecordRow> content, int page, int size) {}
}

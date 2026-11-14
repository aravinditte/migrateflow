package com.migrateflow.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
import com.migrateflow.common.CanonicalHash;
import com.migrateflow.identity.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Publishes immutable mapping revisions and the default rule-set revision. */
@Service
public class MappingService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper canonicalMapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public MappingService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static final Set<String> CUSTOMER_FIELDS =
            Set.of("external_customer_id", "full_name", "email", "joined_on");
    private static final Set<String> ORDER_FIELDS =
            Set.of("external_order_id", "external_customer_id", "order_date", "amount_yen", "status");
    private static final Set<String> MANDATORY_CUSTOMER_FIELDS =
            Set.of("external_customer_id", "full_name");
    private static final Set<String> MANDATORY_ORDER_FIELDS =
            Set.of("external_order_id", "external_customer_id", "order_date", "amount_yen", "status");

    public record PublishedRevision(UUID revisionId, int revisionNo, String definitionHash, String canonicalJson) {}

    @Transactional
    public PublishedRevision publish(AppUser actor, UUID projectId, MappingDefinition definition) {
        validateDefinition(definition);
        jdbc.queryForObject("SELECT id FROM control.projects WHERE id = ? FOR UPDATE", UUID.class, projectId);
        String canonical;
        try {
            canonical = canonicalMapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String hash = CanonicalHash.sha256Hex(canonical);
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_no),0) FROM control.mapping_revisions WHERE project_id = ?",
                Integer.class, projectId);
        int revisionNo = (max == null ? 0 : max) + 1;
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO control.mapping_revisions (id, project_id, revision_no, definition_json, definition_hash, published_by) "
                        + "VALUES (?,?,?,?::jsonb,?,?)",
                id, projectId, revisionNo, canonical, hash, actor.id());
        audit.record(projectId, actor.id(), "MAPPING_PUBLISHED", "mapping_revision", id,
                Map.of("revisionNo", revisionNo, "definitionHash", hash));
        return new PublishedRevision(id, revisionNo, hash, canonical);
    }

    /** The rule set is a fixed v1 policy in this implementation; publishing is explicit and hashed. */
    @Transactional
    public UUID ensureDefaultRuleSet(UUID projectId) {
        jdbc.queryForObject("SELECT id FROM control.projects WHERE id = ? FOR UPDATE", UUID.class, projectId);
        List<UUID> existing = jdbc.query(
                "SELECT id FROM control.rule_set_revisions WHERE project_id = ? ORDER BY revision_no LIMIT 1",
                (rs, n) -> rs.getObject("id", UUID.class), projectId);
        if (!existing.isEmpty()) return existing.get(0);
        String ruleJson = "{\"version\":\"v1\",\"errorCodes\":[\"REQUIRED_VALUE\",\"DATE_FORMAT\",\"EMAIL_FORMAT\","
                + "\"NEGATIVE_AMOUNT\",\"INTEGER_FORMAT\",\"INVALID_STATUS\",\"DUPLICATE_BUSINESS_KEY\","
                + "\"UNKNOWN_CUSTOMER\",\"FIELD_TOO_LONG\"],\"conservation\":\"raw=accepted+excluded\","
                + "\"duplicates\":\"all-occurrences-invalid\",\"dependencies\":\"orders-need-accepted-customer\"}";
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO control.rule_set_revisions (id, project_id, revision_no, rule_json, rule_hash) VALUES (?,?,1,?::jsonb,?)",
                id, projectId, ruleJson, CanonicalHash.sha256Hex(ruleJson));
        return id;
    }

    public MappingDefinition loadDefinition(UUID revisionId) {
        String json = jdbc.query("SELECT definition_json::text FROM control.mapping_revisions WHERE id = ?",
                (rs, n) -> rs.getString(1), revisionId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Mapping revision"));
        try {
            return canonicalMapper.readValue(json, MappingDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored mapping definition is not readable", e);
        }
    }

    private void validateDefinition(MappingDefinition def) {
        if (def == null) throw ApiException.unprocessable("INVALID_MAPPING", "A mapping definition is required");
        validateDataset("CUSTOMERS", def.customers(), CUSTOMER_FIELDS);
        validateDataset("ORDERS", def.orders(), ORDER_FIELDS);
    }

    private void validateDataset(String dataset, List<MappingDefinition.FieldMapping> fields, Set<String> expected) {
        if (fields == null) throw ApiException.unprocessable("INVALID_MAPPING", dataset + " mappings are required");
        Set<String> mandatory = "CUSTOMERS".equals(dataset) ? MANDATORY_CUSTOMER_FIELDS : MANDATORY_ORDER_FIELDS;
        Set<String> seen = new java.util.HashSet<>();
        for (MappingDefinition.FieldMapping f : fields) {
            if (!expected.contains(f.targetField())) {
                throw ApiException.unprocessable("INVALID_MAPPING", "Unknown target field " + f.targetField() + " for " + dataset);
            }
            if (!seen.add(f.targetField())) {
                throw ApiException.unprocessable("INVALID_MAPPING", "Target field mapped twice: " + f.targetField());
            }
            if (mandatory.contains(f.targetField()) && !f.required()) {
                throw ApiException.unprocessable("INVALID_MAPPING",
                        "Target field " + f.targetField() + " is NOT NULL in the target schema and cannot be optional (required=false)");
            }
            if (f.sourceColumn() == null || f.sourceColumn().isBlank()) {
                throw ApiException.unprocessable("INVALID_MAPPING", "Source column is required for " + f.targetField());
            }
            if (f.steps() == null || f.steps().isEmpty()) {
                throw ApiException.unprocessable("INVALID_MAPPING", "At least one transformation step is required for " + f.targetField());
            }
            for (MappingDefinition.TransformStep step : f.steps()) {
                String error = TransformEngine.validateStep(step);
                if (error != null) throw ApiException.unprocessable("INVALID_MAPPING", error);
            }
        }
        if (!seen.equals(expected)) {
            throw ApiException.unprocessable("INVALID_MAPPING",
                    dataset + " mappings must cover exactly " + expected + " but covered " + seen);
        }
    }
}

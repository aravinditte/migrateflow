package com.migrateflow.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** Append-only audit events (spec 8.1). Never stores passwords or full source rows. */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(UUID projectId, UUID actorId, String action, String entityType, UUID entityId, Map<String, ?> metadata) {
        String json = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                json = mapper.writeValueAsString(metadata);
            } catch (JsonProcessingException e) {
                json = "{\"note\":\"metadata not serializable\"}";
            }
        }
        jdbc.update("INSERT INTO control.audit_events (id, project_id, actor_id, action, entity_type, entity_id, metadata_json) "
                        + "VALUES (?,?,?,?,?,?,?::jsonb)",
                UUID.randomUUID(), projectId, actorId, action, entityType, entityId, json);
    }
}

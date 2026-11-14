package com.migrateflow.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Loads the accepted (valid) rows of a manifest's validation snapshot in
 * stable source order. Target surrogate IDs are deterministic
 * (spec section 19 choice 4): derived from manifest + dataset + business
 * key, so a chunk replay targets exactly the same record and the
 * applied-rows ledger can prove identity instead of guessing.
 */
@Component
public class AcceptedRows {
    private final JdbcTemplate jdbc;

    public AcceptedRows(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CustomerTarget(UUID targetId, UUID sourceRowId, int sourceRowNumber, UUID sourceFileId,
                                 String externalCustomerId, String fullName, String email,
                                 LocalDate joinedOn, String normalizedHash) {}
    public record OrderTarget(UUID targetId, UUID sourceRowId, int sourceRowNumber, UUID sourceFileId,
                              String externalOrderId, UUID customerTargetId, String externalCustomerId,
                              LocalDate orderDate, long amountYen, String status, String normalizedHash) {}

    public static UUID deterministicTargetId(UUID manifestId, String kind, String businessKey) {
        return UUID.nameUUIDFromBytes(("migrateflow:" + manifestId + ":" + kind + ":" + businessKey)
                .getBytes(StandardCharsets.UTF_8));
    }

    public List<CustomerTarget> customers(UUID manifestId, UUID snapshotId) {
        return jdbc.query(
                "SELECT vr.input_row_id, ir.data_row_number, ir.file_id, vr.external_customer_id, vr.full_name, "
                        + "vr.email, vr.joined_on, vr.normalized_hash "
                        + "FROM stage.customer_validated_rows vr "
                        + "JOIN stage.customer_input_rows ir ON ir.id = vr.input_row_id "
                        + "WHERE vr.snapshot_id = ? AND vr.is_valid = TRUE ORDER BY ir.data_row_number",
                (rs, n) -> new CustomerTarget(
                        deterministicTargetId(manifestId, "CUSTOMERS", rs.getString("external_customer_id")),
                        rs.getObject("input_row_id", UUID.class),
                        rs.getInt("data_row_number"),
                        rs.getObject("file_id", UUID.class),
                        rs.getString("external_customer_id"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getDate("joined_on") == null ? null : rs.getDate("joined_on").toLocalDate(),
                        rs.getString("normalized_hash")),
                snapshotId);
    }

    public List<OrderTarget> orders(UUID manifestId, UUID snapshotId) {
        return jdbc.query(
                "SELECT vr.input_row_id, ir.data_row_number, ir.file_id, vr.external_order_id, "
                        + "vr.external_customer_id, vr.order_date, vr.amount_yen, vr.status, vr.normalized_hash "
                        + "FROM stage.order_validated_rows vr "
                        + "JOIN stage.order_input_rows ir ON ir.id = vr.input_row_id "
                        + "WHERE vr.snapshot_id = ? AND vr.is_valid = TRUE ORDER BY ir.data_row_number",
                (rs, n) -> new OrderTarget(
                        deterministicTargetId(manifestId, "ORDERS", rs.getString("external_order_id")),
                        rs.getObject("input_row_id", UUID.class),
                        rs.getInt("data_row_number"),
                        rs.getObject("file_id", UUID.class),
                        rs.getString("external_order_id"),
                        deterministicTargetId(manifestId, "CUSTOMERS", rs.getString("external_customer_id")),
                        rs.getString("external_customer_id"),
                        rs.getDate("order_date").toLocalDate(),
                        rs.getLong("amount_yen"),
                        rs.getString("status"),
                        rs.getString("normalized_hash")),
                snapshotId);
    }

    public int acceptedCount(UUID snapshotId) {
        Integer c = jdbc.queryForObject("SELECT (SELECT count(*) FROM stage.customer_validated_rows WHERE snapshot_id = ? AND is_valid = TRUE) "
                + "+ (SELECT count(*) FROM stage.order_validated_rows WHERE snapshot_id = ? AND is_valid = TRUE)", Integer.class, snapshotId, snapshotId);
        return c == null ? 0 : c;
    }
}

package com.migrateflow.validation;

import com.migrateflow.common.CanonicalHash;
import com.migrateflow.mapping.MappingDefinition;
import com.migrateflow.mapping.TransformEngine;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic validation (spec 4.4). Pure and DB-free: the same inputs
 * always produce the same snapshots, issues and hashes.
 *
 * Order of operations: per-field transforms and checks, then duplicate
 * business-key detection (ALL occurrences invalid), then the dependent-order
 * pass against the final accepted-customer set.
 */
public final class ValidationEngine {
    private ValidationEngine() {}

    public record RawRow(UUID inputRowId, int dataRowNumber, Map<String, String> values) {}
    public record Issue(String kind, UUID sourceRowId, String code, String field, String message) {}

    public record ValidatedCustomer(UUID inputRowId, String externalCustomerId, String fullName,
                                    String email, LocalDate joinedOn, boolean valid, String normalizedHash) {}
    public record ValidatedOrder(UUID inputRowId, String externalOrderId, String externalCustomerId,
                                 LocalDate orderDate, Long amountYen, String status,
                                 boolean valid, String normalizedHash) {}
    public record Outcome(List<ValidatedCustomer> customers, List<ValidatedOrder> orders, List<Issue> issues) {}

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public static Outcome validate(MappingDefinition mapping, List<RawRow> customerRows, List<RawRow> orderRows) {
        List<Issue> issues = new ArrayList<>();
        List<ValidatedCustomer> customers = validateCustomers(mapping.customers(), customerRows, issues);
        List<ValidatedOrder> orders = validateOrders(mapping.orders(), orderRows, issues);

        // Duplicate business keys: every occurrence is invalid (ADR-004).
        markDuplicates(customers.stream().map(c -> new DupRef(c.inputRowId(), c.externalCustomerId(), c.valid())).toList(),
                "CUSTOMERS", "external_customer_id", issues,
                id -> customers.replaceAll(c -> c.inputRowId().equals(id) ? withValid(c, false) : c));
        markDuplicates(orders.stream().map(o -> new DupRef(o.inputRowId(), o.externalOrderId(), o.valid())).toList(),
                "ORDERS", "external_order_id", issues,
                id -> orders.replaceAll(o -> o.inputRowId().equals(id) ? withValid(o, false) : o));

        // Dependent-order pass: an order must resolve to exactly one accepted customer.
        Set<String> acceptedCustomers = new HashSet<>();
        for (ValidatedCustomer c : customers) if (c.valid()) acceptedCustomers.add(c.externalCustomerId());
        orders.replaceAll(o -> {
            if (o.valid() && (o.externalCustomerId() == null || !acceptedCustomers.contains(o.externalCustomerId()))) {
                issues.add(new Issue("ORDERS", o.inputRowId(), "UNKNOWN_CUSTOMER", "external_customer_id",
                        "Order references customer " + o.externalCustomerId()
                                + " which is not an accepted customer"));
                return withValid(o, false);
            }
            return o;
        });
        return new Outcome(customers, orders, issues);
    }

    private record DupRef(UUID rowId, String key, boolean validBefore) {}

    private static void markDuplicates(List<DupRef> refs, String kind, String field, List<Issue> issues,
                                       java.util.function.Consumer<UUID> invalidate) {
        Map<String, List<UUID>> byKey = new LinkedHashMap<>();
        for (DupRef r : refs) {
            if (r.key() != null) byKey.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r.rowId());
        }
        for (Map.Entry<String, List<UUID>> e : byKey.entrySet()) {
            if (e.getValue().size() >= 2) {
                for (UUID rowId : e.getValue()) {
                    issues.add(new Issue(kind, rowId, "DUPLICATE_BUSINESS_KEY", field,
                            "Business key " + e.getKey() + " appears in " + e.getValue().size() + " source rows"));
                    invalidate.accept(rowId);
                }
            }
        }
    }

    private static ValidatedCustomer withValid(ValidatedCustomer c, boolean valid) {
        return new ValidatedCustomer(c.inputRowId(), c.externalCustomerId(), c.fullName(), c.email(),
                c.joinedOn(), valid, c.normalizedHash());
    }

    private static ValidatedOrder withValid(ValidatedOrder o, boolean valid) {
        return new ValidatedOrder(o.inputRowId(), o.externalOrderId(), o.externalCustomerId(), o.orderDate(),
                o.amountYen(), o.status(), valid, o.normalizedHash());
    }

    private static List<ValidatedCustomer> validateCustomers(List<MappingDefinition.FieldMapping> fields,
                                                             List<RawRow> rows, List<Issue> issues) {
        List<ValidatedCustomer> out = new ArrayList<>();
        for (RawRow row : rows) {
            FieldOutcome fo = applyFields("CUSTOMERS", fields, row, issues);
            Map<String, String> v = fo.values();
            boolean valid = fo.ok();
            String id = v.get("external_customer_id");
            String name = v.get("full_name");
            String email = v.get("email");
            LocalDate joined = v.get("joined_on") == null ? null : LocalDate.parse(v.get("joined_on"));
            if (valid) {
                if (id != null && id.length() > 32) { issues.add(err("CUSTOMERS", row, "FIELD_TOO_LONG", "external_customer_id", "external_customer_id exceeds 32 characters")); valid = false; }
                if (name != null && name.length() > 200) { issues.add(err("CUSTOMERS", row, "FIELD_TOO_LONG", "full_name", "full_name exceeds 200 characters")); valid = false; }
                if (email != null && email.length() > 254) { issues.add(err("CUSTOMERS", row, "FIELD_TOO_LONG", "email", "email exceeds 254 characters")); valid = false; }
                if (email != null && !EMAIL.matcher(email).matches()) { issues.add(err("CUSTOMERS", row, "EMAIL_FORMAT", "email", "Email is not a valid address")); valid = false; }
            }
            String hash = CanonicalHash.hashFields(customerHashFields(id, name, email, joined));
            out.add(new ValidatedCustomer(row.inputRowId(), id, name, email, joined, valid, hash));
        }
        return out;
    }

    private static List<ValidatedOrder> validateOrders(List<MappingDefinition.FieldMapping> fields,
                                                       List<RawRow> rows, List<Issue> issues) {
        List<ValidatedOrder> out = new ArrayList<>();
        for (RawRow row : rows) {
            FieldOutcome fo = applyFields("ORDERS", fields, row, issues);
            Map<String, String> v = fo.values();
            boolean valid = fo.ok();
            String id = v.get("external_order_id");
            String custId = v.get("external_customer_id");
            LocalDate date = v.get("order_date") == null ? null : LocalDate.parse(v.get("order_date"));
            Long amount = v.get("amount_yen") == null ? null : Long.valueOf(v.get("amount_yen"));
            String status = v.get("status");
            if (valid) {
                if (id != null && id.length() > 32) { issues.add(err("ORDERS", row, "FIELD_TOO_LONG", "external_order_id", "external_order_id exceeds 32 characters")); valid = false; }
                if (amount != null && amount < 0) { issues.add(err("ORDERS", row, "NEGATIVE_AMOUNT", "amount_yen", "Amount must be >= 0")); valid = false; }
                if (status != null && !TransformEngine.ORDER_STATUSES.contains(status)) {
                    issues.add(err("ORDERS", row, "INVALID_STATUS", "status", "Status is not a known target status")); valid = false;
                }
            }
            String hash = CanonicalHash.hashFields(orderHashFields(id, custId, date, amount, status));
            out.add(new ValidatedOrder(row.inputRowId(), id, custId, date, amount, status, valid, hash));
        }
        return out;
    }

    private record FieldOutcome(Map<String, String> values, boolean ok) {}

    /**
     * Applies every field mapping for one row. Successfully transformed
     * values are kept even when a sibling field fails (so duplicate-key
     * detection still sees the normalized business key); ok=false marks the
     * row invalid and failed fields are stored as null typed values.
     */
    private static FieldOutcome applyFields(String kind, List<MappingDefinition.FieldMapping> fields,
                                            RawRow row, List<Issue> issues) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean ok = true;
        for (MappingDefinition.FieldMapping f : fields) {
            String raw = row.values().get(f.sourceColumn());
            TransformEngine.Result r = TransformEngine.apply(f.steps(), raw);
            if (!r.ok()) {
                issues.add(err(kind, row, r.errorCode(), f.targetField(),
                        "Value for " + f.sourceColumn() + " failed transformation (" + r.errorCode() + ")"));
                values.put(f.targetField(), null);
                ok = false;
                continue;
            }
            String value = r.value();
            if (f.required() && (value == null || value.isBlank())) {
                issues.add(err(kind, row, "REQUIRED_VALUE", f.targetField(),
                        "Required field " + f.targetField() + " is missing"));
                values.put(f.targetField(), null);
                ok = false;
                continue;
            }
            values.put(f.targetField(), value);
        }
        return new FieldOutcome(values, ok);
    }

    private static Issue err(String kind, RawRow row, String code, String field, String message) {
        return new Issue(kind, row.inputRowId(), code, field, message);
    }

    public static Map<String, ?> customerHashFields(String id, String name, String email, LocalDate joined) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("external_customer_id", id);
        m.put("full_name", name);
        m.put("email", email);
        m.put("joined_on", joined == null ? null : joined.toString());
        return m;
    }

    public static Map<String, ?> orderHashFields(String id, String custId, LocalDate date, Long amount, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("external_order_id", id);
        m.put("external_customer_id", custId);
        m.put("order_date", date == null ? null : date.toString());
        m.put("amount_yen", amount);
        m.put("status", status);
        return m;
    }
}

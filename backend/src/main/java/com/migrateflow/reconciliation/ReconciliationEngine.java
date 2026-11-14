package com.migrateflow.reconciliation;

import java.util.*;

/**
 * Pure reconciliation comparison (spec 8.4). PASS requires every mandatory
 * control: conservation inputs are supplied by the caller; here we check
 * business-key set equality, per-record normalized-hash equality, order
 * parent resolution within scope, and monetary totals. Hash mismatches are
 * expanded to field-level differences by the caller when it has the fields.
 */
public final class ReconciliationEngine {
    private ReconciliationEngine() {}

    public record CustomerRec(String externalId, String normalizedHash) {}
    public record OrderRec(String externalId, String customerExternalId, long amountYen, String normalizedHash) {}

    public record Result(boolean pass, List<String> differences,
                         int expectedCustomers, int writtenCustomers,
                         int expectedOrders, int writtenOrders,
                         long expectedOrderAmount, long writtenOrderAmount) {}

    public static Result compare(List<CustomerRec> expectedCustomers, List<CustomerRec> actualCustomers,
                                 List<OrderRec> expectedOrders, List<OrderRec> actualOrders) {
        List<String> diffs = new ArrayList<>();
        Map<String, String> expC = indexCustomers(expectedCustomers, diffs, "expected");
        Map<String, String> actC = indexCustomers(actualCustomers, diffs, "actual");
        if (!expC.keySet().equals(actC.keySet())) {
            Set<String> missing = new TreeSet<>(expC.keySet()); missing.removeAll(actC.keySet());
            Set<String> extra = new TreeSet<>(actC.keySet()); extra.removeAll(expC.keySet());
            if (!missing.isEmpty()) diffs.add("customers missing from target: " + abbreviate(missing));
            if (!extra.isEmpty()) diffs.add("customers in target but not accepted: " + abbreviate(extra));
        }
        for (String id : expC.keySet()) {
            String actualHash = actC.get(id);
            if (actualHash != null && !actualHash.equals(expC.get(id))) {
                diffs.add("customer " + id + " field values differ from the accepted snapshot");
            }
        }
        Map<String, OrderRec> expO = indexOrders(expectedOrders, diffs, "expected");
        Map<String, OrderRec> actO = indexOrders(actualOrders, diffs, "actual");
        if (!expO.keySet().equals(actO.keySet())) {
            Set<String> missing = new TreeSet<>(expO.keySet()); missing.removeAll(actO.keySet());
            Set<String> extra = new TreeSet<>(actO.keySet()); extra.removeAll(expO.keySet());
            if (!missing.isEmpty()) diffs.add("orders missing from target: " + abbreviate(missing));
            if (!extra.isEmpty()) diffs.add("orders in target but not accepted: " + abbreviate(extra));
        }
        long expectedAmount = 0, writtenAmount = 0;
        for (OrderRec o : expectedOrders) expectedAmount = Math.addExact(expectedAmount, o.amountYen());
        for (OrderRec o : actualOrders) writtenAmount = Math.addExact(writtenAmount, o.amountYen());
        for (Map.Entry<String, OrderRec> e : expO.entrySet()) {
            OrderRec actual = actO.get(e.getKey());
            if (actual == null) continue;
            if (!actual.normalizedHash().equals(e.getValue().normalizedHash())) {
                diffs.add("order " + e.getKey() + " field values differ from the accepted snapshot");
            }
            if (actual.customerExternalId() == null || !expC.containsKey(actual.customerExternalId())) {
                diffs.add("order " + e.getKey() + " does not resolve to an accepted customer in scope");
            }
        }
        if (expectedAmount != writtenAmount) {
            diffs.add("order amount total differs: accepted=" + expectedAmount + " written=" + writtenAmount);
        }
        return new Result(diffs.isEmpty(), List.copyOf(diffs),
                expectedCustomers.size(), actualCustomers.size(),
                expectedOrders.size(), actualOrders.size(), expectedAmount, writtenAmount);
    }

    private static Map<String, String> indexCustomers(List<CustomerRec> rows, List<String> diffs, String side) {
        Map<String, String> map = new LinkedHashMap<>();
        for (CustomerRec r : rows) {
            if (map.put(r.externalId(), r.normalizedHash()) != null) {
                diffs.add("duplicate customer business key in " + side + " set: " + r.externalId());
            }
        }
        return map;
    }

    private static Map<String, OrderRec> indexOrders(List<OrderRec> rows, List<String> diffs, String side) {
        Map<String, OrderRec> map = new LinkedHashMap<>();
        for (OrderRec r : rows) {
            if (map.put(r.externalId(), r) != null) {
                diffs.add("duplicate order business key in " + side + " set: " + r.externalId());
            }
        }
        return map;
    }

    private static String abbreviate(Set<String> ids) {
        List<String> list = new ArrayList<>(ids);
        if (list.size() <= 5) return list.toString();
        return list.subList(0, 5) + " ... (" + list.size() + " total)";
    }
}

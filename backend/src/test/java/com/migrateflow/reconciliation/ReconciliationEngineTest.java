package com.migrateflow.reconciliation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationEngineTest {

    private ReconciliationEngine.CustomerRec cust(String id, String hash) {
        return new ReconciliationEngine.CustomerRec(id, hash);
    }

    private ReconciliationEngine.OrderRec order(String id, String cust, long amount, String hash) {
        return new ReconciliationEngine.OrderRec(id, cust, amount, hash);
    }

    @Test
    void identicalSetsPass() {
        var result = ReconciliationEngine.compare(
                List.of(cust("C1", "h1"), cust("C2", "h2")),
                List.of(cust("C2", "h2"), cust("C1", "h1")),
                List.of(order("O1", "C1", 100, "x1")),
                List.of(order("O1", "C1", 100, "x1")));
        assertTrue(result.pass());
        assertEquals(100, result.expectedOrderAmount());
    }

    @Test
    void missingRowFails() {
        var result = ReconciliationEngine.compare(
                List.of(cust("C1", "h1")), List.of(),
                List.of(), List.of());
        assertFalse(result.pass());
        assertTrue(result.differences().toString().contains("missing"));
    }

    @Test
    void changedFieldFails() {
        var result = ReconciliationEngine.compare(
                List.of(cust("C1", "h1")), List.of(cust("C1", "hX")),
                List.of(), List.of());
        assertFalse(result.pass());
    }

    @Test
    void amountMismatchFails() {
        var result = ReconciliationEngine.compare(
                List.of(cust("C1", "h1")), List.of(cust("C1", "h1")),
                List.of(order("O1", "C1", 100, "x1")),
                List.of(order("O1", "C1", 101, "x2")));
        assertFalse(result.pass());
    }

    @Test
    void orphanOrderFails() {
        var result = ReconciliationEngine.compare(
                List.of(cust("C1", "h1")), List.of(cust("C1", "h1")),
                List.of(order("O1", "C1", 100, "x1")),
                List.of(order("O1", "C9", 100, "x1")));
        assertFalse(result.pass());
        assertTrue(result.differences().toString().contains("does not resolve"));
    }
}

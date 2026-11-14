package com.migrateflow.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrateflow.intake.CsvParser;
import com.migrateflow.mapping.DefaultMappings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 13.1/13.2 (T04-T06, T10 inputs): the checked-in deterministic fixtures
 * must validate to exactly 995 accepted customers, 1,990 accepted orders and
 * a 21,998,650 yen accepted total, with the planted rows invalid.
 */
class FixtureValidationTest {

    private List<ValidationEngine.RawRow> load(String file) throws Exception {
        Path path = Path.of("..", "fixtures", file);
        assertTrue(Files.exists(path), "fixture missing: " + path.toAbsolutePath());
        CsvParser.ParsedCsv parsed = CsvParser.parse(Files.readAllBytes(path), 10_000, 50);
        return parsed.rows().stream()
                .map(r -> new ValidationEngine.RawRow(UUID.nameUUIDFromBytes(
                        (file + ":" + r.dataRowNumber()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        r.dataRowNumber(), r.values()))
                .toList();
    }

    @Test
    void fixturesProduceTheDocumentedCounts() throws Exception {
        var customers = load("customers_1000.csv");
        var orders = load("orders_2000.csv");
        assertEquals(1000, customers.size());
        assertEquals(2000, orders.size());

        ValidationEngine.Outcome outcome = ValidationEngine.validate(DefaultMappings.create(), customers, orders);

        long validCustomers = outcome.customers().stream().filter(ValidationEngine.ValidatedCustomer::valid).count();
        long validOrders = outcome.orders().stream().filter(ValidationEngine.ValidatedOrder::valid).count();
        assertEquals(995, validCustomers);
        assertEquals(1990, validOrders);

        long total = outcome.orders().stream().filter(ValidationEngine.ValidatedOrder::valid)
                .mapToLong(ValidationEngine.ValidatedOrder::amountYen).sum();
        assertEquals(21_998_650L, total);

        // The planted invalid rows (1-based) must be exactly the documented sets.
        Set<Integer> invalidCustomers = outcome.customers().stream().filter(c -> !c.valid())
                .map(c -> rowNumberOf(customers, c.inputRowId())).collect(Collectors.toSet());
        Set<Integer> invalidOrders = outcome.orders().stream().filter(o -> !o.valid())
                .map(o -> rowNumberOf(orders, o.inputRowId())).collect(Collectors.toSet());
        assertEquals(Set.of(7, 13, 20, 21, 34), invalidCustomers);
        assertEquals(Set.of(9, 10, 11, 12, 13, 14, 15, 16, 17, 18), invalidOrders);

        // Conservation invariant (spec 4.4): raw = accepted + excluded.
        assertEquals(1000, validCustomers + invalidCustomers.size());
        assertEquals(2000, validOrders + invalidOrders.size());

        // Cross-check against the checked-in expected_results.json.
        JsonNode expected = new ObjectMapper().readTree(Path.of("..", "fixtures", "expected_results.json").toFile());
        assertEquals(995, expected.get("customers").get("acceptedRows").asInt());
        assertEquals(1990, expected.get("orders").get("acceptedRows").asInt());
        assertEquals(21_998_650L, expected.get("orders").get("acceptedAmountTotalYen").asLong());
    }

    @Test
    void duplicateKeysInvalidateEveryOccurrence() throws Exception {
        var customers = load("customers_1000.csv");
        var orders = load("orders_2000.csv");
        ValidationEngine.Outcome outcome = ValidationEngine.validate(DefaultMappings.create(), customers, orders);
        Set<String> dupRows = outcome.issues().stream()
                .filter(i -> "DUPLICATE_BUSINESS_KEY".equals(i.code()))
                .map(i -> i.kind()).collect(Collectors.toSet());
        assertEquals(Set.of("CUSTOMERS", "ORDERS"), dupRows);
        long dupCount = outcome.issues().stream().filter(i -> "DUPLICATE_BUSINESS_KEY".equals(i.code())).count();
        assertEquals(4, dupCount); // C0020 x2 + O0013 x2
    }

    @Test
    void dependentOrdersOfExcludedCustomersAreInvalid() throws Exception {
        var customers = load("customers_1000.csv");
        var orders = load("orders_2000.csv");
        ValidationEngine.Outcome outcome = ValidationEngine.validate(DefaultMappings.create(), customers, orders);
        Set<Integer> unknownCustomerRows = outcome.issues().stream()
                .filter(i -> "UNKNOWN_CUSTOMER".equals(i.code()))
                .map(i -> rowNumberOf(orders, i.sourceRowId())).collect(Collectors.toSet());
        // Order 12 references C9999; 15-18 reference the excluded customers.
        assertEquals(Set.of(12, 15, 16, 17, 18), unknownCustomerRows);
    }

    private int rowNumberOf(List<ValidationEngine.RawRow> rows, UUID id) {
        return rows.stream().filter(r -> r.inputRowId().equals(id)).findFirst().orElseThrow().dataRowNumber();
    }
}

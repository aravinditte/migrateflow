# ADR-001: Stack - Spring Boot 3.5 / Spring Batch 5.2 on Java 21

Status: accepted.

Spring Boot 3.5.7 (Spring Batch 5.2.x, Spring Framework 6.2.x) on Java 21 is
the current maintained generation for a November 2026 build. Spring Batch
provides chunk transactions, a persistent job repository and same-instance
restart semantics that the rehearsal/cutover design maps onto directly.

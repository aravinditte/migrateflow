# ADR-002: JDBC (JdbcClient) instead of JPA/Hibernate

Status: accepted.

The domain is set-based staging, bulk chunk writes and ledger checks.
Explicit SQL keeps chunk-transaction boundaries, FOR UPDATE slot locking and
run-scoped queries obvious and auditable. Flyway owns the schema; there is
no ORM auto-DDL anywhere.

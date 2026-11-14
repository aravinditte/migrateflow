# ADR-006: Real PostgreSQL for tests, no Testcontainers

Status: accepted.

Integration tests run against a real PostgreSQL supplied by the developer
(local install) or CI (service container). This exercises identical SQL and
Flyway migrations without requiring a Docker daemon inside the test JVM,
which keeps the suite runnable in restricted environments. The Docker
Compose packaging is verified separately on Docker hosts.

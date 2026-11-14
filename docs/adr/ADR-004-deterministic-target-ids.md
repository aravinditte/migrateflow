# ADR-004: Deterministic target UUIDs

Status: accepted.

Target ids are name-based UUIDs over (manifest id, kind, business key).
Restart correctness then reduces to a ledger lookup: a row either exists
with the expected hash (skip) or with a different hash (fail closed).
Random ids would make post-crash state ambiguous.

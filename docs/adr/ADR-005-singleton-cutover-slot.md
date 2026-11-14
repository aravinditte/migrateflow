# ADR-005: Singleton target cutover slot

Status: accepted.

There is exactly one final target, so exactly one cutover may own it. A
seeded singleton row, locked with SELECT ... FOR UPDATE during preflight,
serialises competing cutovers across projects without advisory-lock
bookkeeping. The reservation, approval checks and target-empty check happen
in the same transaction.

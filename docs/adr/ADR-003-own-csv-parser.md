# ADR-003: Hand-written strict CSV parser

Status: accepted.

Validation evidence must cite the physical line where each record starts,
and malformed input must fail the whole upload deterministically. The
parser implements RFC 4180 quoting with strict UTF-8 decoding, BOM
tolerance, duplicate/blank header rejection and physical-line tracking -
behavior that general CSV libraries do not guarantee uniformly.

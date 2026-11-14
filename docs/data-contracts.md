# Data contracts

## Source files (exact headers, exact order)

`customers.csv`:
`Cust_No,Customer_Name,Email,Join_Date`

`orders.csv`:
`Order_No,Cust_No,Order_Dt,Amount_JPY,State`

Upload rules: UTF-8 (BOM allowed), RFC 4180 quoting, LF or CRLF, one header
row, configured size/row limits. Duplicate or blank headers, NUL bytes,
malformed quotes and inconsistent field counts fail the whole upload with
`CSV_MALFORMED` — a bad file is never partially accepted. Note: all source business
records are synthetic.

## Field contracts

| Dataset | Field | Target type | Rule |
|---|---|---|---|
| customers | Cust_No | text, ≤32 | required, unique business key after trim/uppercase |
| customers | Customer_Name | text, ≤200 | required after trim |
| customers | Email | text, ≤254 | optional; format checked if present |
| customers | Join_Date | date | optional; strict `yyyy/MM/dd` |
| orders | Order_No | text, ≤32 | required, unique business key after trim/uppercase |
| orders | Cust_No | FK | required; must reference an accepted customer |
| orders | Order_Dt | date | required; strict `yyyy/MM/dd` |
| orders | State | enum | required; `N→NEW, P→PAID, S→SHIPPED, C→CANCELLED` |
| orders | Amount_JPY | bigint | required, `>= 0` |

## Mapping DSL

A mapping revision is an ordered list of per-field operations from a strict
allow-list: `TRIM`, `UPPERCASE`, `LOWERCASE`, `NULL_IF_BLANK`,
`PARSE_DATE(format)`, `PARSE_LONG`, `ENUM_MAP(mapping)`, `DEFAULT_VALUE(v)`.
Anything else is rejected at publish time; there is no scripting anywhere in
the mapping path. Revisions are immutable and hash-bound into the manifest.

## Validation outcomes

Each row ends exactly one of: **valid**, or invalid with one or more coded
issues (`REQUIRED_FIELD`, `FIELD_TOO_LONG`, `DATE_FORMAT`, `EMAIL_FORMAT`,
`NUMBER_FORMAT`, `NEGATIVE_AMOUNT`, `UNKNOWN_STATUS`, `UNKNOWN_CUSTOMER`,
`DUPLICATE_BUSINESS_KEY`, `FILE_STRUCTURE`...). Duplicate business keys
invalidate **all** occurrences. Orders whose customer is not accepted are
`UNKNOWN_CUSTOMER` even when their own fields are clean.

Conservation always holds: `raw rows = accepted rows + excluded rows`, where
"excluded" requires an explicit reviewer decision per invalid row. An
invalid row can never be marked valid by approval — only excluded, with a
reason.

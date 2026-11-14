#!/usr/bin/env python3
"""Deterministic MigrateFlow fixture generator (spec section 13.1).

Produces customers_1000.csv (1,000 rows) and orders_2000.csv (2,000 rows)
with planted invalid rows, plus expected_results.json.

Planted defects (1-based data-row numbers):
  customers: 7 blank name; 13 invalid date; 20+21 duplicate normalized
             business key C0020; 34 invalid email.                -> 5 excluded
  orders:    9 negative amount; 10 invalid date; 11 unknown status;
             12 unknown customer C9999; 13+14 duplicate O0013;
             15->C0007, 16->C0013, 17->C0020, 18->C0034 (excluded
             customers).                                         -> 10 excluded
Expected: 995 customers / 1,990 orders accepted, order total 21,998,650 JPY.
No randomness is used; the output is a pure function of this file.
"""
import csv
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))


def gen_customers():
    rows = []
    for i in range(1, 1001):
        rows.append({
            "Cust_No": f"C{i:04d}",
            "Customer_Name": f"Customer {i}",
            "Email": f"customer{i}@example.test",
            "Join_Date": "2025/01/01",
        })
    rows[6]["Customer_Name"] = ""                 # row 7: blank name
    rows[12]["Join_Date"] = "2025/13/01"          # row 13: invalid date
    rows[20]["Cust_No"] = " c0020 "               # row 21: dup of C0020 after TRIM+UPPERCASE
    rows[33]["Email"] = "not-an-email"            # row 34: invalid email
    return rows


def gen_orders():
    rows = []
    for i in range(1, 2001):
        cust = 100 + (i - 1) % 900                # cycles C0100..C0999
        rows.append({
            "Order_No": f"O{i:04d}",
            "Cust_No": f"C{cust:04d}",
            "Order_Dt": "2025/02/01",
            "Amount_JPY": str(1000 + 10 * i),
            "State": "N",
        })
    rows[8]["Amount_JPY"] = "-500"                # row 9: negative amount
    rows[9]["Order_Dt"] = "2025/02/30"            # row 10: invalid date
    rows[10]["State"] = "X"                       # row 11: unknown status
    rows[11]["Cust_No"] = "C9999"                 # row 12: unknown customer
    rows[13]["Order_No"] = " o0013 "              # row 14: dup of O0013 after TRIM+UPPERCASE
    rows[14]["Cust_No"] = "C0007"                 # row 15: excluded customer
    rows[15]["Cust_No"] = "C0013"                 # row 16: excluded customer
    rows[16]["Cust_No"] = "C0020"                 # row 17: excluded customer
    rows[17]["Cust_No"] = "C0034"                 # row 18: excluded customer
    return rows


def main():
    customers = gen_customers()
    orders = gen_orders()
    with open(os.path.join(HERE, "customers_1000.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["Cust_No", "Customer_Name", "Email", "Join_Date"])
        w.writeheader()
        w.writerows(customers)
    with open(os.path.join(HERE, "orders_2000.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["Order_No", "Cust_No", "Order_Dt", "Amount_JPY", "State"])
        w.writeheader()
        w.writerows(orders)

    excluded_customers = [7, 13, 20, 21, 34]
    excluded_orders = [9, 10, 11, 12, 13, 14, 15, 16, 17, 18]
    accepted_total = sum(1000 + 10 * i for i in range(1, 2001) if i not in excluded_orders)
    assert accepted_total == 21_998_650, accepted_total
    expected = {
        "customers": {"rawRows": 1000, "excludedRows": 5, "acceptedRows": 995,
                      "excludedRowNumbers": excluded_customers},
        "orders": {"rawRows": 2000, "excludedRows": 10, "acceptedRows": 1990,
                   "excludedRowNumbers": excluded_orders,
                   "acceptedAmountTotalYen": accepted_total},
    }
    with open(os.path.join(HERE, "expected_results.json"), "w", encoding="utf-8") as f:
        json.dump(expected, f, indent=2)
        f.write("\n")
    print("customers=1000 orders=2000 accepted=995/1990 total=", accepted_total)


if __name__ == "__main__":
    main()

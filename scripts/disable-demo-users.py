#!/usr/bin/env python3
"""
Disable seeded demo accounts in PostgreSQL (MF-006).
Sets enabled = FALSE for all demo accounts ending in @example.test.
This prevents further logins while preserving all audit event foreign keys,
reviewer attributions, and historical migration provenance.

Usage:  python3 scripts/disable-demo-users.py [postgres-url]
        (or MIGRATEFLOW_DATABASE_URL / defaults to local docker port)
"""
import os
import sys

def main():
    try:
        import psycopg2
    except ImportError:
        print("Note: psycopg2 not installed in this environment. Printing SQL for manual execution:")
        print("UPDATE control.users SET enabled = FALSE WHERE email LIKE '%@example.test';")
        return

    db_url = sys.argv[1] if len(sys.argv) > 1 else os.environ.get(
        "MIGRATEFLOW_DATABASE_URL", "postgresql://migrateflow:migrateflow-dev-pw@localhost:5432/migrateflow")
    print(f"Connecting to database to deactivate demo accounts: {db_url.split('@')[-1]}")
    conn = psycopg2.connect(db_url)
    try:
        with conn.cursor() as cur:
            cur.execute("UPDATE control.users SET enabled = FALSE WHERE email LIKE '%@example.test';")
            affected = cur.rowcount
            conn.commit()
            print(f"Deactivated {affected} demo user account(s). Foreign keys and audit logs remain intact.")
    finally:
        conn.close()

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Disable seeded demo accounts in PostgreSQL (MF-006 / MF-A11).
Sets enabled = FALSE for all demo accounts ending in @example.test.
This prevents further logins while preserving all audit event foreign keys,
reviewer attributions, and historical migration provenance.

Usage:  python3 scripts/disable-demo-users.py [postgres-url]
        (or MIGRATEFLOW_DATABASE_URL environment variable)
"""
import os
import sys

def main():
    try:
        import psycopg2
    except ImportError:
        print("ERROR: psycopg2 is required to deactivate demo accounts. Install with: pip install psycopg2-binary", file=sys.stderr)
        sys.exit(1)

    db_url = sys.argv[1] if len(sys.argv) > 1 else os.environ.get(
        "MIGRATEFLOW_DATABASE_URL", "postgresql://migrateflow:migrateflow-dev-pw@localhost:5432/migrateflow")

    # Sanitize logged URL (hide password)
    masked_target = db_url.split("@")[-1] if "@" in db_url else "database"
    print(f"Connecting to {masked_target} to deactivate demo accounts...")

    try:
        conn = psycopg2.connect(db_url)
    except Exception as e:
        print(f"ERROR: Database connection failed: {e}", file=sys.stderr)
        sys.exit(1)

    try:
        with conn:
            with conn.cursor() as cur:
                cur.execute("UPDATE control.users SET enabled = FALSE WHERE email LIKE '%@example.test';")
                affected = cur.rowcount
                cur.execute("SELECT count(*) FROM control.users WHERE email LIKE '%@example.test' AND enabled = TRUE;")
                remaining = cur.fetchone()[0]
                if remaining > 0:
                    print(f"ERROR: Verification failed: {remaining} demo account(s) remain enabled!", file=sys.stderr)
                    sys.exit(1)
                print(f"Successfully deactivated {affected} demo user account(s). 0 enabled demo accounts remain.")
    except Exception as e:
        print(f"ERROR: Failed during deactivation transaction: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        conn.close()

if __name__ == "__main__":
    main()

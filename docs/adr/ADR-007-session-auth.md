# ADR-007: Session authentication with SPA CSRF cookie

Status: accepted.

The workbench is an internal tool operated by named individuals. Server
sessions with BCrypt password hashes and a readable XSRF-TOKEN cookie
(mirrored into the X-XSRF-TOKEN header) are simpler to audit than bearer
tokens for this scope. OIDC/SSO is future work, recorded in limitations.

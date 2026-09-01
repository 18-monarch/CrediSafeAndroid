# Security v2.7

- Passwords are normalized, length-checked, and hashed with bcrypt cost 12.
- Existing hashes are verified; unknown emails do not create accounts during login.
- Legacy passwordless beta accounts can be secured only by the same device UUID.
- JWTs are HS256 with issuer, audience, subject, and 12-hour expiry.
- `JWT_SECRET` must be at least 32 characters and must exist only on the server.
- CORS is deny-by-default for browsers; Android requests are unaffected.
- Auth and road requests are rate-limited.
- Helmet security headers are enabled and Express identity is hidden.
- WebSocket bearer tokens must be sent in the Authorization header.
- Live dashboard data is authenticated.
- Trip ownership is checked for event, telemetry, completion, and detail operations.
- XP and reward ledgers have per-trip uniqueness constraints.
- Telemetry uploads are protected against oversized compressed payloads, decompression expansion, malformed hashes, sample-count mismatch, and non-monotonic timestamps.

Remaining production work: verified-email flow, password reset, refresh-token rotation/revocation, structured audit logs, secret rotation runbooks, external penetration testing, and an abuse-monitoring dashboard.

# CrediSafe Sync Conflict Resolution

## Core Principles
1. **Client trip UUID is the Source of Truth for identity.**
2. **Server results are authoritative once validated.**
3. **Local data is never silently overwritten without a server confirmation.**

## Conflict Scenarios

### 1. Duplicate Upload (Idempotency)
- **Scenario**: Client uploads a trip summary that already exists on the server.
- **Resolution**: Server detects the `tripId` exists and returns `200 OK` with the existing server data. No duplicate trip or ledger entries are created.

### 2. Client Retry after Partial Sync
- **Scenario**: Client successfully uploaded events but failed during telemetry sync.
- **Resolution**: Server uses `ON CONFLICT` with `(trip_id, timestamp_ms)` to allow re-uploading the same events without duplication. Telemetry assets are replaced if the checksum differs.

### 3. State Conflict (Authoritative wins)
- **Scenario**: Client attempts to update a trip that the server has already marked as `VALIDATED`.
- **Resolution**: Server rejects the update and returns the authoritative XP/Points. Client must update its local state to `SERVER_CONFIRMED` and use the server's values.

### 4. Concurrent Sessions
- **Scenario**: Same user attempts to start two trips simultaneously on different devices.
- **Resolution**: Backend allows multiple active trips in the `liveStore`, but completion logic ensures unique constraints on any business-critical metrics.

# CrediSafe API Contract v1

## Base URL
The base URL is configurable via environment variables.

## Error Structure
All errors follow this structure:
```json
{
  "error": {
    "code": "string",
    "message": "string",
    "requestId": "uuid"
  }
}
```

## Endpoints

### 1. Health Check
`GET /health`
- **Auth**: None
- **Response**: `200 OK`
```json
{
  "status": "ok",
  "database": "ok",
  "version": "1.0.0"
}
```

### 2. Create Session
`POST /v1/auth/session`
- **Auth**: None (or optional Client Secret)
- **Request Body**:
```json
{
  "deviceId": "uuid",
  "clientSecret": "string (optional)"
}
```
- **Response**: `200 OK`
```json
{
  "accessToken": "jwt",
  "expiresIn": 3600,
  "userId": "uuid"
}
```

### 3. Create Trip
`POST /v1/trips`
- **Auth**: Required (Bearer Token)
- **Request Body**: `TripUploadRequest`
- **Response**: `201 Created` / `200 OK` (if already exists)
```json
{
  "success": true,
  "message": "Trip created"
}
```

### 4. Upload Events
`POST /v1/trips/{tripId}/events`
- **Auth**: Required (Bearer Token)
- **Path Param**: `tripId`
- **Request Body**: `List<EventUpload>`
- **Response**: `200 OK`
```json
{
  "success": true,
  "message": "Events uploaded"
}
```

### 5. Upload Telemetry
`POST /v1/trips/{tripId}/telemetry`
- **Auth**: Required (Bearer Token)
- **Path Param**: `tripId`
- **Request Body**: `TelemetryUploadRequest`
- **Response**: `200 OK`
```json
{
  "success": true,
  "message": "Telemetry uploaded and verified"
}
```

### 6. Complete Trip
`POST /v1/trips/{tripId}/complete`
- **Auth**: Required (Bearer Token)
- **Path Param**: `tripId`
- **Response**: `200 OK`
```json
{
  "success": true,
  "authoritativeXp": 184,
  "authoritativePoints": 92,
  "engineVersion": "2.1"
}
```

### 7. Get Trip Details
`GET /v1/trips/{tripId}`
- **Auth**: Required (Bearer Token)
- **Response**: `200 OK` (`TripUploadRequest`)

### 8. List Trips
`GET /v1/trips`
- **Auth**: Required (Bearer Token)
- **Response**: `200 OK` (`List<TripUploadRequest>`)

### 9. Sync Acknowledgement
`POST /v1/sync/ack`
- **Auth**: Required (Bearer Token)
- **Request Body**: `SyncAckRequest`
- **Response**: `200 OK`

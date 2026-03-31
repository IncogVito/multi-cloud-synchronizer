# Phase 1: Mikroserwis iCloud (Python/FastAPI)

## Cel
Mikroserwis opakowujący bibliotekę icloudpy w REST API. Jedyne zadanie: komunikacja z Apple iCloud.

## Wymagania wstępne
Przed implementacją wczytaj dokumentację icloudpy (README repozytorium, dostępne metody API). Zaimplementuj endpointy zgodnie z faktycznym API biblioteki. Oznacz komentarzem `# OPEN` jeśli coś nie jest jasne.

## Endpointy API

### Autentykacja
```
POST /auth/login
Body: { "apple_id": "...", "password": "..." }
Response: { "session_id": "...", "requires_2fa": true/false }

POST /auth/2fa
Body: { "session_id": "...", "code": "..." }
Response: { "session_id": "...", "authenticated": true }

GET /auth/sessions
Response: [{ "session_id": "...", "apple_id": "...", "active": true }]

DELETE /auth/sessions/{session_id}
```

### Obsługa wielu kont
- Serwis musi obsługiwać jednoczesne sesje dla wielu kont iCloud.
- Sesje trzymane w pamięci (dictionary). `# OPEN: Rozważyć persystencję sesji (Redis? plik?)` [Na razie z plikiem]
- Każde kolejne wywołanie API wymaga session_id w headerze (X-Session-ID).

### Zdjęcia
```
GET /photos
Headers: X-Session-ID
Query params: ?limit=100&offset=0
Response: { "photos": [{ "id", "filename", "size", "created_date", "dimensions", "asset_token" }], "total": N }

GET /photos/{photo_id}
Headers: X-Session-ID
Response: binary stream (original photo)

GET /photos/{photo_id}/thumbnail
Headers: X-Session-ID
Query params: ?size=256
Response: binary stream (thumbnail if available from iCloud)

DELETE /photos/{photo_id}
Headers: X-Session-ID
Response: { "deleted": true }
```

### Urządzenia (informacyjne)
```
GET /devices
Headers: X-Session-ID
Response: [{ "id", "name", "model", "battery_level", "location" }]
```

### Status
```
GET /health
Response: { "status": "ok", "version": "1.0.0" }

GET /storage
Headers: X-Session-ID
Response: { "used_bytes": N, "total_bytes": N }
```

## Architektura wewnętrzna
```
app/
├── main.py              # FastAPI app, CORS, startup
├── config.py            # Settings via pydantic BaseSettings
├── routers/
│   ├── auth.py          # Login, 2FA, sessions
│   ├── photos.py        # Photo operations
│   ├── devices.py       # Device listing
│   └── health.py        # Health & storage
├── services/
│   ├── icloud_session_manager.py  # Manages icloudpy sessions per account
│   └── photo_service.py          # Photo retrieval logic
├── models/
│   ├── auth.py          # Pydantic models for auth
│   ├── photo.py         # Pydantic models for photos
│   └── device.py        # Pydantic models for devices
└── exceptions.py        # Custom exception handlers
```

## Obsługa błędów
- 401 – brak sesji lub sesja wygasła.
- 404 – zdjęcie nie znalezione.
- 503 – iCloud niedostępny.
- Globalny exception handler tłumaczący icloudpy exceptions na HTTP responses.

## Bezpieczeństwo
- Serwis dostępny tylko w wewnętrznej sieci Docker (nie wystawiony na zewnątrz).
- `# OPEN: Dodatkowe zabezpieczenie komunikacji backend↔mikroserwis (shared secret/API key?)`

## Testy
- Twórz pliki testowe dla każdego modułu (tests/test_auth.py, tests/test_photos.py, etc.).
- Testy puste (stub) z nazwami metod opisującymi co testują.
- Komentarz: `# TODO: Implement test – to be completed by secondary model`

## Definicja ukończenia
- Wszystkie endpointy zdefiniowane i zaimplementowane.
- Swagger UI dostępny pod /docs.
- Logowanie do iCloud i pobieranie listy zdjęć działa end-to-end (testowane manualnie z prawdziwym kontem).

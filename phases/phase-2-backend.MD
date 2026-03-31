# Phase 2: Backend (Java/Micronaut)

## Cel
Centralny serwis aplikacji: zarządza bazą danych, wirtualną strukturą folderów, thumbnailami, detekcją dysku, integracją z mikroserwisem iCloud, oraz bezpieczeństwem.

## Baza danych – SQLite

### Lokalizacja
- Plik SQLite na dysku zewnętrznym: `/mnt/external-drive/cloud-sync.db`
- Ścieżka konfigurowana przez zmienną środowiskową `EXTERNAL_DRIVE_PATH`

### Schema (Micronaut Data JDBC + Flyway migracje)
```sql
-- Użytkownicy/konta iCloud
CREATE TABLE icloud_accounts (
    id TEXT PRIMARY KEY,
    apple_id TEXT NOT NULL UNIQUE,
    display_name TEXT,
    session_id TEXT, -- aktywna sesja w mikroserwisie
    last_sync_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Zdjęcia zsynchronizowane na dysk
CREATE TABLE photos (
    id TEXT PRIMARY KEY,
    icloud_photo_id TEXT,
    account_id TEXT REFERENCES icloud_accounts(id),
    filename TEXT NOT NULL,
    file_path TEXT NOT NULL, -- ścieżka na dysku zewnętrznym
    thumbnail_path TEXT,     -- ścieżka do thumbnaila
    file_size BIGINT,
    width INT,
    height INT,
    created_date TIMESTAMP,
    imported_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    checksum TEXT,           -- do weryfikacji integralności
    synced_to_disk BOOLEAN DEFAULT FALSE,
    exists_on_icloud BOOLEAN DEFAULT TRUE,
    exists_on_iphone BOOLEAN, -- OPEN: jak to ustalać?
    media_type TEXT DEFAULT 'PHOTO' -- PHOTO, VIDEO, LIVE_PHOTO
);

-- Wirtualna struktura folderów
CREATE TABLE virtual_folders (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id TEXT REFERENCES virtual_folders(id),
    folder_type TEXT, -- 'YEAR', 'MONTH', 'CUSTOM', 'ROOT'
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Przypisanie zdjęć do folderów (many-to-many)
CREATE TABLE photo_folder_assignments (
    photo_id TEXT REFERENCES photos(id),
    folder_id TEXT REFERENCES virtual_folders(id),
    PRIMARY KEY (photo_id, folder_id)
);

-- Status urządzeń
CREATE TABLE device_status (
    id TEXT PRIMARY KEY,
    device_type TEXT NOT NULL, -- 'EXTERNAL_DRIVE', 'IPHONE', 'ICLOUD'
    is_connected BOOLEAN DEFAULT FALSE,
    last_checked_at TIMESTAMP,
    details TEXT -- JSON z dodatkowymi info
);
```

## API Endpointy

### Status & Health
```
GET /api/health
GET /api/status/devices       → status dysku, iPhone'a, iCloud
POST /api/status/check-drive  → trigger sprawdzenia dysku
POST /api/status/check-iphone → trigger sprawdzenia iPhone'a
```

### Konta iCloud (proxy do mikroserwisu)
```
POST /api/accounts/login      → proxy do mikroserwisu + zapis do DB
POST /api/accounts/2fa        → proxy do mikroserwisu
GET  /api/accounts            → lista kont
DELETE /api/accounts/{id}     → wylogowanie + usunięcie
GET /api/accounts/{id}/status → czy sesja aktywna
```

### Zdjęcia
```
GET /api/photos                        → lista z filtrami i paginacją
    ?account_id=&year=&month=&folder_id=&page=&size=&synced=
GET /api/photos/{id}                   → szczegóły zdjęcia
GET /api/photos/{id}/thumbnail         → thumbnail (binary)
GET /api/photos/{id}/full              → pełne zdjęcie (binary, z dysku)
POST /api/photos/sync                  → trigger synchronizacji z iCloud na dysk
DELETE /api/photos/icloud              → usuń z iCloud (batch)
    Body: { "photo_ids": [...] }
    Warunek: zdjęcie MUSI istnieć na dysku (synced_to_disk=true)
DELETE /api/photos/iphone              → usuń z iPhone'a (batch)
    Body: { "photo_ids": [...] }
    Warunek: zdjęcie MUSI istnieć na dysku (synced_to_disk=true)
```

### Wirtualne foldery
```
GET    /api/folders                    → drzewo folderów
POST   /api/folders                    → utwórz folder
PUT    /api/folders/{id}               → edytuj folder
DELETE /api/folders/{id}               → usuń folder
GET    /api/folders/{id}/photos        → zdjęcia w folderze
POST   /api/folders/{id}/photos        → przypisz zdjęcia do folderu
       Body: { "photo_ids": [...] }
```

### Auto-organizacja
```
POST /api/folders/auto-organize        → automatycznie utwórz foldery wg lat/miesięcy
     Query: ?granularity=YEAR|MONTH
```

## Detekcja dysku – AI Agent z Function Calling

### Koncepcja
Przy wejściu użytkownika na stronę, backend uruchamia AI agenta, który próbuje wykryć i zamontować dysk zewnętrzny.

### Implementacja
- Stwórz `DiskDetectionAgent` implementujący wzorzec AI function calling.
- Agent ma dostęp do "narzędzi" (functions):
  - `list_block_devices()` – wywołuje `lsblk -J` w kontenerze
  - `check_mount_point(path)` – sprawdza czy ścieżka jest zamontowana
  - `mount_device(device, mount_point)` – montuje urządzenie
  - `check_disk_space(path)` – sprawdza wolne miejsce
  - `read_disk_label(device)` – czyta etykietę dysku
- Agent dostaje prompt systemowy opisujący jego zadanie i iteracyjnie wywołuje narzędzia.
- `// OPEN: Wybór providera LLM (OpenAI, local LLM, Ollama). Przygotuj interfejs abstrakcji.`
- `// OPEN: Fallback jeśli LLM niedostępny – prosta heurystyka (szukaj /dev/sd* nie zamontowanego).`

### Endpoint
```
POST /api/agent/detect-drive
Response: Server-Sent Events (SSE) stream z krokami agenta
  { "step": 1, "action": "list_block_devices", "result": "..." }
  { "step": 2, "action": "mount_device", "result": "..." }
  { "step": "final", "success": true, "message": "Drive mounted at /mnt/external-drive" }
```

## Thumbnailing
- Przy synchronizacji zdjęcia z iCloud na dysk, generuj thumbnail.
- `// OPEN: Biblioteka do thumbnailów – opcje: Thumbnailator (Java), ImageIO, lub wywołanie zewnętrznego narzędzia (imagemagick w kontenerze).`
- Domyślny rozmiar: 300x300px, JPEG, quality 80%.
- Thumbnails zapisywane w `/mnt/external-drive/thumbnails/{photo_id}.jpg`.
- Thumbnail generowany asynchronicznie po pobraniu zdjęcia.

## Rozpoznawanie iPhone'a
- Backend triggeruje skrypty shell z katalogu `/scripts/` zamontowanego jako volume.
- Endpoint `POST /api/status/check-iphone` wywołuje `detect-iphone.sh`.
- Skrypt zwraca JSON z informacją o podłączonym urządzeniu.
- `// OPEN: Implementacja skryptu – użytkownik dostarczy. Backend parsuje stdout jako JSON.`

## Security (Micronaut Security)
- Proste zabezpieczenie: Basic Auth lub Session-based auth.
- Jeden skonfigurowany użytkownik (credentials w zmiennych środowiskowych: APP_USERNAME, APP_PASSWORD).
- Wszystkie endpointy `/api/**` wymagają autentykacji.
- `/api/health` – publiczny.
- `// OPEN: Rozbudowa na JWT jeśli potrzebne w przyszłości.`

## Architektura pakietów
```
com.cloudsync/
├── Application.java
├── config/
│   ├── DataSourceConfig.java
│   ├── SecurityConfig.java
│   └── ICloudServiceConfig.java
├── controller/
│   ├── HealthController.java
│   ├── AccountController.java
│   ├── PhotoController.java
│   ├── FolderController.java
│   ├── StatusController.java
│   └── AgentController.java
├── service/
│   ├── AccountService.java
│   ├── PhotoService.java
│   ├── SyncService.java
│   ├── FolderService.java
│   ├── ThumbnailService.java
│   ├── DeviceStatusService.java
│   └── DiskDetectionAgent.java
├── client/
│   └── ICloudServiceClient.java  # HTTP client do mikroserwisu
├── repository/
│   ├── PhotoRepository.java
│   ├── AccountRepository.java
│   ├── FolderRepository.java
│   └── DeviceStatusRepository.java
├── model/
│   ├── entity/        # JPA/JDBC entities
│   ├── dto/           # Request/Response DTOs
│   └── enums/
├── agent/
│   ├── AgentExecutor.java
│   ├── AgentTool.java          # interface
│   ├── tools/
│   │   ├── ListBlockDevicesTool.java
│   │   ├── CheckMountPointTool.java
│   │   ├── MountDeviceTool.java
│   │   ├── CheckDiskSpaceTool.java
│   │   └── ReadDiskLabelTool.java
│   └── LlmProvider.java       # interface / OPEN
├── exception/
│   ├── DriveNotAvailableException.java
│   ├── PhotoNotSyncedException.java
│   └── GlobalExceptionHandler.java
└── util/
    └── ShellExecutor.java      # uruchamianie skryptów shell
```

## Testy
- Twórz pliki testowe dla każdego serwisu, kontrolera, repozytorium.
- Testy puste (stub) z opisowymi nazwami metod.
- Komentarz: `// TODO: Implement test – to be completed by secondary model`
- Struktura testów odzwierciedla strukturę kodu źródłowego.

## Definicja ukończenia
- Backend startuje i łączy się z SQLite na zamontowanym dysku.
- Proxy do mikroserwisu działa (logowanie przez backend).
- Synchronizacja pobiera zdjęcia z iCloud i zapisuje na dysk.
- Thumbnails generowane poprawnie.
- Endpoint detekcji dysku zwraca SSE stream z krokami agenta.
- Security blokuje nieautoryzowane requesty.

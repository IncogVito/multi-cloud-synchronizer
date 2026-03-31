Cloud Synchronizer – Główna Instrukcja dla Modelu (Master Prompt)
🎯 CEL PROJEKTU
Tworzysz aplikację webową Cloud Synchronizer, która synchronizuje zdjęcia i pliki z Apple iCloud z dyskiem zewnętrznym podłączonym do komputera. Aplikacja składa się z trzech komponentów uruchamianych w Dockerze:

Backend – Java (najnowsza LTS) + Micronaut + GraalVM + Gradle
Mikroserwis iCloud – Python + FastAPI + biblioteka icloudpy
Frontend – Angular (najnowszy) + Vite
🧭 ZASADY OGÓLNE
Architektura
Wszystkie komponenty działają jako kontenery Docker orkiestrowane przez docker-compose.
Komunikacja między backendem a mikroserwisem odbywa się po wewnętrznej sieci dockerowej (REST API).
Frontend komunikuje się wyłącznie z backendem (backend pełni rolę API Gateway).
Jakość kodu
Testy jednostkowe: Dla każdego tworzonego pliku źródłowego twórz odpowiadający plik testowy. Testy mogą być puste (stub) – zostaną uzupełnione przez tańsze modele. Zawsze twórz strukturę pliku z prawidłowymi importami, nazwą klasy/describe i pustymi metodami testowymi z komentarzem // TODO: Implement test.
Stosuj clean code, SOLID, sensowne nazewnictwo.
Każdy komponent ma własny README.md z instrukcją uruchomienia.
MVP Focus
Skupiaj się na MVP (Minimum Viable Product). Nie overengineeruj.
Tam gdzie szczegóły implementacyjne są otwarte, zaznacz to komentarzem: // OPEN: [opis decyzji do podjęcia] lub # OPEN: [opis].
Jeśli coś wymaga podjęcia decyzji architektonicznej, zaproponuj domyślne rozwiązanie i oznacz jako OPEN.
Konwencje
Język kodu i komentarzy: angielski.
Nazwy commitów: conventional commits (feat:, fix:, chore:, docs:).
Struktura projektu: monorepo z katalogami /backend, /icloud-service, /frontend, /docker.
📁 STRUKTURA MONOREPO
Code
cloud-synchronizer/
├── backend/                  # Java Micronaut + GraalVM
│   ├── src/main/java/
│   ├── src/test/java/
│   ├── build.gradle
│   └── Dockerfile
├── icloud-service/           # Python FastAPI + icloudpy
│   ├── app/
│   ├── tests/
│   ├── requirements.txt
│   └── Dockerfile
├── frontend/                 # Angular + Vite
│   ├── src/
│   ├── angular.json
│   └── Dockerfile
├── docker/
│   └── docker-compose.yml
├── scripts/                  # Shell scripts (iPhone detection etc.)
└── README.md
🔄 PROCES TWORZENIA
Realizuj projekt w podanej kolejności faz. Każda faza ma swój spec (poniżej). Po zakończeniu fazy, przejdź do następnej.

Faza 0: Scaffolding & Docker
Przygotuj strukturę projektu, pliki konfiguracyjne, docker-compose, Dockerfile'e, podstawowe zależności.

Faza 1: Mikroserwis iCloud (Python/FastAPI)
Najpierw – bo backend od niego zależy. API do logowania i pobierania danych z iCloud przez icloudpy.

Faza 2: Backend (Java/Micronaut)
Core logiki: SQLite na dysku zewnętrznym, zarządzanie zdjęciami, wirtualna struktura folderów, thumbnailing, detekcja dysku (AI agent z function calling), security, integracja z mikroserwisem.

Faza 3: Frontend (Angular/Vite)
UI: logowanie do iCloud, status urządzeń, przeglądanie zdjęć z thumbnailami, grupowanie wg dat, zaznaczanie i usuwanie.

Faza 4: Integracja & Polish
End-to-end testy manualne, poprawki, dokumentacja.

⚠️ WAŻNE DECYZJE OTWARTE (OPEN)
Poniższe kwestie są celowo zostawione otwarte. Przy implementacji zaproponuj domyślne rozwiązanie, oznacz komentarzem // OPEN i idź dalej:

Dokładny format przechowywania thumbnailów (np. rozmiar, format JPEG/WebP, lokalizacja na dysku)
Szczegóły algorytmu synchronizacji (conflict resolution, delta sync vs full sync)
Dokładna implementacja AI agenta do detekcji dysku (model, provider, format function calls)
Strategia cache'owania thumbnailów
Szczegóły UX (dokładne layouty, animacje, responsywność)
Mechanizm rozpoznawania iPhone'a – przygotowane są sloty na skrypty .sh, ich treść będzie dostarczona osobno
📋 SPEC – FAZA 0: Scaffolding & Docker
Cel
Przygotowanie kompletnej struktury monorepo, konfiguracji buildów i środowiska Docker.

Wymagania
Struktura katalogów
Utwórz pełną strukturę katalogów jak w sekcji "Struktura Monorepo" w głównej instrukcji.
Backend scaffolding
Micronaut projekt z Gradle (Kotlin DSL build.gradle.kts).
Java w najnowszej LTS (21+). Target: GraalVM native image.
Zależności startowe: micronaut-http-server, micronaut-serde-jackson, micronaut-data-jdbc, sqlite-jdbc, micronaut-security.
Konfiguracja application.yml z profilami: dev, docker.
Placeholder dla datasource SQLite (ścieżka konfigurowana zmienną środowiskową, domyślnie: /mnt/external-drive/cloud-sync.db).
Mikroserwis iCloud scaffolding
Python 3.12+, FastAPI, uvicorn.
requirements.txt z: fastapi, uvicorn, icloudpy, python-dotenv, pydantic.
Podstawowa struktura: app/main.py, app/routers/, app/services/, app/models/.
Health check endpoint: GET /health.
Frontend scaffolding
Angular (najnowszy, 17+) z Vite jako bundler.
Przygotuj plik globalnych styli (styles/global.scss) z podstawowymi zmiennymi CSS (kolory, fonty, spacing, border-radius) – styl delikatny, UX-owy, minimalistyczny.
Przygotuj podstawowe klasy utility (.card, .btn, .btn-primary, .status-badge, .container).
Routing z lazy loading.
Struktura: core/, shared/, features/.
Docker
Dockerfile dla każdego komponentu (multi-stage builds).
Backend: build z Gradle → GraalVM native image → scratch/distroless runtime.
Mikroserwis: slim Python image.
Frontend: build z Node → nginx do serwowania.
docker-compose.yml:
Usługi: backend, icloud-service, frontend.
Sieć wewnętrzna: cloud-sync-net.
Volumes: mapowanie dysku zewnętrznego do /mnt/external-drive (w backendzie).
Mapowanie katalogu ze skryptami shell do kontenera backend.
Porty: frontend :80, backend :8080, icloud-service :8000 (tylko wewnętrznie).
Health checks dla każdego serwisu.
Skrypty
Katalog scripts/ z placeholder plikami:
detect-iphone.sh – # OPEN: Script to detect connected iPhone via USB
mount-drive.sh – # OPEN: Script to mount external drive
check-drive.sh – # OPEN: Script to check if external drive is available
Dokumentacja
Root README.md z opisem projektu, wymaganiami, instrukcją docker-compose up.
Każdy komponent: swój README.md.
Definicja ukończenia
docker-compose up startuje wszystkie 3 kontenery bez błędów.
Health checki przechodzą.
Frontend wyświetla pustą stronę z tytułem "Cloud Synchronizer".
Backend odpowiada na GET /health.
Mikroserwis odpowiada na GET /health.
📋 SPEC – FAZA 1: Mikroserwis iCloud (Python/FastAPI)
Cel
Mikroserwis opakowujący bibliotekę icloudpy w REST API. Jedyne zadanie: komunikacja z Apple iCloud.

Wymagania wstępne
Przed implementacją wczytaj dokumentację icloudpy (README repozytorium, dostępne metody API). Zaimplementuj endpointy zgodnie z faktycznym API biblioteki. Oznacz komentarzem # OPEN jeśli coś nie jest jasne.
Endpointy API
Autentykacja
Code
POST /auth/login
Body: { "apple_id": "...", "password": "..." }
Response: { "session_id": "...", "requires_2fa": true/false }

POST /auth/2fa
Body: { "session_id": "...", "code": "..." }
Response: { "session_id": "...", "authenticated": true }

GET /auth/sessions
Response: [{ "session_id": "...", "apple_id": "...", "active": true }]

DELETE /auth/sessions/{session_id}
Obsługa wielu kont
Serwis musi obsługiwać jednoczesne sesje dla wielu kont iCloud.
Sesje trzymane w pamięci (dictionary). # OPEN: Rozważyć persystencję sesji (Redis? plik?).
Każde kolejne wywołanie API wymaga session_id w headerze (X-Session-ID).
Zdjęcia
Code
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
Urządzenia (informacyjne)
Code
GET /devices
Headers: X-Session-ID
Response: [{ "id", "name", "model", "battery_level", "location" }]
Status
Code
GET /health
Response: { "status": "ok", "version": "1.0.0" }

GET /storage
Headers: X-Session-ID
Response: { "used_bytes": N, "total_bytes": N }
Architektura wewnętrzna
Code
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
Obsługa błędów
401 – brak sesji lub sesja wygasła.
404 – zdjęcie nie znalezione.
503 – iCloud niedostępny.
Globalny exception handler tłumaczący icloudpy exceptions na HTTP responses.
Bezpieczeństwo
Serwis dostępny tylko w wewnętrznej sieci Docker (nie wystawiony na zewnątrz).
# OPEN: Dodatkowe zabezpieczenie komunikacji backend↔mikroserwis (shared secret/API key?).
Testy
Twórz pliki testowe dla każdego modułu (tests/test_auth.py, tests/test_photos.py, etc.).
Testy puste (stub) z nazwami metod opisującymi co testują.
Komentarz: # TODO: Implement test – to be completed by secondary model.
Definicja ukończenia
Wszystkie endpointy zdefiniowane i zaimplementowane.
Swagger UI dostępny pod /docs.
Logowanie do iCloud i pobieranie listy zdjęć działa end-to-end (testowane manualnie z prawdziwym kontem).
📋 SPEC – FAZA 2: Backend (Java/Micronaut)
Cel
Centralny serwis aplikacji: zarządza bazą danych, wirtualną strukturą folderów, thumbnailami, detekcją dysku, integracją z mikroserwisem iCloud, oraz bezpieczeństwem.

Baza danych – SQLite
Lokalizacja
Plik SQLite na dysku zewnętrznym: /mnt/external-drive/cloud-sync.db.
Ścieżka konfigurowana przez zmienną środowiskową EXTERNAL_DRIVE_PATH.
Schema (Micronaut Data JDBC + Flyway migracje)
SQL
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
API Endpointy
Status & Health
Code
GET /api/health
GET /api/status/devices       → status dysku, iPhone'a, iCloud
POST /api/status/check-drive  → trigger sprawdzenia dysku
POST /api/status/check-iphone → trigger sprawdzenia iPhone'a
Konta iCloud (proxy do mikroserwisu)
Code
POST /api/accounts/login      → proxy do mikroserwisu + zapis do DB
POST /api/accounts/2fa        → proxy do mikroserwisu
GET  /api/accounts            → lista kont
DELETE /api/accounts/{id}     → wylogowanie + usunięcie
GET /api/accounts/{id}/status → czy sesja aktywna
Zdjęcia
Code
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
Wirtualne foldery
Code
GET    /api/folders                    → drzewo folderów
POST   /api/folders                    → utwórz folder
PUT    /api/folders/{id}               → edytuj folder
DELETE /api/folders/{id}               → usuń folder
GET    /api/folders/{id}/photos        → zdjęcia w folderze
POST   /api/folders/{id}/photos        → przypisz zdjęcia do folderu
       Body: { "photo_ids": [...] }
Auto-organizacja
Code
POST /api/folders/auto-organize        → automatycznie utwórz foldery wg lat/miesięcy
     Query: ?granularity=YEAR|MONTH
Detekcja dysku – AI Agent z Function Calling
Koncepcja
Przy wejściu użytkownika na stronę, backend uruchamia AI agenta, który próbuje wykryć i zamontować dysk zewnętrzny.

Implementacja
Stwórz DiskDetectionAgent implementujący wzorzec AI function calling.
Agent ma dostęp do "narzędzi" (functions):
list_block_devices() – wywołuje lsblk -J w kontenerze
check_mount_point(path) – sprawdza czy ścieżka jest zamontowana
mount_device(device, mount_point) – montuje urządzenie
check_disk_space(path) – sprawdza wolne miejsce
read_disk_label(device) – czyta etykietę dysku
Agent dostaje prompt systemowy opisujący jego zadanie i iteracyjnie wywołuje narzędzia.
// OPEN: Wybór providera LLM (OpenAI, local LLM, Ollama). Przygotuj interfejs abstrakcji.
// OPEN: Fallback jeśli LLM niedostępny – prosta heurystyka (szukaj /dev/sd* nie zamontowanego).
Endpoint
Code
POST /api/agent/detect-drive
Response: Server-Sent Events (SSE) stream z krokami agenta
  { "step": 1, "action": "list_block_devices", "result": "..." }
  { "step": 2, "action": "mount_device", "result": "..." }
  { "step": "final", "success": true, "message": "Drive mounted at /mnt/external-drive" }
Thumbnailing
Przy synchronizacji zdjęcia z iCloud na dysk, generuj thumbnail.
// OPEN: Biblioteka do thumbnailów – opcje: Thumbnailator (Java), ImageIO, lub wywołanie zewnętrznego narzędzia (imagemagick w kontenerze).
Domyślny rozmiar: 300x300px, JPEG, quality 80%.
Thumbnails zapisywane w /mnt/external-drive/thumbnails/{photo_id}.jpg.
Thumbnail generowany asynchronicznie po pobraniu zdjęcia.
Rozpoznawanie iPhone'a
Backend triggeruje skrypty shell z katalogu /scripts/ zamontowanego jako volume.
Endpoint POST /api/status/check-iphone wywołuje detect-iphone.sh.
Skrypt zwraca JSON z informacją o podłączonym urządzeniu.
// OPEN: Implementacja skryptu – użytkownik dostarczy. Backend parsuje stdout jako JSON.
Security (Micronaut Security)
Proste zabezpieczenie: Basic Auth lub Session-based auth.
Jeden skonfigurowany użytkownik (credentials w zmiennych środowiskowych: APP_USERNAME, APP_PASSWORD).
Wszystkie endpointy /api/** wymagają autentykacji.
/api/health – publiczny.
// OPEN: Rozbudowa na JWT jeśli potrzebne w przyszłości.
Architektura pakietów
Code
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
Testy
Twórz pliki testowe dla każdego serwisu, kontrolera, repozytorium.
Testy puste (stub) z opisowymi nazwami metod.
Komentarz: // TODO: Implement test – to be completed by secondary model.
Struktura testów odzwierciedla strukturę kodu źródłowego.
Definicja ukończenia
Backend startuje i łączy się z SQLite na zamontowanym dysku.
Proxy do mikroserwisu działa (logowanie przez backend).
Synchronizacja pobiera zdjęcia z iCloud i zapisuje na dysk.
Thumbnails generowane poprawnie.
Endpoint detekcji dysku zwraca SSE stream z krokami agenta.
Security blokuje nieautoryzowane requesty.
📋 SPEC – FAZA 3: Frontend (Angular/Vite)
Cel
Interfejs użytkownika: delikatny, minimalistyczny design UX. Logowanie, statusy urządzeń, przeglądanie zdjęć, operacje batch.

Design System
Filozofia
Czysty, jasny, minimalistyczny design.
Delikatne cienie, zaokrąglone rogi, dużo white space.
Animacje subtelne (transitions 200-300ms).
Paleta neutralna z jednym kolorem akcentowym.
Globalne style (styles/global.scss)
SCSS
:root {
  // Colors
  --color-primary: #4A90D9;
  --color-primary-hover: #357ABD;
  --color-success: #27AE60;
  --color-warning: #F2994A;
  --color-danger: #EB5757;
  --color-bg: #F8F9FA;
  --color-surface: #FFFFFF;
  --color-text: #2D3436;
  --color-text-secondary: #636E72;
  --color-border: #E0E0E0;

  // Spacing
  --space-xs: 4px;
  --space-sm: 8px;
  --space-md: 16px;
  --space-lg: 24px;
  --space-xl: 32px;
  --space-2xl: 48px;

  // Typography
  --font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  --font-size-sm: 0.875rem;
  --font-size-md: 1rem;
  --font-size-lg: 1.25rem;
  --font-size-xl: 1.5rem;
  --font-size-2xl: 2rem;

  // Borders & Shadows
  --border-radius: 8px;
  --border-radius-lg: 12px;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.08);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.1);
  --shadow-lg: 0 8px 24px rgba(0,0,0,0.12);

  // Transitions
  --transition-fast: 150ms ease;
  --transition-normal: 250ms ease;
}
Klasy utility (do użycia w komponentach)
.card – biała karta z shadow-sm, border-radius, padding.
.btn – bazowy przycisk.
.btn-primary, .btn-danger, .btn-ghost – warianty.
.status-badge – kolorowy badge statusu (.connected, .disconnected, .pending).
.container – centered max-width container.
.grid-photos – CSS grid do wyświetlania thumbnailów.
.section-header – nagłówek sekcji z opcjonalnymi akcjami.
Routing & Struktura modułów
Code
/                          → redirect do /dashboard
/login                     → logowanie do aplikacji (Basic Auth)
/dashboard                 → główna strona z wyborem konta i statusami
/photos                    → przeglądanie zdjęć
/photos?account=X&year=Y   → filtrowane widoki
Moduły Angular
Code
src/app/
├── core/
│   ├── services/
│   │   ├── auth.service.ts          # logowanie do aplikacji
│   │   ├── api.service.ts           # bazowy HTTP client
│   │   ├── account.service.ts       # zarządzanie kontami iCloud
│   │   ├── photo.service.ts         # operacje na zdjęciach
│   │   ├── device-status.service.ts # status urządzeń
│   │   └── sse.service.ts           # obsługa Server-Sent Events
│   ├── guards/
│   │   └── auth.guard.ts
│   ├── interceptors/
│   │   └── auth.interceptor.ts      # dodaje Basic Auth header
│   └── models/
│       ├── photo.model.ts
│       ├── account.model.ts
│       └── device-status.model.ts
├── shared/
│   ├── components/
│   │   ├── status-badge/            # badge z kolorowym statusem
│   │   ├── photo-thumbnail/         # thumbnail z checkbox selection
│   │   ├── loading-spinner/
│   │   └── confirm-dialog/          # modal potwierdzenia
│   └── pipes/
│       ├── file-size.pipe.ts        # formatowanie rozmiaru pliku
│       └── relative-date.pipe.ts    # "2 days ago"
├── features/
│   ├── login/
│   │   └── login.component.ts       # prosty formularz login
│   ├── dashboard/
│   │   ├── dashboard.component.ts   # główny widok
│   │   ├── account-selector/        # wybór/dodanie konta iCloud
│   │   ├── device-status-panel/     # panel statusów urządzeń
│   │   └── icloud-login-modal/      # modal logowania do iCloud + 2FA
│   └── photos/
│       ├── photos.component.ts      # widok zdjęć
│       ├── photo-grid/              # siatka thumbnailów
│       ├── photo-timeline/          # grupowanie wg lat/miesięcy
│       ├── photo-detail-modal/      # podgląd pełnego zdjęcia
│       ├── batch-actions-bar/       # pasek akcji batch (usuń z iCloud/iPhone)
│       └── granularity-toggle/      # przełącznik Year/Month
└── app.component.ts
Ekrany – szczegóły
1. Login (/login)
Prosty formularz: username, password.
Logowanie do aplikacji (Basic Auth backend).
Po zalogowaniu → redirect do /dashboard.
2. Dashboard (/dashboard)
Panel statusów urządzeń (górna część):

Trzy status-badge obok siebie:
🟢/🔴 External Drive – "Connected" / "Not detected" + przycisk "Re-check"
🟢/🔴 iPhone – "Connected" / "Not connected" + przycisk "Re-check"
🟢/🔴 iCloud – "Logged in (user@icloud.com)" / "Not connected"
Przy kliknięciu "Re-check" na dysku → SSE stream z krokami agenta wyświetlany jako mały log w tooltipie/expandable sekcji.
Wybór konta iCloud (środkowa część):

Lista zalogowanych kont (karty).
Przycisk "+ Add iCloud Account" → otwiera modal.
Modal: apple_id + password → jeśli 2FA → pole na kod.
Po zalogowaniu konto pojawia się na liście.
Quick stats:

Ilość zsynchronizowanych zdjęć.
Zajęte miejsce na dysku.
Ostatnia synchronizacja.
3. Photos (/photos)
Toolbar (góra):

Dropdown: wybór konta iCloud.
Granularity toggle: Year | Month (przełącznik wizualny).
Przycisk "Sync Now" → triggeruje synchronizację.
Wyszukiwarka (by filename) – // OPEN: na MVP może pominąć.
Timeline View:

Zdjęcia pogrupowane w sekcje.
Każda sekcja ma header: np. "2024" lub "January 2024".
Header zawiera: nazwę, liczbę zdjęć, checkbox "select all in section".
Photo Grid:

CSS Grid, responsywny (auto-fill, minmax ~150px).
Każdy thumbnail:
Kwadratowy, object-fit: cover.
Overlay checkbox (widoczny on hover lub gdy tryb selekcji aktywny).
Mała ikonka typu (📷 photo, 🎥 video).
Klik → otwiera modal z pełnym zdjęciem + metadane.
Batch Actions Bar (sticky bottom, widoczny gdy coś zaznaczone):

"X items selected"
Przycisk "Delete from iCloud" (aktywny tylko jeśli wszystkie zaznaczone mają synced_to_disk=true).
Przycisk "Delete from iPhone" (aktywny tylko jeśli wszystkie zaznaczone mają synced_to_disk=true).
Przycisk "Clear selection".
Przed usunięciem → modal potwierdzenia z listą zaznaczonych.
Photo Detail Modal:

Pełnoekranowe zdjęcie.
Metadane: nazwa, rozmiar, data, wymiary.
Statusy: ☁️ on iCloud, 📱 on iPhone, 💾 on disk.
Przyciski: "Delete from iCloud", "Delete from iPhone" (z warunkami jak wyżej).
Komunikacja z backendem
Wszystkie requesty przez ApiService → bazowy URL z konfiguracji environment.
AuthInterceptor dodaje Basic Auth credentials (przechowywane w sessionStorage).
SSE dla detekcji dysku: użyj EventSource API.
// OPEN: WebSocket dla real-time status updates?
Testy
Twórz pliki .spec.ts dla każdego komponentu, serwisu i pipe'a.
Testy puste z it('should ...') i komentarzem // TODO: Implement test.
Minimum describe block z prawidłowymi importami i TestBed setup.
Definicja ukończenia
Użytkownik może się zalogować do aplikacji.
Dashboard wyświetla statusy urządzeń (z danymi z backendu).
Można dodać konto iCloud i przejść 2FA.
Zdjęcia wyświetlane jako thumbnails pogrupowane wg lat/miesięcy.
Selekcja zdjęć i batch delete działa.
Design jest spójny z design systemem.
📋 SPEC – FAZA 4: Integracja & Polish
Cel
Weryfikacja end-to-end, poprawki, dokumentacja, przygotowanie do deploymentu.

Zadania
Integracja end-to-end
Przetestuj pełny flow: docker-compose up → login → dodanie konta iCloud → sync → przeglądanie → batch delete.
Napraw wszelkie problemy z komunikacją między serwisami.
Zweryfikuj volumes i montowanie dysku zewnętrznego.
Error handling
Upewnij się, że wszystkie błędy z mikroserwisu są sensownie propagowane na frontend.
Toast notifications na frontendzie dla error/success.
Graceful handling gdy dysk jest niedostępny.
Dokumentacja
Zaktualizuj README.md z pełną instrukcją.
Dodaj sekcję "Configuration" z opisem zmiennych środowiskowych.
Dodaj sekcję "Architecture" z diagramem (ASCII art lub opis).
Dodaj sekcję "Development" z instrukcją uruchomienia bez Dockera.
Performance
Lazy loading thumbnailów (intersection observer).
Paginacja zdjęć (infinite scroll lub klasyczna).
// OPEN: Cache headers na thumbnailach.
Skrypty CI/CD
// OPEN: GitHub Actions workflow do budowania obrazów Docker.
Przygotuj Makefile lub skrypty helper: make build, make up, make down, make logs.
Definicja ukończenia
Pełny flow działa bez błędów z docker-compose up.
Dokumentacja kompletna.
Wszystkie pliki testowe (stuby) istnieją.
Projekt gotowy do przekazania do uzupełnienia testów przez secondary models.
NOTA DLA MODELU UZUPEŁNIAJĄCEGO TESTY: Wszystkie pliki testowe w projekcie zawierają puste metody testowe oznaczone // TODO: Implement test. Twoje zadanie: zaimplementuj każdy test jednostkowy. Korzystaj z mocków (Mockito/JUnit 5 dla Javy, pytest + unittest.mock dla Pythona, Jasmine/Karma lub Jest dla Angulara). Testuj logikę biznesową, nie infrastrukturę. Każdy test powinien mieć arrange/act/assert (given/when/then). Nie modyfikuj kodu produkcyjnego.
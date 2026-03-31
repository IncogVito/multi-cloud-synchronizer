# Phase 0: Scaffolding & Docker

## Cel
Przygotowanie kompletnej struktury monorepo, konfiguracji buildów i środowiska Docker.

## Wymagania

### Struktura katalogów
Utwórz pełną strukturę katalogów:
```
cloud-synchronizer/
├── backend/                  # Java Micronaut + GraalVM
│   ├── src/main/java/
│   ├── src/test/java/
│   ├── build.gradle.kts
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
```

### Backend scaffolding
- Micronaut projekt z Gradle (Kotlin DSL build.gradle.kts).
- Java w najnowszej LTS (21+). Target: GraalVM native image.
- Zależności startowe: micronaut-http-server, micronaut-serde-jackson, micronaut-data-jdbc, sqlite-jdbc, micronaut-security.
- Konfiguracja application.yml z profilami: dev, docker.
- Placeholder dla datasource SQLite (ścieżka konfigurowana zmienną środowiskową, domyślnie: /mnt/external-drive/cloud-sync.db).

### Mikroserwis iCloud scaffolding
- Python 3.12+, FastAPI, uvicorn.
- requirements.txt z: fastapi, uvicorn, icloudpy, python-dotenv, pydantic.
- Podstawowa struktura: app/main.py, app/routers/, app/services/, app/models/.
- Health check endpoint: GET /health.

### Frontend scaffolding
- Angular (najnowszy, 17+) z Vite jako bundler.
- Plik globalnych styli (styles/global.scss) z podstawowymi zmiennymi CSS (kolory, fonty, spacing, border-radius) – styl delikatny, UX-owy, minimalistyczny.
- Podstawowe klasy utility (.card, .btn, .btn-primary, .status-badge, .container).
- Routing z lazy loading.
- Struktura: core/, shared/, features/.

### Docker
- Dockerfile dla każdego komponentu (multi-stage builds).
- Backend: build z Gradle → GraalVM native image → scratch/distroless runtime.
- Mikroserwis: slim Python image.
- Frontend: build z Node → nginx do serwowania.
- docker-compose.yml:
  - Usługi: backend, icloud-service, frontend.
  - Sieć wewnętrzna: cloud-sync-net.
  - Volumes: mapowanie dysku zewnętrznego do /mnt/external-drive (w backendzie).
  - Mapowanie katalogu ze skryptami shell do kontenera backend.
  - Porty: frontend :80, backend :8080, icloud-service :8000 (tylko wewnętrznie).
  - Health checks dla każdego serwisu.

### Skrypty
Katalog scripts/ z placeholder plikami:
- detect-iphone.sh – `# OPEN: Script to detect connected iPhone via USB`
- mount-drive.sh – `# OPEN: Script to mount external drive`
- check-drive.sh – `# OPEN: Script to check if external drive is available`

### Dokumentacja
- Root README.md z opisem projektu, wymaganiami, instrukcją docker-compose up.
- Każdy komponent: swój README.md.

## Definicja ukończenia
- `docker-compose up` startuje wszystkie 3 kontenery bez błędów.
- Health checki przechodzą.
- Frontend wyświetla pustą stronę z tytułem "Cloud Synchronizer".
- Backend odpowiada na GET /health.
- Mikroserwis odpowiada na GET /health.

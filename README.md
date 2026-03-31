# Cloud Synchronizer

A self-hosted multi-cloud synchronization tool that enables management and synchronization of iCloud content (photos, etc.) via a web interface. Designed to run on a local machine/NAS with an external drive for storage.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Docker Network                         │
│                                                           │
│  ┌──────────┐    ┌──────────────┐    ┌────────────────┐  │
│  │ Frontend │    │   Backend    │    │ iCloud Service │  │
│  │ (Angular)│    │  (Micronaut) │    │   (FastAPI)    │  │
│  │  :80     │───▶│   :8080      │───▶│   (internal)   │  │
│  └──────────┘    └──────┬───────┘    └────────────────┘  │
│                         │                                 │
└─────────────────────────┼───────────────────────────────-┘
                          │
                   ┌──────▼───────┐
                   │  External    │
                   │  Drive       │
                   │  (SQLite)    │
                   └──────────────┘
```

### Components

1. **Frontend** - Angular 17+ SPA served by nginx on port 80. Proxies API calls to backend.
2. **Backend** - Java 21 Micronaut 4 REST API on port 8080. Built as GraalVM native image. Uses SQLite on external drive.
3. **iCloud Service** - Python 3.12 FastAPI microservice (internal only, not exposed). Integrates with Apple iCloud via icloudpy.

## Requirements

- Docker 24+
- Docker Compose v2+
- External USB drive (optional for dev, defaults to `/tmp/cloud-sync-dev`)

## Quick Start

```bash
# Clone the repository
git clone <repo-url>
cd cloud-synchronizer

# Start all services
cd docker
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f

# Stop all services
docker-compose down
```

The frontend will be available at http://localhost.

## Environment Variables

| Variable              | Description                                    | Default                          |
|-----------------------|------------------------------------------------|----------------------------------|
| `EXTERNAL_DRIVE_PATH` | Host path to external drive mount point        | `/tmp/cloud-sync-dev`            |
| `APP_USERNAME`        | Basic auth username for backend API            | `admin`                          |
| `APP_PASSWORD`        | Basic auth password for backend API            | `changeme`                       |

Create a `.env` file in the `docker/` directory to override defaults:

```env
EXTERNAL_DRIVE_PATH=/mnt/my-external-drive
APP_USERNAME=myuser
APP_PASSWORD=mysecurepassword
```

## Health Checks

| Service        | Endpoint                          |
|----------------|-----------------------------------|
| Backend        | http://localhost:8080/api/health  |
| Frontend       | http://localhost:80               |
| iCloud Service | Internal only (port 8000)         |

## Open Decisions

- [ ] **iCloud authentication flow** - How to handle 2FA in a headless environment (SMS vs. trusted device)
- [ ] **Session persistence** - Whether to persist iCloud sessions to disk (security trade-off)
- [ ] **Photo sync strategy** - Full sync vs. incremental; conflict resolution
- [ ] **External drive detection** - Auto-detection of drive mount vs. fixed path configuration
- [ ] **Multi-user support** - Currently single-user via basic auth; OAuth2 for future?
- [ ] **GraalVM build time** - Native compile is slow (~5 min); consider JVM mode for dev
- [ ] **iPhone USB integration** - `detect-iphone.sh` scripts are stubs; requires `libimobiledevice`

## Development

Each component has its own README with local development instructions:

- [Backend README](backend/README.md)
- [iCloud Service README](icloud-service/README.md)
- [Frontend README](frontend/README.md)

## Scripts

Shell scripts in `scripts/` are mounted read-only into the backend container at `/scripts`:

| Script              | Purpose                              | Status |
|---------------------|--------------------------------------|--------|
| `detect-iphone.sh`  | Detect connected iPhone via USB      | Stub   |
| `mount-drive.sh`    | Mount external USB drive             | Stub   |
| `check-drive.sh`    | Check external drive availability    | Stub   |

# Cloud Synchronizer Backend

Java 21 Micronaut 4 backend service with GraalVM native image support. Provides REST API for managing cloud synchronization, backed by SQLite.

## Requirements

- Java 21+
- Gradle 8+ (or use the included wrapper)
- GraalVM 21+ (for native image builds)
- Docker (for containerized runs)

## Running locally

```bash
# Run with Gradle (JVM mode)
./gradlew run

# Run with dev profile (uses local SQLite file ./dev-cloud-sync.db)
MICRONAUT_ENVIRONMENTS=dev ./gradlew run

# Build native image (requires GraalVM)
./gradlew nativeCompile

# Run native binary
./build/native/nativeCompile/cloud-synchronizer-backend
```

## Running with Docker

```bash
# Build image
docker build -t cloud-synchronizer-backend .

# Run container
docker run -p 8080:8080 \
  -e APP_USERNAME=admin \
  -e APP_PASSWORD=changeme \
  -e MICRONAUT_ENVIRONMENTS=docker \
  cloud-synchronizer-backend
```

## Health check

```bash
curl http://localhost:8080/api/health
# {"status":"ok","version":"1.0.0"}
```

## Configuration

| Environment Variable   | Description                        | Default                                  |
|------------------------|------------------------------------|------------------------------------------|
| `EXTERNAL_DRIVE_PATH`  | Path to SQLite database file       | `/mnt/external-drive/cloud-sync.db`      |
| `APP_USERNAME`         | Basic auth username                | `admin`                                  |
| `APP_PASSWORD`         | Basic auth password                | `changeme`                               |
| `MICRONAUT_ENVIRONMENTS` | Active Micronaut profiles        | (none)                                   |
| `ICLOUD_SERVICE_URL`   | URL of the iCloud microservice     | `http://icloud-service:8000`             |

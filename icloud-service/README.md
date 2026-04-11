# iCloud Service

Python 3.12 FastAPI microservice that integrates with Apple iCloud via icloudpy. Handles authentication, photo listing/deletion, and device info retrieval.

## Requirements

- Python 3.12+
- pip

## Running locally

```bash
# Install dependencies
pip install -r requirements.txt

# Run development server
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

# Run tests
pytest tests/
```

## Running with Docker

```bash
# Build image
docker build -t icloud-service .

# Run container
docker run -p 8000:8000 icloud-service
```

## Health check

```bash
curl http://localhost:8000/health
# {"status":"ok","version":"1.0.0"}
```

## API Endpoints

| Method | Path                           | Description                    | Status       |
|--------|--------------------------------|--------------------------------|--------------|
| GET    | /health                        | Health check                   | Implemented  |
| POST   | /auth/login                    | Initiate iCloud login          | Stub         |
| POST   | /auth/2fa                      | Complete 2FA verification      | Stub         |
| GET    | /auth/sessions                 | List active sessions           | Stub         |
| DELETE | /auth/sessions/{session_id}    | Delete a session               | Stub         |
| GET    | /photos                        | List photos                    | Stub         |
| GET    | /photos/{photo_id}             | Get photo metadata             | Stub         |
| GET    | /photos/{photo_id}/thumbnail   | Get photo thumbnail            | Stub         |
| DELETE | /photos/{photo_id}             | Delete photo                   | Stub         |
| GET    | /devices                       | List iCloud devices            | Stub         |

## Notes

- This service is internal-only; it is not exposed outside the Docker network.
- The backend proxies requests to this service.
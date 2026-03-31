# Cloud Synchronizer Frontend

Angular 17+ SPA with standalone components, lazy-loaded routing, and the new application builder (esbuild + Vite). Communicates with the backend via HTTP.

## Requirements

- Node.js 20+
- npm 10+

## Running locally

```bash
# Install dependencies
npm install

# Start development server (http://localhost:4200)
npm start

# Build for production
npm run build

# Run unit tests
npm test
```

## Running with Docker

```bash
# Build image
docker build -t cloud-synchronizer-frontend .

# Run container
docker run -p 80:80 cloud-synchronizer-frontend
```

The nginx server proxies `/api/*` requests to the backend service.

## Project structure

```
src/
├── app/
│   ├── core/
│   │   ├── guards/       # Route guards (auth)
│   │   ├── interceptors/ # HTTP interceptors (auth header)
│   │   ├── models/       # TypeScript interfaces
│   │   └── services/     # Singleton services
│   ├── features/
│   │   ├── dashboard/    # Dashboard page
│   │   ├── login/        # Login page
│   │   └── photos/       # Photos page
│   ├── app.component.ts  # Root component
│   ├── app.config.ts     # Application configuration
│   └── app.routes.ts     # Route definitions
├── environments/         # Environment configs
└── styles/
    └── global.scss       # Global CSS variables and utility classes
```

## Environment variables

Configured via `src/environments/environment*.ts` files:

| Variable    | Development           | Docker (production) |
|-------------|----------------------|---------------------|
| `apiUrl`    | `http://localhost:8080` | `/api` (nginx proxy) |
| `production`| `false`               | `true`              |

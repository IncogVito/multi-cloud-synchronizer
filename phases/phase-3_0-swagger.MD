# Phase 3.0 — Swagger / OpenAPI + Orval Client Generation

## Objective

1. Expose an OpenAPI (Swagger) spec from the Micronaut backend.
2. Configure `orval` in the Angular frontend to generate typed HTTP clients from that spec.
3. Update Dockerfiles so both steps work inside Docker builds.
4. Update `.gitignore` files to exclude generated artifacts.

---

## Part 1 — Backend: Generate OpenAPI spec with Micronaut

### 1.1 Add dependencies to `backend/build.gradle.kts`

In the `dependencies` block, add:

```kotlin
annotationProcessor("io.micronaut.openapi:micronaut-openapi")
implementation("io.micronaut.openapi:micronaut-openapi-annotations")
implementation("io.micronaut.openapi:micronaut-openapi-ui")  // serves Swagger UI + YAML
```

Make sure the micronaut BOM / version catalog resolves `micronaut-openapi` for Micronaut 4.x.
If it is not covered by the BOM, pin the version explicitly:

```kotlin
annotationProcessor("io.micronaut.openapi:micronaut-openapi:6.x.x")
```

Check https://micronaut.io/launch for the current compatible version.

### 1.2 Annotate the application entry point

In `StartupCheck.java` (or wherever `@OpenAPIDefinition` fits best), add:

```java
@OpenAPIDefinition(
    info = @Info(
        title = "Multi-Cloud Synchronizer API",
        version = "1.0.0",
        description = "API for managing photos, accounts, folders and device status"
    )
)
```

Import from `io.swagger.v3.oas.annotations.OpenAPIDefinition` and `io.swagger.v3.oas.annotations.info.Info`.

### 1.3 Annotate existing controllers

For each controller, add `@Tag` at class level and `@Operation` + `@ApiResponse` at method level.
Annotate all six controllers:

| Controller | Suggested `@Tag(name = ...)` |
|---|---|
| `HealthController` | `"Health"` |
| `AccountController` | `"Accounts"` |
| `PhotoController` | `"Photos"` |
| `FolderController` | `"Folders"` |
| `StatusController` | `"Status"` |
| `AgentController` | `"Agent"` |

Example for `AccountController`:

```java
@Tag(name = "Accounts")
@Controller("/api/accounts")
public class AccountController {

    @Operation(summary = "Login", description = "Authenticate with iCloud credentials")
    @ApiResponse(responseCode = "200", description = "Login successful or 2FA required")
    @Post("/login")
    public HttpResponse<?> login(@Body LoginRequest body) { ... }

    // repeat for other methods
}
```

### 1.4 Configure OpenAPI in `backend/src/main/resources/application.yml`

```yaml
micronaut:
  router:
    static-resources:
      swagger:
        paths: classpath:META-INF/swagger
        mapping: /swagger/**
      swagger-ui:
        paths: classpath:META-INF/swagger/views/swagger-ui
        mapping: /swagger-ui/**

openapi:
  enabled: true
```

After a successful `./gradlew build`, the spec is generated at:
`backend/build/resources/main/META-INF/swagger/cloud-synchronizer-backend-1.0.0.yml`

At runtime the spec is available at:
`http://localhost:8080/swagger/cloud-synchronizer-backend-1.0.0.yml`

And Swagger UI at:
`http://localhost:8080/swagger-ui/`

### 1.5 Copy the spec for frontend consumption

After running `./gradlew build` locally, copy the spec to a committed location so the
frontend Dockerfile can use it without starting the backend:

```
backend/src/main/resources/META-INF/swagger/openapi.yml   ← symlink or copy
```

Or better: add a Gradle task in `build.gradle.kts` that copies the generated spec to
`../openapi/openapi.yml` (project root level):

```kotlin
tasks.register<Copy>("exportSwagger") {
    dependsOn("classes")
    from(layout.buildDirectory.dir("resources/main/META-INF/swagger"))
    include("*.yml")
    rename { "openapi.yml" }
    into(layout.projectDirectory.dir("../openapi"))
}
```

Run `./gradlew exportSwagger` to produce `openapi/openapi.yml`.
This file **should be committed** to the repository and regenerated whenever the API changes.

---

## Part 2 — Frontend: Generate API clients with Orval

### 2.1 Install dependencies

```bash
cd frontend
npm install --save-dev orval
```

Orval with Angular needs the following peer packages (install if not already present):

```bash
npm install --save-dev @angular/core @angular/common rxjs
```

These are almost certainly already installed since the project uses Angular.

### 2.2 Create `frontend/orval.config.ts`

```typescript
import { defineConfig } from 'orval';

export default defineConfig({
  cloudSync: {
    input: {
      target: '../openapi/openapi.yml',
    },
    output: {
      target: './src/app/core/api/generated/index.ts',
      schemas: './src/app/core/api/generated/model',
      client: 'angular',
      mode: 'tags-split',        // one file per @Tag
      baseUrl: '/api',
      prettier: true,
    },
    hooks: {
      afterAllFilesWrite: 'prettier --write',
    },
  },
});
```

`mode: 'tags-split'` produces one service file per controller tag:
- `src/app/core/api/generated/accounts.service.ts`
- `src/app/core/api/generated/photos.service.ts`
- etc.

### 2.3 Add script to `frontend/package.json`

```json
{
  "scripts": {
    "generate-api": "orval --config orval.config.ts",
    "prebuild": "npm run generate-api",
    "prestart": "npm run generate-api"
  }
}
```

Using `prebuild` / `prestart` ensures clients are always up-to-date before development or production builds.

### 2.4 Register the generated `HttpClient`-based services in Angular

In `src/app/app.config.ts`, ensure `HttpClientModule` / `provideHttpClient()` is present
(it likely already is via the existing `api.service.ts`):

```typescript
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { authInterceptor } from './core/interceptors/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(withInterceptors([authInterceptor])),
    // ...other providers
  ],
};
```

The generated Angular services are injectable via DI — no additional module registration needed.

---

## Part 3 — Dockerfile updates

### 3.1 Backend `backend/Dockerfile`

In the **builder stage**, after the Gradle build, also run the `exportSwagger` task so the spec
is available as a build artifact:

```dockerfile
# --- builder stage (existing) ---
FROM gradle:8-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle build exportSwagger -x test --no-daemon
```

Then add a minimal stage to export the spec:

```dockerfile
# --- swagger export stage ---
FROM scratch AS swagger
COPY --from=builder /app/../openapi/openapi.yml /openapi.yml
```

This lets the frontend build reference the backend spec via `COPY --from`.

### 3.2 Frontend `frontend/Dockerfile`

Add an `api-gen` stage before the existing `builder` stage that:
1. Copies the swagger spec from the backend image
2. Installs dependencies
3. Runs orval

```dockerfile
# --- api-gen stage ---
FROM node:24-alpine AS api-gen
WORKDIR /app

# Copy swagger spec produced by the backend build
COPY --from=backend-swagger /openapi.yml /openapi/openapi.yml

COPY package*.json ./
COPY orval.config.ts ./
RUN npm ci --ignore-scripts

RUN npm run generate-api

# --- builder stage (existing, modified) ---
FROM node:24-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci

COPY . .
# Copy the already-generated API clients
COPY --from=api-gen /app/src/app/core/api/generated ./src/app/core/api/generated

# Skip prestart/prebuild since generate-api was already run
RUN npm run build -- --no-progress

# --- runtime stage (unchanged) ---
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

**Important:** The frontend image must be built with the backend image reference available.
In `docker-compose.yml` or the build pipeline, build the backend first and reference its
swagger output. If a `docker-compose.yml` already exists, ensure the build order and
`depends_on` (for build contexts with Compose Bake) is set.

If multi-stage cross-image `COPY --from` is not available in your CI, fall back to:
- Committing `openapi/openapi.yml` to the repo (recommended for simplicity)
- Mounting the spec via a volume in CI

---

## Part 4 — `.gitignore` updates

### 4.1 `frontend/.gitignore`

Add the generated API clients directory:

```
# Generated API clients (orval)
src/app/core/api/generated/
```

### 4.2 Root-level or `openapi/.gitignore`

The `openapi/openapi.yml` **should NOT be gitignored** — it must be committed so that:
- The frontend Dockerfile can use it without running the backend
- Developers can see API diffs in PRs

However, if you prefer to generate it on-the-fly in CI only, add:

```
# Uncomment if generating openapi.yml in CI only
# openapi/openapi.yml
```

---

## Verification checklist

- [ ] `./gradlew build` succeeds and produces `build/resources/main/META-INF/swagger/*.yml`
- [ ] `./gradlew exportSwagger` copies the spec to `openapi/openapi.yml`
- [ ] `http://localhost:8080/swagger-ui/` serves the Swagger UI
- [ ] `cd frontend && npm run generate-api` generates files under `src/app/core/api/generated/`
- [ ] `npm run build` succeeds with the generated clients
- [ ] `docker build` of the backend produces the spec artifact
- [ ] `docker build` of the frontend runs orval and builds the app
- [ ] `src/app/core/api/generated/` is listed in `frontend/.gitignore`
- [ ] `openapi/openapi.yml` is committed to the repository

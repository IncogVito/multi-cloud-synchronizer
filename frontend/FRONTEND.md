# Frontend Architecture

Angular 21 SPA. Standalone components, signals-based state, Orval-generated API client.

## Stack

- Angular 21.2.6, TypeScript 5.9 strict
- RxJS 7.8
- Build: esbuild + Vite (Angular application builder)
- Tests: Vitest + @analogjs/vitest-angular
- API client: Orval 7.9.0 from `../openapi.yml`

## Directory layout

```
src/app/
├── features/
│   ├── login/           # Auth: account select, password, 2FA, account creation
│   ├── dashboard/       # 8 sub-components: sync, accounts, storage stats, device status
│   ├── photos/          # 6 sub-components: timeline, toolbar, batch actions, detail modal
│   ├── setup-wizard/    # 4-step wizard: disk confirm, folder picker, strategy, reorganize
│   └── disk-setup/      # Mount/unmount disk UI
├── core/
│   ├── services/        # 11 business services
│   ├── guards/          # authGuard, appContextGuard
│   ├── interceptors/    # authInterceptor, noContextInterceptor
│   ├── models/          # 6 TypeScript model files
│   └── api/generated/   # Orval output (do not edit manually)
└── app.component.ts     # Root component + sidebar
```

## State management

Currently **no NGXS**. State is split across:

| Mechanism | Used for |
|---|---|
| Angular Signals | `AppContextService` (device+folder context), `ToastService` (toast queue) |
| RxJS BehaviorSubject | `SyncService` (sync progress), `DiskIndexingService` (disk scan progress) |
| sessionStorage | Auth credentials (BasicAuth encoded) |

### TODO: migrate to NGXS

Replace ad-hoc signal/BehaviorSubject state with proper NGXS stores. Candidate states:

- `AppContextState` — device+folder context (replaces `AppContextService` signals)
- `AuthState` — credentials (replaces direct sessionStorage reads)
- `SyncState` — sync progress + status (replaces BehaviorSubject in `SyncService`)
- `PhotosState` — photo list, pagination, selection, granularity toggle
- `DiskIndexState` — indexing progress (replaces BehaviorSubject in `DiskIndexingService`)

**Why:** components currently subscribe to service internals directly; NGXS gives clean selector API, Redux DevTools integration, and easier unit testing.

## Key services

| Service | Purpose |
|---|---|
| `AppContextService` | Central device+folder context; signals + HTTP |
| `SyncService` | Sync orchestration + SSE progress streaming |
| `DiskIndexingService` | Disk scan progress via SSE |
| `AuthService` | BasicAuth credentials in sessionStorage |
| `AccountService` | iCloud account management |
| `DiskSetupService` | Disk mount/unmount |
| `SetupWizardService` | Setup wizard HTTP calls |
| `ToastService` | Signal-based toast queue (auto-dismiss 4-6s) |
| `ApiService` | Generic `HttpClient` wrapper |

## Routing

All routes lazy-loaded via `loadComponent()`.

| Route | Guards |
|---|---|
| `/login` | — |
| `/setup` | `authGuard` |
| `/setup/wizard` | `authGuard`, `appContextGuard` |
| `/dashboard` | `authGuard`, `appContextGuard` |
| `/photos` | `authGuard`, `appContextGuard` |

- `authGuard` — requires `AuthService.isAuthenticated()`, else → `/login`
- `appContextGuard` — requires active device+folder context, else → `/setup`

## HTTP interceptors

- `authInterceptor` — injects `Authorization: Basic <encoded>` on every request
- `noContextInterceptor` — catches HTTP 409 `NO_ACTIVE_CONTEXT`, clears context signal, shows toast, redirects to `/setup`

## SSE streaming

`SyncService` and `DiskIndexingService` use raw `fetch()` + `ReadableStream` + `AbortController` (not `HttpClient`) for Server-Sent Events. Progress events published via `BehaviorSubject` to subscribing components.

## API client generation

```bash
# 1. Regenerate openapi.yml from backend annotations
cd backend && ./gradlew exportSwagger

# 2. Regenerate Angular services from spec
cd frontend && npm run generate-api
```

`prestart` and `prebuild` npm scripts run step 2 automatically. Never edit files under `src/app/core/api/generated/` by hand.


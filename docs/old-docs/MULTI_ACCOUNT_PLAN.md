# Plan: Multi-Account Isolation

**Cel:** Wszystkie dane (zdjęcia, statystyki, taski) scopowane per `account_id`, nie per `storage_device_id`.
Jeden dysk może trzymać wiele kont. Konta się nie nachodzą.

---

## Decyzje architektoniczne (ustalone)

| # | Decyzja | Wybór |
|---|---|---|
| 1 | Scope danych | `account_id` = primary. `storage_device_id` = hardware context tylko |
| 2 | Przełączanie kont | Brak switchera. Logout → Login żeby zmienić konto |
| 3 | Restore sesji po restarcie | `activeAccountId` w localStorage + walidacja przez `GET /api/accounts/{id}/status` |
| 4 | Stats response shape | Bez zmian. Parametr `storageDeviceId` → `accountId`. `diskCapacityBytes`/`diskFreeBytes` pobierane z `account.storageDeviceId` wewnętrznie |
| 5 | Ten sam plik na wielu kontach | Dwa osobne pliki na dysku. Brak cross-account file sharing |
| 6 | `photos.account_id` nullable | Zostaje nullable. Orphan photos (NULL) niewidoczne w UI |
| 7 | Virtual folders | Odłożone — frontend ich nie używa znacząco |
| 8 | `app_context` | Zostaje bez zmian (storage_device_id + basePath). BEZ dodawania account_id |
| 9 | Task history scope | Tylko aktywne konto |
| 10 | Logout vs usuń konto | Dwie operacje: "Wyloguj" (czyści sesję + localStorage) vs "Usuń konto" (usuwa rekord, pliki zostają) |
| 11 | `activeAccountId` storage | Client-side localStorage + Angular signal. NIE w app_context (server-side) |
| 12 | Route guards | brak auth→/login, brak device→/setup, brak account→/login, sesja wygasła→/login |
| 13 | Strona logowania | Bez zmian (pusty formularz Apple ID + hasło) |
| 14 | Dashboard konta | Wszystkie konta na dysku widoczne. Aktywne wyraźnie wyróżnione. Brak przycisku "przełącz" |
| 15 | Usuń aktywne konto | Auto-logout → usuwa rekord → redirect /login. Pliki na dysku zostają |
| 16 | Thumbnail jobs | Per aktywne konto (`accountId` param zamiast `storageDeviceId`) |
| 17 | DiskIndexingController | Usunąć w całości. Wszystkie 3 operacje (reorganize-preview, reorganize, index) → account-scoped w SyncController. Base path = `account.syncFolderPath` |

---

## Obecny stan (diagnoza)

| Zasób | Obecny scope | Problem |
|---|---|---|
| `photos` | `storage_device_id` (primary) + `account_id` (secondary, optional) | Stats agregują wszystko z dysku |
| `stats` | `storage_device_id` only | Brak per-account breakdown |
| `task_history` | `account_id` + `provider` | OK, nie wymaga zmian w schemacie |
| `virtual_folders` | `storage_device_id` only | Odłożone |
| iPhone photos | `source_provider='IPHONE'` | iPhone = source, nie właściciel. Sync do konta X → `account_id=X`. Ten sam iPhone może zasilać wiele kont |

---

## Docelowy model

```
StorageDevice (dysk)
  └── ICloudAccount A  (syncFolderPath: device/folder-a)
  │     └── photos (account_id = A, file_path w folder-a)
  │     └── task_history (account_id = A)
  └── ICloudAccount B  (syncFolderPath: device/folder-b)
        └── photos (account_id = B, file_path w folder-b)
        └── task_history (account_id = B)

activeAccountId → localStorage (client-side)
app_context     → storage_device_id + basePath (bez zmian)
```

---

## Kroki implementacji

### Krok 1 — Baza danych

Brak nowych migracji wymaganych. Istniejący schemat już obsługuje multi-account:
- `photos.account_id` — istnieje, nullable ✓
- `icloud_accounts.storage_device_id` — istnieje ✓
- `task_history.account_id` — istnieje ✓

Migracja V15 (`virtual_folders.account_id`) — odłożona.

---

### Krok 2 — iPhone photos (brak zmian)

`startSync(accountId, "IPHONE")` już przekazuje `accountId`. Zdjęcia z iPhone dostają `account_id` konta które zainicjowało sync. Jeden iPhone → wiele kont = osobne rekordy z różnymi `account_id`. Bez zmian w `IPhoneSyncProvider`.

---

### Krok 3 — StatsService

```java
// Stare
StatsResponse getStats(String storageDeviceId)

// Nowe
StatsResponse getStats(String accountId)
// storageDeviceId wyciągane z account.storageDeviceId internaly
// diskCapacityBytes / diskFreeBytes pobierane z StorageDevice (przez account)
```

Endpoint: `GET /api/stats/overview?storageDeviceId=...` → `GET /api/stats/overview?accountId=...`

---

### Krok 4 — PhotoController / PhotoService

```java
// Stare
listPhotos(String accountId?, Boolean synced, String storageDeviceId, ...)

// Nowe
listPhotos(String accountId, Boolean synced, ...)
// storageDeviceId wyciągane z account.storageDeviceId internaly
```

Dotyczy też:
- `getMonthsSummary(accountId)` — usuwa `storageDeviceId` param
- `countMissingThumbnails(accountId)`
- `startThumbnailJob(accountId)`

---

### Krok 5 — DiskIndexingController → account-scoped

Usunąć `DiskIndexingController` w całości.

Przenieść do `SyncController` (account-scoped):
- `GET /{accountId}/reorganize-preview` — już istnieje, fix: użyć `account.syncFolderPath` zamiast `ctx.basePath()`
- `POST /{accountId}/reorganize` — już istnieje, ten sam fix
- `GET /{accountId}/index` — nowy endpoint, skanuje `account.syncFolderPath`

**Fix bugu reorganize-preview:** `SyncService.reorganizePreview(accountId)` i `reorganize(accountId)` muszą używać `account.getSyncFolderPath()` jako basePath. Obecny kod używa globalnego `ctx.basePath()` co powoduje błędne oznaczanie zdjęć konta A jako niezorganizowanych przy przeglądaniu konta B.

---

### Krok 6 — Task history filtering

`GET /api/jobs/history` — dodać filtrowanie po `accountId`. Backend zwraca tylko taski aktywnego konta.

---

### Krok 7 — Nowy task: "Przypisz zdjęcia bez konta"

Task widoczny w `/tasks` w sekcji "Dostępne akcje". Pojawia się gdy `COUNT(photos WHERE account_id IS NULL AND storage_device_id = account.storageDeviceId) > 0`.

Zachowanie:
- Tworzy rekord w `task_history` (type: `ASSIGN_ORPHAN_PHOTOS`, account_id = aktywne konto)
- Przypisuje wszystkie orphan photos z dysku konta do `account_id` aktywnego konta
- SSE progress jak inne taski
- Po zakończeniu: pokazuje ile zdjęć zostało przypisanych

---

### Krok 8 — Frontend

**AppContextService / nowy AccountSessionService:**
- Dodać `activeAccountId` jako signal + zapis/odczyt z localStorage
- `load()` rozszerzony: po załadowaniu device context → sprawdź `localStorage.activeAccountId` → `GET /api/accounts/{id}/status` → jeśli sesja aktywna: restore; jeśli nie: redirect `/login`

**Route guards:**
- `appContextGuard` rozszerzyć o sprawdzenie aktywnego konta

**Dashboard `AccountsPanelComponent`:**
- Wyróżnić aktywne konto (badge "Aktywne", inne obramowanie)
- Brak przycisku przełączania

**Nowe przyciski na karcie konta:**
- "Wyloguj" (tylko sesja) — dostępny na aktywnym koncie
- "Usuń konto" — dostępny na każdym koncie

**StatsComponent:** `accountId` zamiast `storageDeviceId`

**PhotoGridComponent:** `accountId` jako required input

**TaskHistoryComponent:** filtrowanie po aktywnym koncie

**TasksComponent:** nowa sekcja "Dostępne akcje" z taskiem przypisywania orphan photos

**Orval API client** — regenerować po zmianach w openapi.yml.

---

### Krok 9 — API endpoints (breaking changes)

| Endpoint | Stary param | Nowy param |
|---|---|---|
| `GET /api/stats/overview` | `storageDeviceId` | `accountId` |
| `GET /api/photos` | `storageDeviceId` (required) | `accountId` (required) |
| `GET /api/photos/months-summary` | `storageDeviceId` (required) | `accountId` (required) |
| `GET /api/photos/missing-thumbnails-count` | `storageDeviceId` (optional) | `accountId` (optional) |
| `POST /api/photos/thumbnail-jobs` | `storageDeviceId` | `accountId` |
| `GET /api/jobs/history` | brak filtra | `accountId` (active account) |
| `GET /api/disk-indexing/reorganize-preview` | usunąć | → `GET /api/sync/{accountId}/reorganize-preview` |
| `POST /api/disk-indexing/reorganize` | usunąć | → `POST /api/sync/{accountId}/reorganize` |
| `GET /api/disk-indexing/index` | usunąć | → `GET /api/sync/{accountId}/index` (nowy) |

---

## Znane bugi do naprawy przy okazji

### Bug: reorganize-preview nie jest per-folder

**Symptom:** Nowe konto w `device/folder-b` widzi zdjęcia niezorganizowane mimo że folder jest pusty.

**Root cause:** `SyncService.reorganizePreview(accountId)` używa globalnego `ctx.basePath()` zamiast `account.getSyncFolderPath()`. Zdjęcia konta A w `device/folder-a/2023/01/photo.jpg` → relativizacja względem `device/` = `folder-a/2023/01` → nie pasuje do `\d{4}/\d{2}` → błędnie oznaczone jako niezorganizowane.

**Fix:** Użyć `account.getSyncFolderPath()` jako basePath we wszystkich reorganize/index operacjach.

---

## Kolejność prac (recommended)

1. **Bug fix reorganize-preview** (Krok 5 częściowo) — izolowany fix, nie wymaga reszty planu
2. **StatsService** (Krok 3) — izolowana zmiana, łatwa do testowania
3. **PhotoService/Controller** (Krok 4) — core flow
4. **DiskIndexingController usunięcie** (Krok 5) — po migracji frontendu na nowe endpointy
5. **Task history filtering** (Krok 6)
6. **Orphan photos task** (Krok 7)
7. **Frontend** (Krok 8) — po ustabilizowaniu API
8. **openapi.yml regeneracja + Orval** — po każdej zmianie kontraktów

---

## Ryzyka

- **Breaking API** — frontend i backend muszą być aktualizowane razem (lub feature-flagowane).
- **SQLite ALTER TABLE** — jeśli virtual_folders.account_id zostanie odblokowane w przyszłości: Flyway 9 + SQLite może wymagać copy-table pattern.
- **Orphan photos backfill** — photos bez `account_id` są niewidoczne dopóki user nie uruchomi taska przypisywania. Jeśli user nie wie o tasku → "zgubiła się" część zdjęć z perspektywy UI.

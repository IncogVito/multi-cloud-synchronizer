# Analiza: `app_context.basePath` vs `account.syncFolderPath`

**Objaw:** POST `/api/context` (basePath ustawiony) → zaraz potem GET `/api/sync/{accountId}/reorganize-preview` zwraca `400 SYNC_FOLDER_NOT_CONFIGURED`.

**Konto `7a322d32` ma `syncFolderPath = null`.** To nie jest bug w reorganize — to bug w modelu: folder konta nigdy nie zostaje ustawiony.

---

## Dwa rozłączne pojęcia "folderu"

| | `app_context.basePath` | `account.syncFolderPath` |
|---|---|---|
| Scope | **globalny** (singleton, 1 wiersz, bez `account_id`) | **per-konto** |
| Ustawiany przez | POST `/api/context` (disk-setup) | PUT `/accounts/{id}/sync-config` (tylko setup-wizard) |
| Trwałość | `app_context` table | `icloud_accounts.sync_folder_path` |

Multi-account = wiele kont na jednym dysku, każde w swoim podfolderze. **Jeden globalny `basePath` nie może reprezentować N folderów kont.** To jest źródło błędu (decyzja #8 w planie zostawiła `app_context` bez `account_id`, ale kod sync nadal na nim polega).

---

## Gdzie `app_context.basePath` jest używany (i dlaczego error-prone)

| Plik:linia | Użycie | Problem |
|---|---|---|
| `SyncService:373` `compareAndPersist` | `scanDiskFiles(Path.of(ctx.basePath()))` — wykrywanie "już na dysku" | Skanuje **globalny** folder, nie folder konta → cross-account false positives/negatives |
| `SyncService:831` `downloadOne` | `resolveDestDir(ctx.basePath(), ...)` — **fizyczny zapis pobranych zdjęć** | Wszystkie konta piszą do **tego samego** globalnego folderu |
| `SyncService:121,158` | `requireActive()` — brama startu/confirm sync, `ctx` przekazany niżej | propaguje globalny basePath do download |
| `SyncService:930` | `getActive().freeBytes()` — statystyki | OK-ish (tylko wolne miejsce) |
| `StatsService:55` | `Files.getFileStore(ctx.basePath())` — wolne miejsce | Mógłby liczyć z folderu konta |

**Kluczowy konflikt:** sync **pisze** do `ctx.basePath()` (globalny), a reorganize/index **czytają** `account.syncFolderPath` (`SyncService:190,219`, `DiskIndexingService:84`, `SetupService:147`). Te dwie ścieżki mogą się rozjeżdżać → reorganize szuka tam gdzie sync nie zapisał, albo 400 gdy `syncFolderPath` null.

---

## Dlaczego `syncFolderPath` jest null

`AccountService.login()` (`:50-65`) tworzy konto i ustawia tylko `sessionId`, `appleId`, `displayName`.
**NIE ustawia** `syncFolderPath` ani `storageDeviceId`.

Jedyne miejsce ustawiające `syncFolderPath` = `SetupService.saveSyncConfig()` (`:115`), wołane **wyłącznie** z setup-wizard (`reorganize-step.component.ts:275`).

Flow disk-setup (`disk-setup.component.ts:540` → POST `/api/context`) ustawia **tylko** globalny `app_context`, pomija per-konto config. Konto utworzone przez logowanie poza wizardem → `syncFolderPath = null` na zawsze.

---

## Rekomendacja

`account.syncFolderPath` = **jedyne źródło prawdy** dla folderu konta. `app_context.basePath` przestaje być używany do operacji na plikach.

1. **Ustawiać `syncFolderPath` przy "utworzeniu konta na dysku"** — gdy konto zostaje powiązane z dyskiem (`storageDeviceId` + folder), zapisać `syncFolderPath`. Nie polegać na osobnym kroku wizarda.
2. **Zastąpić wszystkie `ctx.basePath()` w ścieżce sync** (`SyncService:373`, `:831`, parametry `compareAndPersist`/`downloadOne`) przez `account.getSyncFolderPath()`.
3. **`app_context`** zostaje tylko jako kontekst urządzenia (mount point, wolne miejsce dysku). Wolne miejsce można liczyć z folderu konta.
4. **Guard niezmieniony:** `requireSyncFolderPath()` dalej rzuca `SyncFolderNotConfiguredException` gdy folder nieustawiony — ale po fixie folder zawsze będzie ustawiony przy powiązaniu konta z dyskiem, więc 400 nie wystąpi w normalnym flow.

---

## Decyzje (ustalone w grill)

| # | Decyzja | Wybór |
|---|---|---|
| D1 | Model folderu | **Explicit pick per konto.** Każde konto wybiera własny folder → `account.syncFolderPath`. `app_context.basePath` nie reprezentuje folderu konta |
| D2 | Trigger wyboru folderu | **Nowy guard:** aktywne konto z `syncFolderPath == null` → redirect do wyboru folderu (reuse wizard). Persist przez istniejący `PUT /accounts/{id}/sync-config` |
| D3 | `app_context.basePath` | **Usunąć całkowicie** (entity, DTO, kontrakt POST, frontend model, migracja drop column). `app_context = { storageDeviceId, mountPoint, setAt }` |
| D4 | Migracja istniejących danych | **Tylko drop `base_path`, bez backfill.** Istniejące konta z null przechodzą guard → wybierają folder raz. Reguła: po ustawieniu `syncFolderPath` **nigdy nie pytać ponownie** (zmiana tylko przez jawną akcję "zmień folder") |
| D5 | Orphaned setup-wizard | **Reuse jako krok wyboru folderu per konto** (nie usuwać) |
| D6 | Reshape wizarda | Konfiguruje **aktywne konto** (z `AccountSessionService`, nie `accounts[0]`). `saveSyncConfig` przeniesiony na **moment wyboru folderu (krok 2), bezwarunkowo** (obecnie tylko w kroku 4 reorganize → pomijany dla pustego folderu = root bug). Krok "Dysk" (1) pominięty/pre-filled z `app_context`. `onWizardDone` **przestaje** wołać `appContextService.set(deviceId, folder)` |
| D7 | Backend sync path | **Full decouple od `app_context`.** `compareAndPersist`/`downloadOne` → `account.getSyncFolderPath()`; `classifyAsset` → `account.getStorageDeviceId()`; `startSync`/`confirmSync` → `requireSyncFolderPath(account)` fail-fast. `StatsService` free-space z `syncFolderPath`; `SyncService:930` `freeBytes` z mount urządzenia |

---

## Plan implementacji

### Backend
1. **`SyncService`** — `compareAndPersist(:368)` i `downloadOne(:807)`: `ctx.basePath()` → `account.getSyncFolderPath()`; `classifyAsset(:391)` `appContext.storageDeviceId()` → `account.getStorageDeviceId()`. `startSync(:120)`/`confirmSync(:156)`: dodać `requireSyncFolderPath(account)` na początku. Usunąć przekazywanie `AppContext` tam gdzie służył tylko do basePath.
2. **`StatsService:55`** — free-space `Files.getFileStore(account.syncFolderPath)`; `SyncService:930` `freeBytes` z mount pointu urządzenia.
3. **`AppContextService.setContext`** — usunąć param `basePath`, walidację ścieżki, `createDirectories`, `isWritable`. Zostaje walidacja: urządzenie zamontowane. (Writable-check przenieść do `saveSyncConfig` jeśli potrzebny.)
4. **`AppContext` DTO / `AppContextEntity` / `AppContextController` POST** — usunąć `basePath`/`relativePath`.
5. **Flyway** — nowa migracja: copy-table drop `app_context.base_path` (SQLite, Flyway 9 pattern). Bez backfill.
6. **`requireSyncFolderPath` / `SyncFolderNotConfiguredException`** — bez zmian (guard zapewnia, że nie wystąpi w normalnym flow; zostaje jako defense).

### Frontend
7. **Nowy guard** `syncFolderGuard` (lub rozszerzenie `accountGuard`): aktywne konto bez `syncFolderPath` → `/setup/wizard?accountId=ACTIVE`. Dodać do `/dashboard`, `/photos`, `/tasks`.
8. **`setup-wizard.component`** — `accountId` z `AccountSessionService.activeAccountId()` (nie `accounts[0]`); pre-fill `deviceId` z `app_context`, start od kroku 2; `onWizardDone` bez `appContextService.set(...)`.
9. **`onFolderSelected`** — wołać `saveSyncConfig` natychmiast (ustawia `syncFolderPath` + `storageDeviceId` + `organizeBy`).
10. **`app-context.model` / `disk-setup` confirmContext** — usunąć `basePath`; disk-setup = tylko wybór/montaż dysku. `active-context-card` "change-folder" → wizard per konto; "change-disk" bez zmian.
11. **Orval** — regen po zmianie `openapi.yml` (POST /api/context bez basePath).

## Plan testów (failing-first, per FIX_AGENT_INSTRUCTIONS)
- **Backend:** `startSync`/`confirmSync` rzuca `SyncFolderNotConfiguredException` gdy `syncFolderPath == null` (fail-fast). Test, że download dest = `account.syncFolderPath` (nie globalny basePath). Istniejące `SyncServiceReorganizeTest` (null→throw) zostają zielone.
- **Backend:** `saveSyncConfig` ustawia `syncFolderPath` + `storageDeviceId`.
- **Frontend:** guard redirectuje gdy `syncFolderPath` null; wizard woła `saveSyncConfig` na wyborze folderu (krok 2), także dla pustego folderu.

## Otwarte ryzyko zaakceptowane
- Istniejące zsynchronizowane pliki leżą fizycznie pod starym `basePath`. Po drop+re-pick, jeśli user wybierze **inny** folder → pliki osierocone (do czasu re-index/reorganize na nowym folderze). Zaakceptowane (D4).

---

## Pliki do zmiany (po decyzji)

- `AccountService` / nowy endpoint — ustawianie `syncFolderPath` przy powiązaniu z dyskiem
- `SyncService:368-409` (`compareAndPersist`), `:807-832` (`downloadOne`) — `account.syncFolderPath` zamiast `ctx.basePath()`
- `StatsService:55` — opcjonalnie folder konta
- Frontend disk-setup / login flow — wybór folderu per konto
- Testy: sync zapisuje do `account.syncFolderPath`; nowe konto dostaje `syncFolderPath` przy powiązaniu z dyskiem

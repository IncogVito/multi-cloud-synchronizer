# Plan: Kontekst dysku zewnętrznego jako globalny stan aplikacji

## Cel

Cała aplikacja ma działać **w kontekście wybranego dysku i wybranego folderu na tym dysku**. To jest "sync context" — jedyne miejsce, gdzie zdjęcia są synchronizowane, organizowane i przeglądane. Dopóki użytkownik nie wybierze dysku **i** ścieżki, żadne inne funkcje (logowanie do iCloud, dodawanie kont, synchronizacja, przeglądanie zdjęć, foldery wirtualne) nie są dostępne.

## Założenia

- **Sync context = `{ storageDeviceId, basePath }`** gdzie `basePath` to absolutna ścieżka pod `mountPoint` dysku (np. `/mnt/external-drive/Pictures/iCloud`).
- Kontekst jest **globalny i pojedynczy** — jeden aktywny w danym momencie. Zmiana wymaga jawnej akcji "Zmień dysk" lub "Zmień folder".
- Kontekst jest **persystentny** — zapisany w lokalnej bazie (SQLite na stałym wolumenie). Po restarcie aplikacji ładuje się automatycznie.
- Wszystkie zapytania do `photos`, `folders`, `sync` są **filtrowane po `storageDeviceId`**. Bez kontekstu zwracają 409 `NO_ACTIVE_CONTEXT`.
- Frontend renderuje "lock screen" zamiast normalnej zawartości, dopóki kontekst nie jest ustawiony.

---

## Backend

### Schemat bazy

#### Nowa tabela `app_context`

Singleton (jeden wiersz), trzyma aktywny sync context:

```sql
CREATE TABLE app_context (
    id              INTEGER PRIMARY KEY CHECK (id = 1),  -- enforce singleton
    storage_device_id TEXT REFERENCES storage_devices(id),
    base_path       TEXT,           -- absolutna ścieżka pod mount_point
    set_at          TIMESTAMP,
    set_by          TEXT             -- username (na przyszłość)
);
INSERT INTO app_context (id) VALUES (1);  -- pusty wiersz na starcie
```

Dlaczego singleton, a nie kolumna w `storage_devices`? Bo "aktywność" to stan aplikacji, nie właściwość dysku. Singleton upraszcza UPDATE i atomowość.

#### Migracja Flyway: `V<next>__app_context.sql`

Stwórz tabelę + pusty wiersz. Pole `storage_device_id` może być `NULL` (brak kontekstu = stan startowy).

### Nowy serwis `AppContextService`

Plik: `backend/src/main/java/com/cloudsync/service/AppContextService.java`

Odpowiedzialności:
- `Optional<AppContext> getActive()` — zwraca aktywny kontekst lub pusty
- `AppContext setContext(String storageDeviceId, String basePath)` — waliduje + zapisuje
- `void clear()` — czyści kontekst (np. przy odmontowaniu)
- `AppContext requireActive()` — rzuca `NoActiveContextException` jeśli brak; używane przez serwisy chronione kontekstem

Walidacje przy `setContext`:
1. `storageDeviceId` istnieje w `storage_devices`
2. Dysk jest aktualnie zamontowany (sprawdź `mount_point` przez `findmnt` lub `check-drive.sh`)
3. `basePath` zaczyna się od `mount_point` dysku (zapobiega path traversal)
4. `basePath` istnieje, jest katalogiem, jest writable
5. Tworzy katalog jeśli nie istnieje (z flagą `create=true`)

Cache: trzymaj aktualny kontekst w polu `volatile AppContext` żeby uniknąć zapytań do DB na każdy request. Invaliduj przy `setContext`/`clear`.

### Nowy DTO

```java
public record AppContext(
    String storageDeviceId,
    String storageDeviceLabel,
    String mountPoint,
    String basePath,        // absolutna ścieżka, zawiera mountPoint jako prefix
    String relativePath,    // basePath bez mountPoint, do wyświetlenia (np. "Pictures/iCloud")
    Long freeBytes,
    Instant setAt
) {}
```

### Nowy kontroler `AppContextController`

Plik: `backend/src/main/java/com/cloudsync/controller/AppContextController.java`

Endpointy (wszystkie `@Secured(IS_AUTHENTICATED)` poza `GET` który jest `IS_ANONYMOUS` żeby frontend mógł sprawdzić stan przy starcie):

```
GET    /api/context              -> AppContext | 204 No Content
POST   /api/context              -> AppContext   (body: { storageDeviceId, basePath })
DELETE /api/context               -> 204
GET    /api/context/browse?path= -> List<DirEntry>  (do path pickera, ograniczone do mountPoint)
POST   /api/context/mkdir         -> AppContext   (body: { name }, tworzy folder pod basePath)
```

`/browse` musi:
- przyjmować ścieżkę WZGLĘDNĄ od mount_point dysku (nie absolutną)
- odrzucać `..` i symlinki wychodzące poza mount_point
- zwracać tylko katalogi (do wyboru lokalizacji), opcjonalnie z liczbą zdjęć w środku
- działać tylko jeśli jakiś dysk jest zamontowany; jeśli kontekst jest już ustawiony, browse może być wywołane też do podglądu, ale przy zmianie kontekstu trzeba wybrać nową ścieżkę

### Wymuszenie kontekstu w istniejących serwisach

Dodaj `AppContextService.requireActive()` na początku metod chronionych kontekstem:

| Serwis                  | Metody do osłonięcia                                          |
|-------------------------|---------------------------------------------------------------|
| `SyncService`           | `startSync()`, `getStatus()`                                  |
| `PhotoService`          | `listPhotos()`, `getPhoto()`, `getThumbnail()`, `assignToFolder()`, `delete()` |
| `FolderService`         | wszystkie CRUD                                                |
| `AccountService`        | `login()`, `submitTwoFa()` (logowanie wymaga, gdzie zapisać sesję) |

`PhotoRepository`, `FolderRepository` itd. dostają nowe metody `findByStorageDeviceId(...)`. Wszystkie istniejące zapytania filtrowane po `storage_device_id` z aktywnego kontekstu.

Migracja danych: na start istniejące rekordy `photos`/`folders` muszą dostać `storage_device_id`. Migracja Flyway przypisze je do aktualnie zamontowanego dysku, jeśli istnieje (lookup po `mount_point`/`filesystem_uuid`); jeśli nie — zostawi NULL i takie rekordy będą niedostępne dopóki user nie ustawi kontekstu i nie uruchomi rebackfillu (osobny endpoint admina, poza zakresem tego planu).

### Nowy wyjątek + handler

`NoActiveContextException` → `GlobalExceptionHandler` mapuje na **HTTP 409 Conflict** z body:
```json
{ "error": "NO_ACTIVE_CONTEXT", "message": "Wybierz dysk i folder docelowy w ustawieniach." }
```

Frontend traktuje 409 z tym kodem jako sygnał do przekierowania na ekran wyboru kontekstu.

### Punkty styku z dyskiem

- `DiskSetupService.unmount()` musi wywołać `appContextService.clear()` JEŚLI odmontowuje aktywny dysk kontekstu (inaczej zostawia osierocony kontekst).
- `DiskSetupService.mountAndRegister()` **NIE** ustawia automatycznie kontekstu — to osobny krok użytkownika (musi wybrać ścieżkę).
- Startup check (`StartupCheck.java`): jeśli aktywny kontekst wskazuje dysk który jest aktualnie zamontowany — jest OK. Jeśli dysk nieobecny — kontekst zostaje, ale `getActive()` zwraca z flagą `degraded=true` (do dodania w DTO) i UI pokazuje ostrzeżenie "Dysk niedostępny — podłącz lub wybierz inny".

---

## Frontend

### Globalny stan kontekstu

Plik: `frontend/src/app/core/services/app-context.service.ts`

```ts
@Injectable({ providedIn: 'root' })
export class AppContextService {
  private _context = signal<AppContext | null>(null);
  private _loading = signal(true);
  context = this._context.asReadonly();
  loading = this._loading.asReadonly();
  hasContext = computed(() => this._context() !== null);

  load(): Observable<AppContext | null> { /* GET /api/context, set signal */ }
  set(storageDeviceId: string, basePath: string): Observable<AppContext> { /* POST */ }
  clear(): Observable<void> { /* DELETE */ }
}
```

### Inicjalizacja przy starcie aplikacji

W `app.config.ts` użyj `provideAppInitializer` (Angular 19+) żeby załadować kontekst PRZED pierwszym renderem routera:

```ts
provideAppInitializer(() => {
  const ctx = inject(AppContextService);
  return firstValueFrom(ctx.load().pipe(catchError(() => of(null))));
})
```

### Globalny guard `appContextGuard`

Plik: `frontend/src/app/core/guards/app-context.guard.ts`

```ts
export const appContextGuard: CanActivateFn = () => {
  const ctx = inject(AppContextService);
  const router = inject(Router);
  if (ctx.hasContext()) return true;
  return router.parseUrl('/setup');
};
```

Nakłada się na **wszystkie chronione trasy**: `/dashboard`, `/photos`, `/setup/wizard`. Trasa `/setup` (wybór dysku + ścieżki) sama z siebie NIE wymaga kontekstu.

### HTTP interceptor `noContextInterceptor`

Łapie odpowiedzi 409 z `error.error === 'NO_ACTIVE_CONTEXT'` i:
- czyści lokalny stan kontekstu (`appContextService.clear()` lokalnie, bez wywołania backendu)
- nawiguje na `/setup`
- wyświetla toast "Wybierz dysk i folder docelowy aby kontynuować"

Rejestracja w `app.config.ts` obok `authInterceptor`.

### Przebudowa `/setup` na pełny kreator kontekstu

Obecny `disk-setup.component.ts` ma 3 stany (loading / no-disk / disks-available / mounted). Trzeba dodać czwarty: **wybór ścieżki**.

Nowy flow:
1. **Step 1 — wybór dysku**: lista z `/api/setup/disks`. Klik "Wybierz" wywołuje `mount`. Jeśli dysk JUŻ jest zamontowany, ten krok jest pominięty (z opcją "Zmień dysk" która unmountuje + wraca do listy).
2. **Step 2 — wybór folderu na dysku**: prosty file-browser oparty o `GET /api/context/browse?path=...`. Pokazuje strukturę folderów pod `mount_point`. User klika folder żeby wejść głębiej, ma przyciski:
   - **"Wybierz ten folder"** — ustawia kontekst na bieżącą ścieżkę
   - **"Utwórz nowy folder"** — modal z nazwą, wywołuje `mkdir`, po sukcesie wybiera nowy folder
   - **"Wróć"** — w górę po drzewie (do mount_point)
3. **Step 3 — potwierdzenie**: pokazuje wybrany dysk + ścieżkę, free space, przycisk "Zatwierdź i przejdź dalej". Po POST `/api/context` redirect na `/dashboard`.

Komponent: rozbij `disk-setup.component.ts` na sub-komponenty:
- `disk-picker.component.ts`
- `path-picker.component.ts`
- `context-confirm.component.ts`
- Parent `setup-wizard-flow.component.ts` (osobny od istniejącego `setup-wizard` który dotyczy organizacji zdjęć — uwaga na nazewnictwo, najlepiej zmienić istniejący na `organize-wizard`).

### Dashboard: "Active Context Card" jako jedyna interaktywna sekcja gdy brak kontekstu

W `dashboard.component.html` na samej górze, **przed** `app-device-status-panel`, dodaj `app-active-context-card`. Logika:

- **Brak kontekstu** (`!appContextService.hasContext()`): karta zajmuje całą szerokość, wszystkie inne sekcje (`device-status-panel`, `sync-section`, `accounts-panel`) renderowane z `[class.disabled]="true"` i overlay z napisem "Wybierz dysk i folder docelowy aby aktywować aplikację" + przycisk "Skonfiguruj kontekst" → `/setup`.
- **Kontekst aktywny**: karta pokazuje:
  - Label dysku + ścieżkę względną
  - Free space (z odświeżaniem co 30s)
  - Liczbę zdjęć w kontekście (`GET /api/photos/count`)
  - Przycisk **"Zmień dysk"** → `/setup` (skok do step 1)
  - Przycisk **"Zmień folder"** → `/setup?step=path` (skok do step 2 z aktualnym dyskiem)
- **Kontekst degraded** (dysk odłączony): czerwone obramowanie + "Dysk niedostępny" + przycisk "Podłącz ponownie".

Plik: `frontend/src/app/features/dashboard/active-context-card/active-context-card.component.ts`

### Wizualne wyłączenie sekcji bez kontekstu

Globalny CSS klasa `.requires-context-disabled`:
```scss
.requires-context-disabled {
  position: relative;
  pointer-events: none;
  opacity: 0.4;
  filter: grayscale(0.5);
  &::after {
    content: 'Wymaga aktywnego kontekstu';
    position: absolute; inset: 0;
    display: flex; align-items: center; justify-content: center;
    background: rgba(255,255,255,0.6);
    font-weight: 600;
    pointer-events: auto;
  }
}
```

W `dashboard.component.html`:
```html
<app-active-context-card />
@if (!ctx.hasContext()) {
  <div class="requires-context-disabled">
    <app-device-status-panel />
    <app-sync-section />
    <app-accounts-panel ... />
  </div>
} @else {
  <app-device-status-panel />
  <app-sync-section />
  <app-accounts-panel ... />
}
```

### Zmiany w istniejących komponentach

- **`sync-section.component.ts`**: `canStart` dodaje warunek `appContextService.hasContext()`. Tekst "Dysk docelowy" pobiera z kontekstu (`ctx.basePath`) zamiast z `DiskSetupService.getStatus()` (które pokazuje tylko mount_point bez subfoldera).
- **`photos.component.ts`** i serwisy zdjęć: nic nie trzeba zmieniać poza tym że backend zwraca pustą listę / 409 jeśli brak kontekstu — guard przekieruje wcześniej.
- **`account.service.ts`**: jeśli backend wymusza kontekst dla logowania, dodaj obsługę 409 (interceptor już to robi globalnie).
- **`auth.guard.ts`**: pozostaje, ale dodaj `appContextGuard` do tych samych tras (compose obu).

---

## Migracja istniejących danych

1. **Nowa migracja Flyway** `V{N}__app_context_and_photo_storage.sql`:
   - Tworzy tabelę `app_context` z pustym wierszem
   - Dodaje `storage_device_id` do `photos`, `folders` (jeśli jeszcze nie ma)
   - Backfill: jeśli istnieje dokładnie jeden rekord w `storage_devices`, przypisuje wszystkie istniejące `photos`/`folders` do niego, ale **NIE** ustawia automatycznie aktywnego kontekstu (user musi to zrobić świadomie po pierwszym uruchomieniu nowej wersji).
2. **Pierwszy start nowej wersji**: użytkownik zobaczy lock-screen, musi przejść `/setup` i wybrać dysk + ścieżkę. Po wyborze backend ustawi kontekst i wszystkie poprzednio dodane zdjęcia (przypisane w backfilcie do tego samego dysku) staną się dostępne.

---

## Edge cases

| Sytuacja | Zachowanie |
|----------|-----------|
| Dysk z kontekstu został odmontowany ręcznie (poza aplikacją) | `getActive()` zwraca z `degraded=true`, dashboard pokazuje czerwoną kartę, sync/photos zwracają 409 z innym kodem `CONTEXT_DRIVE_UNAVAILABLE`, frontend toast "Dysk niedostępny — podłącz ponownie lub wybierz inny" |
| `basePath` został usunięty z dysku | Tak samo jak wyżej, kod `CONTEXT_PATH_MISSING`, prompt do utworzenia folderu lub zmiany ścieżki |
| User zmienia kontekst gdy trwa sync | Backend odrzuca POST `/api/context` z 409 `SYNC_IN_PROGRESS`, UI pokazuje "Zatrzymaj synchronizację aby zmienić kontekst" |
| Dwa różne dyski mają ten sam `filesystem_uuid` (klonowanie) | Mało prawdopodobne, ale `setContext` waliduje tylko UUID — pierwsze podłączenie wygrywa, drugie nadpisuje `device_path`. Bez specjalnej obsługi w v1. |
| User wybiera ścieżkę poza mount_point (np. przez ręczny POST z złym body) | Backend odrzuca z 400 `INVALID_BASE_PATH` (path traversal protection) |
| Aktywny kontekst wskazuje dysk którego już nie ma w `storage_devices` (rekord usunięty) | `getActive()` zwraca null + log warning, kontekst jest faktycznie pusty |

---

## Kolejność implementacji

1. **Backend — schemat + serwis**
   - Migracja Flyway (`app_context`, `storage_device_id` w `photos`/`folders`)
   - `AppContext` DTO + `AppContextService` + `NoActiveContextException`
   - `AppContextController` z GET/POST/DELETE/browse/mkdir
   - Unit testy serwisu (walidacje path, lifecycle)

2. **Backend — wymuszenie kontekstu**
   - `requireActive()` w `SyncService`, `PhotoService`, `FolderService`
   - Repository: dodać filtry `WHERE storage_device_id = ?`
   - `GlobalExceptionHandler` mapuje wyjątek na 409
   - `DiskSetupService.unmount()` wywołuje `appContextService.clear()` warunkowo
   - Integration testy: sync/photos zwracają 409 bez kontekstu

3. **Frontend — service + guard + interceptor**
   - `AppContextService` z signalami
   - `provideAppInitializer` w `app.config.ts`
   - `appContextGuard` + `noContextInterceptor`
   - Dodać guard do istniejących tras

4. **Frontend — przebudowa /setup**
   - Rozbicie `disk-setup.component.ts` na step components
   - `path-picker.component.ts` z drzewem folderów
   - `context-confirm.component.ts`
   - Parent flow component zarządzający stepami

5. **Frontend — dashboard**
   - `active-context-card.component.ts`
   - Klasa `.requires-context-disabled` w globalnych stylach
   - Zmiana `dashboard.component.html` żeby zawijała sekcje
   - Aktualizacja `sync-section` żeby pokazywał `basePath` zamiast `mountPoint`

6. **E2E sanity check**
   - Świeży start → lock screen → wybór dysku → wybór ścieżki → dashboard pokazuje kontekst → sync działa
   - Restart aplikacji → kontekst persystuje
   - Ręczny `umount` na hoście → degraded state pojawia się w max 30s
   - "Zmień dysk" z dashboardu → wraca do step 1, po wyborze nowego dysku stary kontekst zostaje skasowany

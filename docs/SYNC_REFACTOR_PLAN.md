## STATUS: IMPLEMENTED
# Plan: Refaktor SyncService + SSE Progress

## Cel

Zmiana synchronizacji z blokującej, sekwencyjnej procedury na trójfazowy,
asynchroniczny pipeline z real-time progress przez Server-Sent Events.

---

## Diagnoza obecnego stanu

### Backend (Java)

| Problem | Gdzie |
|---|---|
| `syncFromICloud` blokuje wątek HTTP do zakończenia wszystkich pobrań | `SyncService.java:58` |
| Brak modelu stanu synchronizacji | - |
| `listPhotos` zwraca `Map<String,Object>` — brak typów | `ICloudServiceClient.java:37` |
| Brak SSE | - |
| Brak porównania plików na dysku przed pobraniem | `SyncService.java:74` |

### icloud-service (Python) — KRYTYCZNE

| Problem | Gdzie |
|---|---|
| `async def` handlery wywołują **synchroniczny** `icloudpy` bezpośrednio — **blokuje event loop** | `photos.py:11,21,31` |
| `get_photos` iteruje cały album przy każdym wywołaniu (lazy iterator = seryjne HTTP do Apple) | `photo_service.py:17` |
| `_find_photo` iteruje O(n) całego albumu dla każdego pobrania (10k fotek = 10k iteracji per download) | `photo_service.py:115` |
| Brak cachowania metadanych — każde wywołanie zaczyna od nowa | `photo_service.py:17` |
| `download_photo_stream` też blokujące I/O na event loop | `photo_service.py:62` |

**Efekt:** 200 zdjęć = ~8-9 sek. Podczas tej iteracji event loop jest zablokowany —
żadne inne żądania (thumbnails, status, health) nie mogą być obsłużone.

---

## Architektura po refaktorze

```
POST /api/sync/{accountId}
       │
       ▼
   SyncService.startSync()
       │
       ├─► Emituj SSE: {phase: "FETCHING_METADATA", fetched: 0}
       │
       │  [FAZA 0 — icloud-service background]
       ├─► POST /photos/prefetch (icloud-service)
       │       │  zwraca natychmiast {"status": "fetching"}
       │       └─► asyncio.create_task → iteruje album w tle, wypełnia cache
       │
       │  [Backend odpowiada użytkownikowi natychmiast]
       ├─► Zwróć SyncStartResponse {status: "FETCHING_METADATA"}
       │
       │  [Backend polluje w tle (virtual thread, ~1s)]
       │  GET /photos/prefetch/status → {fetched: N, total: M}
       │       │  Emituj SSE z postępem metadanych
       │       └─► gdy status == "ready" → FAZA 1
       │
   ┌───┴────────────────────────────────┐
   │  FAZA 1 (~ms, gdy metadata gotowa) │
   │  1. GET /photos z cache (szybko)   │
   │  2. Wczytaj pliki z dysku          │
   │  3. Diff → nowe wpisy do DB        │
   │     (syncStatus = PENDING)         │
   │  4. Emituj SSE: totals             │
   └────────────────────────────────────┘
       │
       │  [Faza 2 — parallel downloads, virtual threads]
       ▼
   SyncDownloadWorker
   ┌───────────────────────────────────────────┐
   │  FAZA 2 (w tle, równoległa)               │
   │  Semaphore(10) — max 10 równoległych req  │
   │  Dla każdego PENDING foto:                │
   │    - CompletableFuture + virtual thread   │
   │    - GET /photos/{id} (streaming)         │
   │    - Zapisz na dysk                       │
   │    - syncStatus = SYNCED/FAILED           │
   │    - Emituj SyncProgressEvent             │
   └───────────────────────────────────────────┘

GET /api/sync/{accountId}/events   ← SSE stream (wszystkie fazy)
GET /api/sync/{accountId}/status   ← snapshot JSON
```

---

## CZĘŚĆ A — icloud-service (Python)

### A1. Naprawa blokowania event loop

**Problem:** `async def get_photos(...)` wywołuje `photo_service.get_photos(...)` synchronicznie.
`icloudpy` używa `requests` (sync HTTP) pod spodem.

**Fix:** Wszystkie blokujące operacje icloudpy w `asyncio.to_thread()`:

```python
# photos.py — PRZED (blokuje event loop!):
@router.get("")
async def get_photos(x_session_id: str = Header(...)):
    return photo_service.get_photos(x_session_id, limit=limit, offset=offset)

# photos.py — PO:
@router.get("")
async def get_photos(x_session_id: str = Header(...)):
    return await asyncio.to_thread(photo_service.get_photos, x_session_id, limit, offset)
```

To samo dla `get_photo`, `get_thumbnail`, `delete_photo`.

### A2. Cache metadanych per session

Nowy moduł: `app/services/photo_cache.py`

```python
class PhotoCache:
    # session_id → {"status": "idle/fetching/ready", "photos": List[dict],
    #               "photo_index": dict[id → photo_obj],  # dla O(1) lookup
    #               "fetched": int, "total": int | None}
    _cache: dict[str, dict] = {}
    _locks: dict[str, asyncio.Lock] = {}   # lock per session dla album iteracji
```

**`status` lifecycle:** `idle` → `fetching` → `ready` | `error`

### A3. Nowe endpointy w `photos.py`

```python
# Uruchom prefetch w tle — zwraca natychmiast
@router.post("/prefetch")
async def prefetch_photos(x_session_id: str = Header(...)):
    asyncio.create_task(photo_cache.start_prefetch(x_session_id))
    return {"status": "fetching", "message": "Background fetch started"}

# Status prefetchu
@router.get("/prefetch/status")
async def prefetch_status(x_session_id: str = Header(...)):
    return photo_cache.get_status(x_session_id)
    # zwraca: {"status": "fetching/ready", "fetched": N, "total": N | null}
```

### A4. `PhotoCache.start_prefetch()` — background task

```python
async def start_prefetch(self, session_id: str):
    async with self._get_lock(session_id):   # jeden prefetch na raz per session
        state = self._cache[session_id]
        state["status"] = "fetching"
        
        # Uruchom blokującą iterację w osobnym OS thread
        await asyncio.to_thread(self._iterate_album, session_id)
        
        state["status"] = "ready"

def _iterate_album(self, session_id: str):
    """Sync — uruchamiany w OS thread przez asyncio.to_thread"""
    api = session_manager.get_api(session_id)
    state = self._cache[session_id]
    photos = []
    index = {}
    for photo in api.photos.all:
        d = self._photo_to_dict(photo)
        photos.append(d)
        index[photo.id] = photo   # zachowaj obiekt dla download
        state["fetched"] = len(photos)
    state["photos"] = photos
    state["photo_index"] = index
    state["total"] = len(photos)
```

### A5. `_find_photo` → O(1) z cache

```python
def _find_photo(self, session_id: str, photo_id: str):
    # PRZED: iteracja O(n) całego albumu
    # PO: O(1) lookup z cache
    index = photo_cache.get_index(session_id)
    if index and photo_id in index:
        return index[photo_id]
    raise PhotoNotFoundException(photo_id)
```

### A6. Równoległość pobrań w icloud-service

Każde `GET /photos/{id}` już teraz może działać równolegle bo:
- `async def get_photo(...)` wywołujemy z `asyncio.to_thread(photo_service.download_photo_stream, ...)`
- Każde wywołanie dostaje własny OS thread
- `photo.download()` to niezależne HTTP GET do CDN Apple — brak shared state

**Limit:** Semaphore na poziomie backendu (patrz Faza 2) — nie w icloud-service.
icloud-service nie musi wiedzieć o limicie, obsługuje każdy request normalnie.

---

## CZĘŚĆ B — Backend (Java/Micronaut)

### B1. Nowe modele

#### `ICloudPhotoAsset` (DTO)
```
model/dto/ICloudPhotoAsset.java
```
Pola: `String id`, `String filename`, `Long size`, `Instant createdDate`,
`Integer width`, `Integer height`, `String assetToken`

#### `ICloudPhotoListResponse` (DTO)
```
model/dto/ICloudPhotoListResponse.java
```
Pola: `List<ICloudPhotoAsset> photos`, `int total`

#### `ICloudPrefetchStatus` (DTO)
```
model/dto/ICloudPrefetchStatus.java
```
Pola: `String status` (fetching/ready/error), `int fetched`, `Integer total`

#### `SyncStatus` (enum)
```
model/enums/SyncStatus.java
```
Wartości: `PENDING`, `DOWNLOADING`, `SYNCED`, `FAILED`

#### `SyncPhase` (enum)
```
model/enums/SyncPhase.java
```
Wartości: `FETCHING_METADATA`, `COMPARING`, `DOWNLOADING`, `DONE`, `ERROR`

#### `SyncProgressEvent` (DTO — SSE payload)
```
model/dto/SyncProgressEvent.java
```
Pola: `String accountId`, `SyncPhase phase`, `int totalOnCloud`,
`int synced`, `int failed`, `int pending`, `int metadataFetched`,
`double percentComplete`, `String currentFile`, `Instant timestamp`

#### `SyncStartResponse` (DTO)
```
model/dto/SyncStartResponse.java
```
Pola: `String accountId`, `SyncPhase phase`, `String message`, `Instant startedAt`

### B2. Aktualizacja istniejących modeli

**`Photo`** — nowe pola:
```java
private String syncStatus;   // SyncStatus enum jako String
private String assetToken;
```

**`PhotoRepository`** — nowe metody:
```java
List<Photo> findByAccountIdAndSyncStatus(String accountId, String syncStatus);
long countByAccountIdAndSyncStatus(String accountId, String syncStatus);
```

**`ICloudServiceClient`** — nowe/zaktualizowane metody:
```java
// zmiana: typowany response
HttpResponse<ICloudPhotoListResponse> listPhotos(
    @Header("X-Session-ID") String sessionId,
    @QueryValue int limit, @QueryValue int offset);

// nowe:
@Post("/photos/prefetch")
HttpResponse<Map<String, Object>> prefetchPhotos(
    @Header("X-Session-ID") String sessionId);

@Get("/photos/prefetch/status")
HttpResponse<ICloudPrefetchStatus> getPrefetchStatus(
    @Header("X-Session-ID") String sessionId);
```

### B3. `SyncStateHolder` (singleton, in-memory)

```java
@Singleton
public class SyncStateHolder {
    private final ConcurrentHashMap<String, SyncProgressEvent> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<FluxSink<SyncProgressEvent>>> sinks = new ConcurrentHashMap<>();

    public void updateAndEmit(String accountId, SyncProgressEvent event) {
        states.put(accountId, event);
        List<FluxSink<SyncProgressEvent>> accountSinks = sinks.getOrDefault(accountId, List.of());
        accountSinks.forEach(sink -> sink.next(event));
    }

    public Publisher<SyncProgressEvent> subscribe(String accountId) {
        return Flux.create(sink -> {
            sinks.computeIfAbsent(accountId, k -> new CopyOnWriteArrayList<>()).add(sink);
            sink.onDispose(() -> sinks.getOrDefault(accountId, List.of()).remove(sink));
            // Wyślij od razu aktualny snapshot
            Optional.ofNullable(states.get(accountId)).ifPresent(sink::next);
        });
    }

    public Optional<SyncProgressEvent> getSnapshot(String accountId) {
        return Optional.ofNullable(states.get(accountId));
    }
}
```

### B4. `AppConfig` — ExecutorService i Semaphore

```java
// virtual threads — do I/O blokującego (downloads, polling)
@Bean @Named("syncVirtualThreadExecutor")
ExecutorService syncVirtualThreadExecutor() {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("sync-vt-", 0).factory());
}
```

Dla ograniczenia równoległości pobrań używamy `Semaphore(10)` w `SyncService`:
```java
private final Semaphore downloadSemaphore = new Semaphore(10);
```
Virtual thread acquire'uje semafor → czeka bez blokowania OS thread.

### B5. Refaktor `SyncService`

#### `startSync(accountId)` → `SyncStartResponse`

```
1. Wczytaj account z DB, sprawdź dysk
2. Emituj SSE: {phase: FETCHING_METADATA, message: "Pobieranie listy z iCloud..."}
3. Wywołaj POST /photos/prefetch (icloud-service) → zwraca natychmiast
4. Uruchom pollMetadataAndContinue(accountId) jako CompletableFuture (virtual thread)
5. Zwróć SyncStartResponse {phase: FETCHING_METADATA} — użytkownik dostaje odpowiedź
```

#### `pollMetadataAndContinue(accountId)` — w tle

```
Pętla co ~1 sek:
    GET /photos/prefetch/status
    Emituj SSE: {phase: FETCHING_METADATA, metadataFetched: N, total: M}
    Jeśli status == "ready" → przerwij pętlę → wywołaj compareAndPersist(accountId)
```

#### `compareAndPersist(accountId)`

```
1. GET /photos (z cache — teraz szybko) → List<ICloudPhotoAsset>
2. Files.list(destDir) → Set<String> diskFiles
3. Pobierz z DB Set<String> znanych icloudPhotoId ze statusem SYNCED
4. Diff: iCloudPhotos gdzie id NIE w DB-synced AND filename NIE w diskFiles
5. Batch insert nowych Photo z syncStatus = PENDING
6. Emituj SSE: {phase: COMPARING, total: N, pending: P, alreadySynced: S}
7. Uruchom downloadPendingPhotosAsync(accountId)
```

#### `downloadPendingPhotosAsync(accountId)` — parallel downloads

```java
List<Photo> pending = photoRepository.findByAccountIdAndSyncStatus(accountId, "PENDING");

List<CompletableFuture<Void>> futures = pending.stream()
    .map(photo -> CompletableFuture.runAsync(() -> {
        downloadSemaphore.acquire();   // max 10 równoległych
        try {
            downloadOne(photo, account);
        } finally {
            downloadSemaphore.release();
        }
    }, syncVirtualThreadExecutor))
    .toList();

// Nie czekamy — każdy future sam emituje events po zakończeniu
```

#### `downloadOne(photo, account)`

```
1. photo.syncStatus = DOWNLOADING → DB update
2. Emituj SSE event
3. Pobierz stream: iCloudServiceClient.downloadPhoto(assetToken, sessionId)
4. Zapisz na dysk (streaming, nie ładuj całości do pamięci)
5. photo.syncStatus = SYNCED, syncedToDisk = true → DB update
6. Przelicz i emituj SSE: {synced++, pending--, percentComplete}
```

**Streaming zapis na dysk** (zamiast `byte[]`):
```java
// ICloudServiceClient — zmienić na ReactiveStreamingHttpClient dla dużych plików
// LUB: obecny byte[] jest OK dla typowych zdjęć (do ~50MB)
```

### B6. `SyncController` (nowy)

```java
@Controller("/api/sync")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class SyncController {

    // Trigger — zwraca natychmiast
    @Post("/{accountId}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    SyncStartResponse startSync(@PathVariable String accountId);

    // SSE stream
    @Get(value = "/{accountId}/events", produces = MediaType.TEXT_EVENT_STREAM)
    Publisher<Event<SyncProgressEvent>> syncEvents(@PathVariable String accountId);

    // Snapshot
    @Get("/{accountId}/status")
    Optional<SyncProgressEvent> getSyncStatus(@PathVariable String accountId);
}
```

---

## CZĘŚĆ C — Frontend (Angular)

### C1. Modele TypeScript

```typescript
// core/models/sync-progress.model.tsPlan zaktualizowany w SYNC_REFACTOR_PLAN.md.



export type SyncPhase =
  | 'FETCHING_METADATA' | 'COMPARING' | 'DOWNLOADING' | 'DONE' | 'ERROR';

export interface SyncProgressEvent {
  accountId: string;
  phase: SyncPhase;
  totalOnCloud: number;        // znana liczba zdjęć w iCloud
  metadataFetched: number;     // ile metadanych pobrano (faza 0)
  synced: number;
  failed: number;
  pending: number;
  percentComplete: number;
  currentFile?: string;
  timestamp: string;
}

export interface SyncStartResponse {
  accountId: string;
  phase: SyncPhase;
  message: string;
  startedAt: string;
}
```

### C2. `SyncService` (Angular)

```typescript
@Injectable({ providedIn: 'root' })
export class SyncService {
  private eventSource: EventSource | null = null;
  syncProgress$ = new BehaviorSubject<SyncProgressEvent | null>(null);

  startSync(accountId: string): Observable<SyncStartResponse> {
    return this.http.post<SyncStartResponse>(`/api/sync/${accountId}`, {}).pipe(
      tap(() => this.subscribeToEvents(accountId))
    );
  }

  private subscribeToEvents(accountId: string): void {
    this.closeEvents();
    // EventSource nie obsługuje nagłówków — token w cookie lub query param
    this.eventSource = new EventSource(`/api/sync/${accountId}/events?token=${this.authService.token}`);
    this.eventSource.onmessage = (e) => {
      const event: SyncProgressEvent = JSON.parse(e.data);
      this.syncProgress$.next(event);
      if (event.phase === 'DONE' || event.phase === 'ERROR') {
        this.closeEvents();
      }
    };
  }

  closeEvents(): void {
    this.eventSource?.close();
    this.eventSource = null;
  }
}
```

### C3. Komponent `sync-progress`

```
features/sync-progress/sync-progress.component.ts
```

UI per faza:

| Faza | Wyświetlane |
|---|---|
| `FETCHING_METADATA` | Spinner + "Pobieranie listy z iCloud: 1234 / ..." |
| `COMPARING` | "Znaleziono X nowych zdjęć, Y już zsynchronizowanych" |
| `DOWNLOADING` | Pasek postępu `synced/total`, nazwa pliku, `failed` jeśli > 0 |
| `DONE` | "Synchronizacja zakończona: X pobranych, Y pominiętych, Z błędów" |

```typescript
get progressPercent(): number {
  const e = this.event;
  if (!e) return 0;
  if (e.phase === 'FETCHING_METADATA' && e.totalOnCloud > 0)
    return Math.round((e.metadataFetched / e.totalOnCloud) * 100);
  if (e.totalOnCloud > 0)
    return Math.round((e.synced / e.totalOnCloud) * 100);
  return 0;
}
```

---

## Kolejność implementacji

```
── icloud-service ──
1.  Naprawić blokowanie event loop: asyncio.to_thread() we wszystkich handlerach
2.  PhotoCache singleton (status, photos list, photo_index)
3.  start_prefetch() background task + _iterate_album() w thread
4.  Nowe endpointy: POST /photos/prefetch, GET /photos/prefetch/status
5.  _find_photo() → O(1) z cache

── backend (Java) ──
6.  Modele: ICloudPhotoAsset, ICloudPrefetchStatus, SyncStatus, SyncPhase,
           SyncProgressEvent, SyncStartResponse
7.  Photo entity: +syncStatus, +assetToken + migracja DB
8.  PhotoRepository: +findByAccountIdAndSyncStatus, +countByAccountIdAndSyncStatus
9.  ICloudServiceClient: typowany listPhotos + prefetch endpoints
10. SyncStateHolder
11. AppConfig: virtualThreadExecutor bean
12. Refaktor SyncService:
      startSync → pollMetadataAndContinue → compareAndPersist → downloadPendingPhotosAsync
13. SyncController (POST, GET SSE, GET status)
14. Usunąć stary POST /api/photos/sync z PhotoController

── frontend ──
15. Modele TypeScript (sync-progress.model.ts)
16. SyncService Angular (startSync + EventSource)
17. Komponent sync-progress
18. Integracja w dashboard/photos (przycisk sync + panel progress)
```

## Kwestie otwarte (decyzja)

| Kwestia | Opcja A | Opcja B |
|---|---|---|
| Limit równoległych pobrań | `Semaphore(10)` w backend | Konfigurowalne przez `application.yml` |
| SSE auth | JWT token w query param `?token=` | Cookie (wymaga CORS config) |
| Persystencja stanu sync | Only in-memory (`SyncStateHolder`) | Tabela `sync_sessions` w DB (resume po restarcie) |
| icloud-service cache invalidation | TTL (np. 30min) | Manualne wywołanie prefetch |
| Streaming download na dysk | `byte[]` w pamięci (OK do ~50MB) | `InputStream` streaming bezpośrednio na dysk |
| icloudpy thread-safety podczas prefetch | `asyncio.Lock` per session (jeden prefetch na raz) | Brak problemu gdy jeden użytkownik |

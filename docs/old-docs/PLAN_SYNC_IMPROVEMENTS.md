# Plan ulepszeń synchronizacji

## Problem 1 — "Already completed" (Netty/SSE)

**Diagnoza:**  
`SyncStateHolder.updateAndEmit()` iteruje po `List<FluxSink>` i woła `sink.next(event)`.
Gdy klient SSE rozłączy się, Reactor zaznacza sink jako `disposed`, ale lista nadal go trzyma —
następna emisja trafia do już zamkniętego sink'a i Netty rzuca `IllegalStateException: Already completed`.
`onDispose` usuwa sink, ale race condition (emisja w jednym wątku, dispose w innym) może ją przegapić.

**Rozwiązanie:**  
W `SyncStateHolder.updateAndEmit()` zamienić `forEach` na `removeIf` z try-catch:

```java
sinks.getOrDefault(accountId, List.of()).removeIf(sink -> {
    try {
        sink.next(event);
        return false;        // sink żywy, zostaw go
    } catch (Exception e) {
        return true;         // sink martwy, usuń go z listy
    }
});
```

Pliki do zmiany: `SyncStateHolder.java`

---

## Problem 2 — Photo ID ze znakami specjalnymi (`/`, `+`)

**Diagnoza:**  
iCloud zwraca ID zdjęć takie jak `AcRlTWGhWL/BPAQigf9xLBUnOn9M` lub `AcboO9sbr9vTW+Aq7q+bnd8rXU/K`.
Backend wywołuje `iCloudServiceClient.downloadPhoto(photoId, sessionId)`, gdzie:

```java
@Get("/photos/{photoId}")
HttpResponse<byte[]> downloadPhoto(@PathVariable String photoId, ...);
```

Micronaut buduje URL jako `/photos/AcRlTWGhWL/BPAQigf9xLBUnOn9M` — ukośnik dzieli ścieżkę na dwa segmenty.
FastAPI routuje to do endpointu `/photos/{photo_id}` tylko jeśli ścieżka ma jeden segment → 404.  
Analogiczny problem z `thumbnail` i `delete`.

**Rozwiązanie:**  
Zmienić wszystkie trzy endpointy z `@PathVariable photoId` na `@QueryValue photoId`:

*icloud-service `photos.py`* — dodać odpowiednie query-parametry:
```python
# Nowe endpointy (stare /{photo_id} zostaną usunięte)
@router.get("/download")
async def get_photo(photo_id: str = Query(...), ...):
    ...

@router.get("/thumbnail")
async def get_thumbnail(photo_id: str = Query(...), size: int = Query(256), ...):
    ...

@router.delete("/delete")
async def delete_photo(photo_id: str = Query(...), ...):
    ...
```

*Backend `ICloudServiceClient.java`*:
```java
@Get("/photos/download")
HttpResponse<byte[]> downloadPhoto(
        @QueryValue String photoId,
        @Header("X-Session-ID") String sessionId);

@Get("/photos/thumbnail")
HttpResponse<byte[]> downloadThumbnail(
        @QueryValue String photoId,
        @Header("X-Session-ID") String sessionId,
        @QueryValue(defaultValue = "256") int size);

@Delete("/photos/delete")
HttpResponse<Map<String, Object>> deletePhoto(
        @QueryValue String photoId,
        @Header("X-Session-ID") String sessionId);
```

Micronaut automatycznie URL-enkoduje wartości query param → `/` staje się `%2F`, `+` staje się `%2B`.
FastAPI automatycznie dekoduje query param → photo_service dostaje oryginalny ID.

Pliki do zmiany: `ICloudServiceClient.java`, `icloud-service/app/routers/photos.py`

---

## Problem 3 — Frontend zbyt reaktywny

**Diagnoza:**  
`emitDownloadProgress()` woła `syncStateHolder.updateAndEmit()` po każdym pobranym lub nieudanym pliku.
Przy 1000 zdjęć to 1000 SSE events → UI re-renderuje się 1000 razy, nerwowo miga licznik i nazwa pliku.

**Rozwiązanie — dwie warstwy:**

### 3a. Backend — throttling emisji

W `SyncService` dodać licznik pobranych od ostatniej emisji i emitować co 5 plików LUB co 3 sekundy:

```java
private final ConcurrentHashMap<String, Instant> lastEmitTime = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, AtomicInteger> downloadedSinceEmit = new ConcurrentHashMap<>();
private static final int EMIT_EVERY_N = 5;
private static final Duration EMIT_MAX_INTERVAL = Duration.ofSeconds(3);

private void maybeEmitDownloadProgress(String accountId) {
    int count = downloadedSinceEmit.computeIfAbsent(accountId, k -> new AtomicInteger()).incrementAndGet();
    Instant last = lastEmitTime.getOrDefault(accountId, Instant.EPOCH);
    if (count >= EMIT_EVERY_N || Duration.between(last, Instant.now()).compareTo(EMIT_MAX_INTERVAL) > 0) {
        downloadedSinceEmit.get(accountId).set(0);
        lastEmitTime.put(accountId, Instant.now());
        emitDownloadProgress(accountId);
    }
}
```

`downloadOne` i `markFailed` wołają `maybeEmitDownloadProgress` zamiast `emitDownloadProgress`.
Przy DONE zawsze emitujemy finalny event normalnie.

Usunąć pole `currentFile` z emitowanych eventów w fazie DOWNLOADING (nie ma sensu przy throttlingu).

### 3b. Backend — lista nieudanych plików

Dodać do `SyncProgressEvent` pole `List<String> failedFiles` (nazwy plików z błędem).
Przy DONE: zapytać `photoRepository.findByAccountIdAndSyncStatus(FAILED)` i wypełnić pole.

Alternatywnie (prostsze): frontend po DONE wywołuje `GET /api/photos?syncStatus=FAILED&accountId=X`
i wyświetla listę. **Wybieram tę opcję** — nie zaśmieca SSE eventu.

### 3c. Frontend — spokojniejszy UI

W `sync-section.component.ts`:
- Usunąć animację `smoothMetadataFetched` (zastąpić zwykłym odczytem, animacja generuje `setInterval`)
- Wyświetlać tylko: liczbę pobranych, liczbę błędów, pasek postępu — bez nazwy aktualnego pliku
- Po fazie DONE: jeśli `failed > 0` → `PhotoService.listPhotos({ syncStatus: 'FAILED' })`
  i wyświetlić collapsible listę nieudanych plików z nazwą i przycikiem "spróbuj ponownie"

Pliki do zmiany:
- `SyncService.java` (throttling, lista failedów przy DONE)
- `SyncProgressEvent.java` (ewentualnie dodać `failedFiles`)
- `sync-section.component.ts` (usunąć animację, dodać sekcję failed)

---

## Problem 4 — Segregowanie zdjęć rok/miesiąc

**Diagnoza:**  
Aktualnie wszystkie zdjęcia lądują płasko w `ctx.basePath()`. Przy tysiącach plików jest to nieużyteczne.

**Rozwiązanie:**

### 4a. Przy pobieraniu (backend)

W `SyncService.downloadOne()` zamiast:
```java
Path destDir = Path.of(ctx.basePath());
```
użyć:
```java
Path destDir = resolveDestDir(ctx.basePath(), photo.getCreatedDate());
```

Gdzie:
```java
private Path resolveDestDir(String basePath, Instant createdDate) {
    if (createdDate == null) {
        return Path.of(basePath, "unknown");
    }
    LocalDate date = createdDate.atZone(ZoneId.systemDefault()).toLocalDate();
    return Path.of(basePath,
            String.valueOf(date.getYear()),
            String.format("%02d", date.getMonthValue()));
}
```

Przykład: `basePath/2025/01/IMG_1234.jpg`

`photo.setFilePath(destFile.toString())` zapisuje pełną ścieżkę z podkatalogami — OK.

`scanDiskFiles` musi być rekurencyjne (`Files.walk`) zamiast `Files.list` (jeden poziom):
```java
try (var stream = Files.walk(dir)) {
    stream.filter(Files::isRegularFile)
          .forEach(p -> { ... });
}
```
Inaczej zdjęcia już posegregowane nie będą rozpoznane jako istniejące przy następnej synchronizacji.


## Problem 5 — Wykrywanie i reorganizacja istniejących zdjęć

**Scenariusz:** Użytkownik miał już zdjęcia pobrane płasko (bez rok/miesiąc). Po włączeniu segregacji
nowe zdjęcia lądują w podkatalogach, ale stare leżą w korzeniu.

**Rozwiązanie — nowa funkcja "Reorganizuj":**

### 5a. Backend — podgląd

```
GET /api/sync/{accountId}/reorganize-preview
```
Odpowiedź:
```json
{
  "unorganizedCount": 142,
  "samples": ["IMG_1234.jpg", "IMG_1235.jpg", "..."],
  "estimatedFolders": ["2024/11", "2024/12", "2025/01"]
}
```

Logika: przejść przez `ctx.basePath()` (`Files.walk`), zebrać pliki bezpośrednio w korzeniu
(głębokość 1) lub w folderach niezgodnych z formatem `\d{4}/\d{2}`.
Porównać z `photo.getCreatedDate()` z bazy by ustalić folder docelowy.

### 5b. Backend — wykonanie

```
POST /api/sync/{accountId}/reorganize
```

Dla każdego zdjęcia w bazie z `syncedToDisk=true` i `filePath` wskazującym na plik poza
`rok/miesiąc` strukturą:
1. Wyznaczyć `destDir = resolveDestDir(basePath, photo.getCreatedDate())`
2. `Files.createDirectories(destDir)`
3. `Files.move(currentPath, destPath)`
4. Zaktualizować `photo.setFilePath(destPath)`

Rob to w batchach pamietaj.

Emitować postęp przez SSE (nowa faza `REORGANIZING` lub prosty licznik w odpowiedzi HTTP).

### 5c. Frontend — monit po DONE

Witold wlasny wpis: tutaj moze zrob dodatkowy komponent pod synchronized- ze sa nieposegregowane pliki i czy chcesz je segregowac. To nie musi byc zwiazane z synchronizacja. Moze byc przed. 

Po odebraniu fazy `DONE`:
1. Wywołać `GET /api/sync/{accountId}/reorganize-preview`
2. Jeśli `unorganizedCount > 0`:
   - Pokazać dialog/baner: "Znaleziono X zdjęć poza strukturą rok/miesiąc. Czy chcesz je posegregować?"
   - Przyciski: "Tak, posegreguj" → `POST /api/sync/{accountId}/reorganize` | "Nie teraz"

Pliki do dodania/zmiany:
- `SyncController.java` (2 nowe endpointy)
- `SyncService.java` (2 nowe metody + helper `resolveDestDir`)  
- `sync-section.component.ts` (monit po DONE)
- `sync.service.ts` (`reorganizePreview()`, `reorganize()`)

---

## Kolejność implementacji

| # | Co | Ryzyko | Priorytet |
|---|---|---|---|
| 1 | `Already completed` — fix SyncStateHolder | Niski | Krytyczny |
| 2 | Photo ID special chars — query params | Średni (zmiana API) | Krytyczny |
| 3a | Throttling emisji (backend) | Niski | Wysoki |
| 4 | Segregacja rok/miesiąc + rekurencyjny scan | Średni | Wysoki |
| 3b/3c | Spokojniejszy frontend + lista failed | Niski | Średni |
| 5 | Reorganizacja istniejących zdjęć | Średni | Niski |

Problemy 1 i 2 powinny być zrobione razem — razem powodują brak pobrań i zaśmiecają logi.
Problem 4 (zmiana `scanDiskFiles` na rekurencyjny) musi iść razem ze zmianą ścieżki zapisu,
inaczej kolejna synchronizacja będzie ponownie uznawać posegregowane zdjęcia za brakujące.

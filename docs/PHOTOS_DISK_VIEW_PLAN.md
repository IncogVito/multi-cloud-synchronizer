# Plan: Widok zdjęć oparty o dysk (bez wyboru konta)

## Motywacja

Aktualny widok Photos wymaga wyboru konta iCloud przed załadowaniem zdjęć.
To nie ma sensu — użytkownik jest już w kontekście zamontowanego dysku.
Zdjęcia mają być wyświetlane z tego co **faktycznie jest na dysku**,
pogrupowane wg roku i miesiąca, z możliwością wygenerowania brakujących miniatur.

---

## Zakres zmian

### Co zostaje:
- Grupowanie timeline po roku/miesiącu (`photo-timeline.component`)
- Wyświetlanie miniatur (blob loading)
- Modal szczegółu zdjęcia
- Zaznaczanie i usuwanie zdjęć

### Co się zmienia:
- **Usunięcie wyboru konta** z toolbar / Photos component
- **Nowe źródło danych**: zdjęcia synced do dysku (`syncedToDisk = true`), opcjonalnie filtrowane po `storageDeviceId` aktywnego dysku
- **Infinite scroll** zamiast przycisku "Load More"
- **Baner "Generuj brakujące podglądy"** gdy są zdjęcia bez miniatur
- **Backend**: nowy endpoint do zliczania brakujących miniatur + triggerowanie ich generacji

---

## B1. Backend — zmiany

### B1.1 `GET /api/photos` — rozszerzenie filtrowania

Dodaj opcjonalny query param `storageDeviceId`. Gdy podany — filtruje tylko zdjęcia przypisane do danego dysku.

```
GET /api/photos?synced=true&storageDeviceId=<uuid>&page=0&size=50
```

Zmiana w `PhotoRepository`:
```java
Page<Photo> findBySyncedToDiskAndStorageDeviceId(
    boolean syncedToDisk, String storageDeviceId, Pageable pageable);
```

Zmiana w `PhotoService.listPhotos()` — dodaj gałąź obsługującą `storageDeviceId`.

### B1.2 `GET /api/photos/missing-thumbnails-count`

Nowy endpoint — zlicza zdjęcia na dysku bez wygenerowanej miniatury.

```json
{ "count": 47 }
```

Logika: `syncedToDisk = true AND (thumbnailPath IS NULL OR thumbnailPath = '')`

Nowe zapytanie w `PhotoRepository`:
```java
@Query("SELECT COUNT(*) FROM photos WHERE synced_to_disk = true AND (thumbnail_path IS NULL OR thumbnail_path = '')")
long countMissingThumbnails();
```

Opcjonalnie z `storageDeviceId`:
```java
@Query("SELECT COUNT(*) FROM photos WHERE synced_to_disk = true AND storage_device_id = :storageDeviceId AND (thumbnail_path IS NULL OR thumbnail_path = '')")
long countMissingThumbnailsByDevice(String storageDeviceId);
```

### B1.3 `POST /api/photos/generate-thumbnails`

Triggeruje asynchroniczne generowanie brakujących miniatur z SSE progressem.

**Request** (opcjonalny body):
```json
{ "storageDeviceId": "uuid" }
```

**Response**: SSE stream (jak sync), eventy:
```
data: {"processed": 5, "total": 47, "done": false}
data: {"processed": 47, "total": 47, "done": true, "errors": 2}
```

Implementacja w `ThumbnailService` — metoda `generateMissingForDevice(storageDeviceId, progressCallback)`:
1. Pobiera zdjęcia z dysku bez thumbnailPath
2. Wywołuje istniejące `generateThumbnail()` per zdjęcie
3. Emituje progress co każde zdjęcie

**Nowy controller**: `ThumbnailController` (lub dołączyć do `PhotoController`):
```java
@Get("/api/photos/missing-thumbnails-count")
MissingThumbnailsCount countMissing(@QueryValue(defaultValue = "") String storageDeviceId)

@Post(value = "/api/photos/generate-thumbnails", produces = MediaType.TEXT_EVENT_STREAM)
Publisher<ThumbnailProgress> generateThumbnails(@Body @Nullable GenerateThumbnailsRequest req)
```

Nowe DTO:
```java
record MissingThumbnailsCount(long count) {}
record GenerateThumbnailsRequest(@Nullable String storageDeviceId) {}
record ThumbnailProgress(int processed, int total, boolean done, int errors) {}
```

---

## F1. Frontend — zmiany w Photos component

### F1.1 Usunięcie wyboru konta

W `photos.component.ts`:
- Usunąć `selectedAccountId` signal i logikę ładowania kont
- Usunąć `AccountsService` z zależności
- Zamiast `accountId` w zapytaniach — użyć `storageDeviceId` z nowego `DiskContextService`
  (lub `StorageDevicesService` — serwis już istnieje, ma aktywny dysk)

W `photos-toolbar.component`:
- Usunąć dropdown wyboru konta
- Pozostawić filtr synced/all jeśli sensowny (lub uprościć)

### F1.2 Nowe źródło danych

```typescript
// photos.component.ts
private storageDeviceId = inject(DiskContextService).activeDeviceId; // signal

ngOnInit() {
  // zamiast czekać na wybór konta — od razu ładuj zdjęcia z dysku
  effect(() => {
    const deviceId = this.storageDeviceId();
    if (deviceId) {
      this.resetAndLoad();
    }
  });
}

loadPhotos() {
  this.photosService.listPhotos({
    synced: 'true',
    storageDeviceId: this.storageDeviceId(),
    page: this.currentPage(),
    size: 50
  }).subscribe(...);
}
```

### F1.3 Infinite scroll

Zastąpić "Load More" button infinite scrollem opartym na `IntersectionObserver`:

```typescript
// photos.component.ts
private setupInfiniteScroll() {
  const sentinel = this.sentinelRef.nativeElement;
  const observer = new IntersectionObserver(entries => {
    if (entries[0].isIntersecting && !this.loading() && this.hasMore()) {
      this.loadNextPage();
    }
  }, { threshold: 0.1 });
  observer.observe(sentinel);
  this.destroyRef.onDestroy(() => observer.disconnect());
}
```

W template — sentinel div na końcu listy:
```html
<div #sentinel class="scroll-sentinel"></div>
```

### F1.4 Baner brakujących miniatur

Nowy komponent `missing-thumbnails-banner.component`:

```html
@if (missingCount() > 0) {
  <div class="missing-thumbnails-banner">
    <span>{{ missingCount() }} zdjęć nie ma podglądu</span>
    @if (generating()) {
      <span>Generowanie... {{ progress().processed }}/{{ progress().total }}</span>
      <mat-progress-bar [value]="progressPercent()"></mat-progress-bar>
    } @else {
      <button mat-raised-button color="accent" (click)="generateThumbnails()">
        Wygeneruj brakujące podglądy
      </button>
    }
  </div>
}
```

Logika komponentu:
- `ngOnInit`: wywołuje `GET /api/photos/missing-thumbnails-count`
- `generateThumbnails()`: otwiera SSE stream `POST /api/photos/generate-thumbnails`
- Po zakończeniu: odświeża miniatury w galerii (emit eventu do parents)
- Po zakończeniu: ponownie sprawdza count (powinien być 0 lub mniejszy)

### F1.5 Nowy serwis `SetupWizardService` (już planowany) lub rozszerzenie `PhotosService`

Dodać do `PhotosService` (lub nowego `ThumbnailService`):
```typescript
getMissingThumbnailsCount(storageDeviceId?: string): Observable<MissingThumbnailsCount>
generateThumbnails(storageDeviceId?: string): Observable<ThumbnailProgress>
```

---

## F2. DiskContextService — skąd brać aktywny dysk

Sprawdzić czy już istnieje serwis trzymający aktywny dysk (storage device).
Jeśli tak — wstrzyknąć go w Photos component.
Jeśli nie — stworzyć `DiskContextService` przechowujący `activeDeviceId` signal,
ustawiany przy montowaniu dysku w `disk-setup` flow.

---

## Kolejność implementacji

```
── backend ──
1.  PhotoRepository: nowe zapytania (storageDeviceId, countMissing)
2.  PhotoService: obsługa storageDeviceId w listPhotos()
3.  ThumbnailService: generateMissingForDevice() z callbackiem
4.  PhotoController: GET /api/photos/missing-thumbnails-count
5.  PhotoController (lub ThumbnailController): POST /api/photos/generate-thumbnails (SSE)
6.  Regeneracja openapi.yml

── frontend ──
7.  Sprawdzenie/stworzenie DiskContextService (activeDeviceId)
8.  PhotosService: getMissingThumbnailsCount(), generateThumbnails() (SSE)
9.  MissingThumbnailsBannerComponent
10. PhotosComponent: usunięcie accountId, dodanie storageDeviceId, infinite scroll
11. PhotosToolbarComponent: usunięcie dropdown konta
12. Regeneracja API client (npm run generate-api)
```

---

## Kwestie otwarte

| Kwestia | Opcja A | Opcja B |
|---|---|---|
| `storageDeviceId` skąd | Z `DiskContextService` (shared state) | Z route params `/photos/:deviceId` |
| Filtr "all/synced" | Usunąć (zawsze synced) | Zostawić dla debugowania |
| Generate thumbnails — gdy brak ImageMagick | Błąd z info w UI | Skip HEIC, generuj resztę |
| Odświeżanie galerii po generacji | Full reload zdjęć | Tylko reload thumbnailUrls dla brakujących |
| Pagination size | 50 (mniejsza strona, szybszy start) | 100 (jak teraz) |

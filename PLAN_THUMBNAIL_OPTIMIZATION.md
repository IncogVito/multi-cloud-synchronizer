# Plan: Thumbnail Memory Fix + Sprite Sheets

## Znalezione problemy

### Problem 1 — wyciek 8GB RAM

**Przyczyna A: `newFixedThreadPool` z virtual thread factory (AppConfig.java:62-66)**
```java
int threads = Math.max(8, Runtime.getRuntime().availableProcessors());
return java.util.concurrent.Executors.newFixedThreadPool(
    threads, Thread.ofVirtual().name("thumb-", 0).factory()
);
```
`newFixedThreadPool` używa nieograniczonej kolejki (`LinkedBlockingQueue`). Przy synchronizacji 50k zdjęć wszystkie `CompletableFuture` są submitowane od razu (`runJob` wysyła całą listę `candidates` naraz). Kolejka trzyma 50k tasków, każdy z referencją do obiektu `Photo`. To ≈ duże zużycie sterty, nie licząc tego że same wirtualne wątki + executor działają niepoprawnie razem (VT są zaprojektowane do unbounded executor, nie fixed pool).

**Przyczyna B: `Sinks.many().replay().all()` (ThumbnailJob.java:21)**
```java
private final Sinks.Many<ThumbnailProgress> sink = Sinks.many().replay().all();
```
`replay().all()` trzyma WSZYSTKIE wyemitowane eventy w pamięci na zawsze (do momentu usunięcia joba — a cleanup jest co 1h, usuwa tylko po 24h). Dla 50k zdjęć = 50k obiektów `ThumbnailProgress` per job w stercie Reactora.

**Przyczyna C: `cleanupOldJobs` — 24h TTL**
Scheduler co 1h, usuwa dopiero po 24h od `createdAt`. Jeśli użytkownik odpalił kilka jobów — wszystkie siedzą w pamięci przez dobę.

**Przyczyna D: procesy `vipsthumbnail` / `ffmpeg`**
Przy 8+ równoległych procesach, każdy ładuje oryginalny plik (HEIC/RAW może mieć 20-50MB). Przy 8 równolegle = 160-400MB tylko od procesów. Nie jest to główny powód 8GB ale dorzuca.

---

## Naprawy (Problem 1)

### Fix A — executor: ograniczyć do połowy CPU, użyć semaforu zamiast fixed pool

Zmienić `AppConfig.thumbnailExecutor()`:
```java
@Bean
@Named("thumbnailExecutor")
public ExecutorService thumbnailExecutor() {
    // Virtual threads + unbounded executor, ale Semaphore w ThumbnailJobService ogranicza równoległość
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

W `ThumbnailJobService` dodać `Semaphore`:
```java
private final Semaphore concurrencyLimit = new Semaphore(
    Math.max(1, Runtime.getRuntime().availableProcessors() / 2)
);
```

W `runJob` każdy task: `semaphore.acquire()` przed `generateThumbnail`, `semaphore.release()` w finally. Dzięki temu:
- Nie więcej niż N/2 równoległych procesów vipsthumbnail/ffmpeg
- Kolejka wirtualnych wątków nie trzyma Photo ref w stercie — task blokuje na semafore, ale zajmuje tylko wirtualny wątek (kb zamiast mb)

### Fix B — sink: `replay().limit(1)` zamiast `replay().all()`

Klient SSE odczytuje dane na bieżąco. Jedyna sytuacja gdzie replay ma sens to reconnect — wystarczy ostatni event (aktualny stan). 

```java
private final Sinks.Many<ThumbnailProgress> sink = Sinks.many().replay().limit(1);
```

Zamiast 50k eventów — max 1 event w buforze na job.

### Fix C — skrócić TTL cleanup

Zmienić `cleanupOldJobs`: usuwać joby done po 1h (nie 24h). Jeśli klient zgubił połączenie i nie odczyta — to jego problem.

```java
Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
```

### Fix D — batch submit zamiast wszystko naraz

Zamiast submitować wszystkie `candidates` do executora jednocześnie, procesować w batchach (np. 50 na raz). Reducer memory pressure na kolejce executora.

---

## Problem 2 — Sprite Sheets (nowy serwis)

### Koncepcja

Zamiast: N żądań `GET /api/photos/{id}/thumbnail` (jedno na zdjęcie)  
Nowe: 
1. `POST /api/photos/sprite-manifest` — przyjmuje listę photoId, zwraca manifest (spriteId + pozycje każdego thumbnaila)
2. `GET /api/photos/sprites/{spriteId}` — zwraca złożony obraz JPEG (sprite sheet)

Frontend pobiera jeden duży obraz i używa CSS `background-position` do wycinania.

### Nowy serwis backend: `ThumbnailSpriteService`

**Lokalizacja:** `backend/src/main/java/com/cloudsync/service/ThumbnailSpriteService.java`

**Odpowiedzialność:**
- Przyjmuje listę `photoId` (max 500)
- Sprawdza które mają thumbnail na dysku
- Składa je w grid (np. 25 kolumn × 20 wierszy = 500 thumbnaili po 300x300px = 7500x6000px JPEG)
- Zapisuje sprite sheet do `{thumbnailDir}/sprites/{spriteId}.jpg`
- Zwraca `SpriteManifest` — mapę `photoId → (x, y, w, h)` + spriteId

**Cache strategia:**
- `spriteId` = hash (SHA-256 pierwsze 12 znaków) z posortowanej listy photoId
- Jeśli plik już istnieje na dysku → nie generuj ponownie, zwróć manifest
- Eviction: osobny job czyści stare sprite sheets (np. starsze niż 7 dni od ostatniego dostępu)

**Implementacja składania obrazu:**
- Użyć Java `BufferedImage` + `Graphics2D` (no extra deps)
- Każdy thumbnail ładowany przez `ImageIO.read(thumbFile)`, rysowany na pozycji `(col*300, row*300)`
- Finalnie `ImageIO.write(sprite, "JPEG", outputStream)` z kompresją JPEG 0.85
- Alternatywa: wywołanie `vips arrayjoin` (ale to dodatkowa zależność od CLI w runtime — lepiej Java)

**Nowe DTO:**

```java
// SpriteManifest.java
record SpriteManifest(
    String spriteId,
    int spriteWidth,
    int spriteHeight,
    Map<String, SpriteSlot> slots  // photoId -> slot
) {}

// SpriteSlot.java  
record SpriteSlot(int x, int y, int w, int h) {}
```

**Nowe endpointy w PhotoController:**

```
POST /api/photos/sprite-manifest
Body: { photoIds: string[] }  // max 500
Response: SpriteManifest

GET /api/photos/sprites/{spriteId}
Response: image/jpeg
```

### Zmiany frontend

**Nowy serwis:** `frontend/src/app/core/services/thumbnail-sprite.service.ts`

**Odpowiedzialność:**
- Zarządza pobieraniem sprite manifestów
- Grupuje widoczne photoId w batche ≤500
- Pobiera sprite image → tworzy jeden `URL.createObjectURL(blob)`
- Udostępnia funkcję `getSlot(photoId): { spriteUrl, x, y, w, h } | null`

**Zmiany w `PhotosComponent`:**
- Usunąć `thumbnailUrls: Map<string, string>` (per-photo blob URLs)
- Zastąpić przez `thumbnailSlots: Map<string, { spriteUrl: string, x: number, y: number, w: number, h: number }>`
- `onThumbnailNeeded` → zamiast fetcha per-photo, dodaje do kolejki batchowej
- Po zebraniu batcha (lub timeout 200ms) → `POST /api/photos/sprite-manifest` → pobiera sprite

**Zmiany w `photo-timeline.component.html`:**
- Zamiast `<img [src]="thumbnailUrls().get(photo.id)">`:
```html
@if (thumbnailSlots().has(photo.id)) {
  <div class="thumb-sprite"
    [style.background-image]="'url(' + thumbnailSlots().get(photo.id)!.spriteUrl + ')'"
    [style.background-position]="'-' + thumbnailSlots().get(photo.id)!.x + 'px -' + thumbnailSlots().get(photo.id)!.y + 'px'"
    [style.background-size]="thumbnailSlots().get(photo.id)!.spriteWidth + 'px ' + thumbnailSlots().get(photo.id)!.spriteHeight + 'px'"
    [style.width.px]="thumbnailSlots().get(photo.id)!.w"
    [style.height.px]="thumbnailSlots().get(photo.id)!.h">
  </div>
}
```

**Cleanup blob URLs:**
- Sprite blob URLs śledzone oddzielnie
- `ngOnDestroy` revokuje je
- Jeden spriteUrl obsługuje ≤500 thumbnaili (zamiast 500 osobnych blob URLs)

---

## Kolejność implementacji

1. **Fix A+B+C** (backend, AppConfig + ThumbnailJob + ThumbnailJobService) — szybkie, izolowane
2. **ThumbnailSpriteService** + nowe DTO + endpointy (backend)
3. **Regeneracja openapi.yml** (`./gradlew exportSwagger`)
4. **Regeneracja klienta Angular** (`npm run generate-api`)
5. **ThumbnailSpriteService** (frontend) + zmiany w PhotosComponent + photo-timeline template

---

## Ryzyka

| Ryzyko | Mitygacja |
|--------|-----------|
| `BufferedImage` ładuje cały sprite do RAM (7500x6000 = ~162MB niepskompresowane) | Generować sprite przez `Graphics2D` streamowo, nie trzymać w pamięci po zapisie na dysk |
| Sprite generation blokuje request (duży batch) | Generować asynchronicznie, endpoint zwraca `202 Accepted` + polling, albo timeout 30s |
| Stale sprite gdy thumbnail się zmieni (regeneracja po `generateThumbnail`) | Invalidacja: usuwanie sprite pliku gdy którykolwiek z photoId dostanie nowy thumbnail |
| Zbyt duże sprite pliki na dysku | TTL cleanup + limit 500 per sprite + kompresja JPEG 85% |
| Frontend batch timing (200ms debounce może spowalniać UX) | Flush batch gdy rozmiar ≥500 lub po 150ms bezczynności |

# Plan: Konfiguracja dysku i folderu synchronizacji

## Cel

Przeprowadzić użytkownika przez wizard konfiguracji przed pierwszą synchronizacją:
1. Wybór i potwierdzenie dysku
2. Wybór konkretnego folderu synchronizacji (zapisanego w DB)
3. Wybór strategii segregacji (rok vs miesiąc)
4. Opcjonalna reorganizacja zastanych zdjęć

---

## D1. Baza danych — migracja `V5__add_sync_config.sql`

```sql
ALTER TABLE icloud_accounts ADD COLUMN sync_folder_path TEXT;
ALTER TABLE icloud_accounts ADD COLUMN storage_device_id TEXT REFERENCES storage_devices(id);
ALTER TABLE icloud_accounts ADD COLUMN organize_by TEXT DEFAULT 'MONTH';
-- organize_by: 'YEAR' → 2024/  |  'MONTH' → 2024/04/
```

**`ICloudAccount` entity** — nowe pola: `syncFolderPath`, `storageDeviceId`, `organizeBy`

---

## D2. Backend — nowe endpointy

#### `GET /setup/browse?path=<ścieżka>`

Listuje podkatalogi na jednym poziomie (lazy load dla drzewa folderów).
Jeśli `path` pominięty → zwraca `mountPoint` zamontowanego dysku jako korzeń.

```json
{
  "path": "/mnt/disk",
  "entries": [
    { "name": "Zdjecia", "path": "/mnt/disk/Zdjecia", "isDir": true, "childCount": 3 },
    { "name": "Dokumenty", "path": "/mnt/disk/Dokumenty", "isDir": true, "childCount": 0 }
  ]
}
```

#### `GET /setup/scan?path=<ścieżka>`

Głęboki rekurencyjny skan folderu (`Files.walk()`) szukający plików obrazów
(jpg, jpeg, heic, png, mov, mp4). Odpowiada synchronicznie (dla typowych zbiorów wystarczy),
dla bardzo dużych można rozważyć SSE.

```json
{ "totalFiles": 1234, "byExtension": {"jpg": 800, "heic": 300, "mov": 134}, "deepestLevel": 6 }
```

#### `PUT /api/accounts/{id}/sync-config`

Zapisuje konfigurację konta. Waliduje że `syncFolderPath` istnieje i jest podkatalogiem
zamontowanego dysku.

```json
{ "syncFolderPath": "/mnt/disk/Zdjecia", "storageDeviceId": "uuid", "organizeBy": "MONTH" }
```

#### `POST /api/accounts/{id}/reorganize?dryRun=true|false`

Przenosi istniejące pliki na dysku do struktury odpowiadającej `organizeBy`.
`dryRun=true` → zwraca plan zmian bez wykonania.

```json
{ "moved": 980, "skipped": 45, "errors": 2, "dryRun": true,
  "sampleMoves": [
    { "from": "/mnt/disk/Zdjecia/IMG_0001.jpg", "to": "/mnt/disk/Zdjecia/2023/04/IMG_0001.jpg" }
  ]
}
```

---

## D3. Backend — implementacja `SetupService`

#### `deepScanFolder(path)`

```java
public DiskScanResult deepScanFolder(String path) {
    Path root = Path.of(path);
    Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "heic", "png", "mov", "mp4");
    Map<String, Long> extCounts = new HashMap<>();
    AtomicInteger maxDepth = new AtomicInteger(0);

    try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(Files::isRegularFile).forEach(p -> {
            String ext = getExtension(p).toLowerCase();
            if (IMAGE_EXTS.contains(ext)) {
                extCounts.merge(ext, 1L, Long::sum);
                int depth = root.relativize(p).getNameCount();
                maxDepth.accumulateAndGet(depth, Math::max);
            }
        });
    }
    return new DiskScanResult(extCounts.values().stream().mapToLong(x -> x).sum(), extCounts, maxDepth.get());
}
```

#### `reorganize(accountId, dryRun)`

```java
public ReorganizeResult reorganize(String accountId, boolean dryRun) {
    ICloudAccount account = accountRepository.findById(accountId).orElseThrow();
    List<Photo> synced = photoRepository.findByAccountIdAndSyncedToDisk(accountId, true);

    int moved = 0, skipped = 0, errors = 0;
    List<MovePreview> sampleMoves = new ArrayList<>();

    for (Photo photo : synced) {
        Path current = Path.of(photo.getFilePath());
        if (!Files.exists(current)) { errors++; continue; }

        LocalDate date = toLocalDate(photo.getCreatedDate());
        Path targetDir = "MONTH".equals(account.getOrganizeBy())
            ? Path.of(account.getSyncFolderPath(), String.valueOf(date.getYear()),
                      String.format("%02d", date.getMonthValue()))
            : Path.of(account.getSyncFolderPath(), String.valueOf(date.getYear()));

        Path target = targetDir.resolve(current.getFileName());
        if (current.equals(target)) { skipped++; continue; }

        if (sampleMoves.size() < 5) sampleMoves.add(new MovePreview(current, target));

        if (!dryRun) {
            Files.createDirectories(targetDir);
            Files.move(current, target, StandardCopyOption.ATOMIC_MOVE);
            photo.setFilePath(target.toString());
            photoRepository.update(photo);
        }
        moved++;
    }
    return new ReorganizeResult(moved, skipped, errors, dryRun, sampleMoves);
}
```

**Kolizje nazw:** jeśli plik o tej samej nazwie już istnieje w `targetDir` → dodaj suffix `_1`, `_2` itd.

---

## D4. Frontend — wizard konfiguracji

Nowa sekcja `features/setup-wizard/` z czterema krokami.
Breadcrumb/stepper na górze. Cofanie dozwolone.

```
[1. Dysk ✓] → [2. Folder ✓] → [3. Segregacja ✓] → [4. Reorganizacja?] → [Gotowe]
```

#### Krok 1 — Potwierdzenie dysku (`disk-confirm-step`)

Reuse logiki z istniejącego `disk-setup.component`. Różnica:
- Wyświetl kartę dysku z: label, ścieżka mount, rozmiar, wolne miejsce, UUID (filesystem)
- Explicit przycisk **"Potwierdź: używam dysku [LABEL]"** — bez kliknięcia nie ma przejścia dalej
- Jeśli dysk nie zamontowany → najpierw montowanie (jak teraz), potem potwierdzenie

#### Krok 2 — Wybór folderu (`folder-picker-step`)

- Drzewo folderów z lazy loadem (`GET /setup/browse?path=...` per rozwinięcie)
- Breadcrumb nawigacja (klik → skocz poziom wyżej)
- Po wybraniu folderu → automatyczny `GET /setup/scan?path=...` z spinnerem
- Wynik skanu: `"Znaleziono ~1 234 zdjęć (heic: 800, jpg: 300, mov: 134)"` — kontekst dla użytkownika
- Przycisk **"Wybierz ten folder"** aktywny tylko gdy folder wybrany

#### Krok 3 — Strategia segregacji (`organize-strategy-step`)

```
○  Foldery roczne
   Zdjecia/
   ├── 2023/
   └── 2024/

●  Foldery miesięczne  [domyślny]
   Zdjecia/
   ├── 2023/
   │   ├── 03/
   │   └── 04/
   └── 2024/
```

- Dwa radio buttons z podglądem struktury (statyczny ASCII/HTML)
- Wybór zapisywany lokalnie, do DB dopiero przy zapisie całego wizarda (krok 4)

#### Krok 4 — Reorganizacja zastanych zdjęć (`reorganize-step`)

Wyświetlany **tylko jeśli** skan w kroku 2 znalazł pliki (`totalFiles > 0`).

1. Pytanie: `"Czy chcesz posegregować {N} istniejących zdjęć zgodnie z wybraną strukturą?"`
2. Jeśli Tak:
   - `POST /api/accounts/{id}/reorganize?dryRun=true` → pokaż podgląd (pierwsze 5 przykładów)
   - Przycisk **"Wykonaj reorganizację"** → `POST /api/accounts/{id}/reorganize?dryRun=false`
   - Progress spinner, wynik: `"Przeniesiono: 980, Pominięto: 45, Błędy: 2"`
3. Jeśli Nie → pomiń, przejdź do podsumowania
4. W obu przypadkach zapisz sync-config do DB (`PUT /api/accounts/{id}/sync-config`)

---

## D5. Pliki do stworzenia / zmodyfikowania

```
── backend ──
backend/src/main/resources/db/migration/V5__add_sync_config.sql    ← nowy
backend/src/main/java/com/cloudsync/model/entity/ICloudAccount.java ← +3 pola
backend/src/main/java/com/cloudsync/model/dto/BrowseEntry.java      ← nowy DTO
backend/src/main/java/com/cloudsync/model/dto/DiskScanResult.java   ← nowy DTO
backend/src/main/java/com/cloudsync/model/dto/ReorganizeResult.java ← nowy DTO
backend/src/main/java/com/cloudsync/model/dto/MovePreview.java      ← nowy DTO
backend/src/main/java/com/cloudsync/service/SetupService.java       ← nowy (browse, scan, reorganize)
backend/src/main/java/com/cloudsync/controller/SetupController.java ← nowe endpointy

── frontend ──
frontend/src/app/core/models/sync-config.model.ts                   ← nowy
frontend/src/app/core/services/setup-wizard.service.ts              ← nowy (browse, scan, syncConfig, reorganize)
frontend/src/app/features/setup-wizard/
  setup-wizard.component.ts/.html                                   ← nowy (kontener kroków)
  steps/disk-confirm-step.component.ts/.html                        ← nowy (reuse disk-setup)
  steps/folder-picker-step.component.ts/.html                       ← nowy
  steps/organize-strategy-step.component.ts/.html                   ← nowy
  steps/reorganize-step.component.ts/.html                          ← nowy
```

---

## D6. Kolejność implementacji

```
── backend ──
1.  V5__add_sync_config.sql + ICloudAccount entity (3 pola)
2.  DTOs: BrowseEntry, DiskScanResult, ReorganizeResult, MovePreview
3.  SetupService: deepScanFolder() + browse()
4.  SetupController: GET /setup/browse, GET /setup/scan
5.  SetupService: reorganize() z dry-run
6.  SetupController: PUT /api/accounts/{id}/sync-config, POST /api/accounts/{id}/reorganize

── frontend ──
7.  sync-config.model.ts + setup-wizard.service.ts
8.  folder-picker-step (browse + scan)
9.  organize-strategy-step
10. reorganize-step (dry-run → confirm → execute)
11. disk-confirm-step (uplift z disk-setup)
12. setup-wizard (orchestracja, routing /setup/wizard)
```

---

## Kwestie otwarte

| Kwestia | Opcja A | Opcja B |
|---|---|---|
| Kolizje nazw przy reorganizacji | Suffix `_1`, `_2` | Skip (pomiń plik) |
| Skan folderu dla 50k+ plików | Synchroniczny (blokuje request) | Asynchroniczny z SSE postępem |
| `organizeBy` gdzie trzymać | W `ICloudAccount` (1:1, proste) | Osobna tabela `sync_config` (rozszerzalna) |
| Folder picker — ładowanie | Lazy load (API call per folder) | Eager (pełne drzewo — wolno dla dużych dysków) |

# Plan: Obsługa dysku zewnętrznego (v2)

## Nowe założenia

- **Baza danych jest lokalna** — na stałym wolumenie serwera, zawsze dostępna, zawsze startuje
- **Dysk zewnętrzny = magazyn zdjęć** — pliki zdjęć trafiają na dysk zewnętrzny
- **Przy każdym zdjęciu przechowuj ID urządzenia** — skąd pochodzi (z którego dysku)
- **Przed logowaniem**: sprawdź czy dysk jest zamontowany i daj możliwość zamontowania
- **App startuje zawsze** — brak dysku nie blokuje uruchomienia

---

## Zmiany w bazie danych

### Nowa tabela `storage_devices`

Przechowuje znane urządzenia magazynujące (dyski zewnętrzne):

```sql
CREATE TABLE storage_devices (
    id TEXT PRIMARY KEY,          -- np. UUID lub label dysku
    label TEXT,                   -- np. "CloudSyncDrive"
    device_path TEXT,             -- np. "/dev/sdb1"
    mount_point TEXT,             -- np. "/mnt/external-drive"
    filesystem_uuid TEXT,         -- UUID z blkid (unikalny identyfikator)
    size_bytes BIGINT,
    first_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP
);
```

### Zmiana w tabeli `photos`

Dodaj kolumnę `storage_device_id`:

```sql
ALTER TABLE photos ADD COLUMN storage_device_id TEXT REFERENCES storage_devices(id);
```

Oznacza: "ten plik fizycznie leży na tym urządzeniu".

---

## Zmiany w konfiguracji

### `application.yml`

Datasource wskazuje na lokalny wolumen serwera (nie dysk zewnętrzny):

```yaml
datasources:
  default:
    url: ${DATASOURCE_URL:jdbc:sqlite:/data/cloud-sync.db?journal_mode=WAL}
```

### `docker-compose.yml`

- Dodaj lokalny volume `db-data:/data` (zawsze dostępny)
- Zewnętrzny dysk nadal montowany pod `/mnt/external-drive` jako magazyn plików
- Dodać `restart: unless-stopped` do backend

```yaml
volumes:
  - db-data:/data               # baza danych — lokalna, trwała
  - ${EXTERNAL_DRIVE_PATH:-}:/mnt/external-drive   # zdjęcia — opcjonalnie
```

---

## Nowe endpointy (bez autoryzacji)

### `DiskSetupController` @ `/api/setup`

```
GET  /api/setup/status      → { mounted, drivePath, freeBytes, deviceId, label }
GET  /api/setup/disks       → lista dostępnych dysków zewnętrznych z lsblk
POST /api/setup/mount       → { device: "/dev/sdb1" } → montuje pod /mnt/external-drive
POST /api/setup/unmount     → odmontowuje /mnt/external-drive
```

Po zamontowaniu — backend:
1. Odczytuje UUID i label dysku przez `blkid`
2. Zapisuje/aktualizuje rekord w `storage_devices` (rejestruje urządzenie)
3. Zwraca `deviceId` do frontenduprobably

---

## Nowe skrypty bash

- `scripts/list-disks.sh` — `lsblk -J`, filtruje zewnętrzne (pomija sda/system)
- `scripts/unmount-drive.sh` — `umount /mnt/external-drive`
- `scripts/read-device-id.sh` — `blkid -s UUID -o value /dev/sdX` → UUID jako identyfikator

---

## Zmiany w backendzie (Java)

### `DiskSetupService.java` (NOWY)

Bez zależności DB dla metod disk-management, z zależnością dla rejestracji urządzenia:
- `getDriveStatus()` → check-drive.sh
- `listDisks()` → list-disks.sh
- `mountAndRegister(device)` → mount-drive.sh + blkid + zapis do `storage_devices`
- `unmount()` → unmount-drive.sh

### `StorageDevice.java` entity + repository (NOWE)

JPA entity dla tabeli `storage_devices`.

### `PhotoService.java` (ZMIANA)

Przy dodawaniu zdjęcia: pobierz aktywny `storage_device_id` (aktualnie zamontowane urządzenie)
i zapisz z zdjęciem.

### `application.yml` (ZMIANA)

Dodaj security rule dla `/api/setup/**`:
```yaml
- pattern: /api/setup/**
  access:
    - isAnonymous()
```

---

## Zmiany w frontendzie

### Nowy `DiskSetupComponent`

Strona pod `/setup`, dostępna bez logowania.

Stan 1 — brak dysku:
```
[!] Brak dysku zewnętrznego
    Podłącz dysk USB/SATA i odśwież
    [Odśwież]
```

Stan 2 — dysk dostępny, niezamontowany:
```
Dostępne dyski:
  ○ /dev/sdb1  Samsung 2TB  [Zamontuj]
  ○ /dev/sdc   WD 4TB       [Zamontuj]
```

Stan 3 — dysk zamontowany:
```
[✓] Dysk zamontowany: "CloudSyncDrive" (/mnt/external-drive)
    Wolne miejsce: 1.8 TB
    [Przejdź do logowania →]
```

Stan 4 — brak dysku (ale app działa):
```
[!] Dysk niezamontowany — zdjęcia niedostępne
    [Zamontuj dysk] lub [Kontynuuj bez dysku →]
```

### Routing i guard

- Route `/setup` → `DiskSetupComponent`
- `AppComponent` przy starcie: `GET /api/setup/status`
  - Jeśli niezamontowany → przekieruj na `/setup`
  - Jeśli zamontowany → normalny flow (`/login` lub `/dashboard`)
- Pozwól wejść na `/login` i dalej nawet bez dysku (app działa, tylko zdjęcia niedostępne)

---

## Flow użytkownika

```
[Start app]
     ↓
GET /api/setup/status
     ├─ mounted=true  → /login → /dashboard (normalnie)
     └─ mounted=false → /setup
                           ├─ brak fizycznego dysku → informacja
                           └─ dysk widoczny → wybierz i zamontuj
                                → mounted=true → /login
```

---

## Kolejność implementacji

1. **Migracja SQL** — `storage_devices` tabela + `storage_device_id` w `photos`
2. **Skrypty bash** — `list-disks.sh`, `unmount-drive.sh`, `read-device-id.sh`
3. **Backend** — `DiskSetupService` + `DiskSetupController` + `StorageDevice` entity
4. **Zmiana datasource** — `application.yml` + `docker-compose.yml`
5. **PhotoService** — dołączanie `storage_device_id` przy imporcie zdjęć
6. **Frontend** — `DiskSetupComponent` + routing guard

---

## Co NIE zmienia się

- Cała logika iCloud, iPhone, kont — bez zmian
- `DeviceStatusService` / `StatusController` — bez zmian
- Autentykacja Basic Auth — bez zmian
- Flyway działa jak dotychczas (baza lokalna, zawsze startuje)

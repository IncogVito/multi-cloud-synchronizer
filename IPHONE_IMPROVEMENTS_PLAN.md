# Plan usprawnień iPhone sync

## 1. Batch scanning z raportowaniem postępu

**Problem:** `IPhoneSyncProvider.doScan()` zbiera wszystkie pliki w jednej iteracji i dopiero po zakończeniu ustawia `SessionState.Ready`. Przez ten czas `getPrefetchStatus()` zwraca zawsze `{status: "scanning", fetched: 0}`.

### Backend — `IPhoneSyncProvider`

- Zmienić `SessionState.Scanning` z bezparametrowego rekordu na `Scanning(int fetched)` przechowujący bieżącą liczbę już znalezionych zdjęć.
- W `scanDcim()` zamiast `forEach` — przerobić na pętlę z licznikiem atomowym, która co np. 50 plików wywołuje `sessions.put(sessionId, SessionState.scanning(count.get()))`.
- `getPrefetchStatus()` dla `Scanning` odczyta bieżące `fetched` zamiast 0 — bez zmiany sygnatury DTO.

### Frontend — `sync-section`

- Nie wymaga zmian — komponent już wyświetla `"Pobrano metadane: X / Y"` bazując na `fetched`/`total` z PrefetchStatus. Gdy `total` będzie `null` (jeszcze nie znamy końca), pokaże tylko licznik rosnący w górę.
- Opcjonalnie: dodać komunikat np. *"Skanowanie… znaleziono X plików"* zamiast pustego paska.

**Wynik:** użytkownik widzi rosnący licznik już od pierwszych sekund skanowania.

---

## 2. Równoległe pobieranie z iPhone

**Problem:** Pobieranie przez FUSE (ifuse) jest wolne — każdy plik czyta się sekwencyjnie przez warstwę FUSE→MTP→USB.

### Backend — `SyncService`

- Aktualny limit semafora (`50`) jest za wysoki dla FUSE/USB; zbyt wiele równoległych odczytów może powodować timeout lub degradację.
- Wprowadzić **per-provider limit równoległości**: iCloud może mieć 20-50 (sieć), iPhone — 4-8 (USB/FUSE). Można to zrobić przez `Map<String, Integer> providerConcurrency` w konfiguracji lub prostym `if ("IPHONE".equals(provider)) semaphore = new Semaphore(4)`.
- Virtual threads już zapewniają brak blokowania IO na wątku semafora — samo dostrojenie liczby do 4-8 powinno dać wyraźną poprawę.

**Wynik:** 4-8 plików pobieranych równolegle zamiast jednego, bez przeciążania USB.

---

## 3. Status montowania w kafelku + przycisk odmontowania

**Problem:** Kafelek iPhone pokazuje tylko "Connected/Disconnected" (czy urządzenie podłączone), ale nie informuje czy jest zamontowane (ifuse). Nie ma też możliwości odmontowania z UI.

### Backend

**Nowy endpoint w `StatusController`:**
```
POST /api/status/iphone/unmount
```
- Wywołuje `shellExecutor.executeScript(scriptsDir, "iphone-unmount.sh")`
- Zwraca `{"unmounted": true/false, "error": "..."}` — prosto z wyniku skryptu

**Rozszerzenie `DeviceStatusResponse`:**
- Dodać pole `mounted: Boolean` do odpowiedzi z `/api/status/devices`
- `DeviceStatusService` po sprawdzeniu statusu sprawdza czy iPhone jest zamontowany (np. `Files.isDirectory(Path.of(iphoneMountPath, "DCIM"))` albo parsując wynik `iphone-mount.sh`)

### Frontend — `device-status-panel`

- Dla kafelka iPhone: jeśli `status === "CONNECTED"`, wyświetlić dodatkowe pole `mounted`
- Wyświetlić badge **"Zamontowany"** / **"Niezamontowany"** obok "Connected"
- Dodać przycisk **"Odmontuj"** (aktywny gdy `mounted === true`) wywołujący `POST /api/status/iphone/unmount`
- Po odmontowaniu: odświeżyć status kafelka (wyczyścić `mounted`, zmienić badge)
- Skrypt `iphone-unmount.sh` już istnieje — backend tylko go woła

---

## Kolejność realizacji

| Priorytet | Zadanie | Ryzyko |
|---|---|---|
| 1 | Unmount button (kafelek) | Niskie — skrypt istnieje, to głównie nowy endpoint + UI |
| 2 | Batch scan z licznikiem | Niskie — zmiana wewnątrz `SessionState`, brak zmian API |
| 3 | Równoległe pobieranie (tuning) | Niskie — parametr semafora, ewentualnie per-provider config |

---

## Pliki do modyfikacji

| Plik | Zadanie |
|---|---|
| `backend/src/main/java/com/cloudsync/provider/IPhoneSyncProvider.java` | Batch scan (zadanie 2) |
| `backend/src/main/java/com/cloudsync/service/SyncService.java` | Per-provider concurrency (zadanie 3) |
| `backend/src/main/java/com/cloudsync/service/DeviceStatusService.java` | Pole `mounted` w statusie (zadanie 1) |
| `backend/src/main/java/com/cloudsync/controller/StatusController.java` | Endpoint `/iphone/unmount` (zadanie 1) |
| `backend/src/main/java/com/cloudsync/model/dto/DeviceStatusResponse.java` | Pole `mounted` (zadanie 1) |
| `frontend/src/app/features/dashboard/device-status-panel/` | Badge + przycisk odmontowania (zadanie 1) |

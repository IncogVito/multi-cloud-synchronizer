# Phase 2.1 – Backend: Device Status via SSE

## Kontekst projektu

Budujemy aplikację do synchronizacji zdjęć z iCloud na zewnętrzny dysk USB.
Użytkownik ma iPhone'a, konto iCloud i podłączony dysk zewnętrzny – aplikacja pobiera zdjęcia z chmury i zapisuje lokalnie.

### Stack technologiczny
- **Backend**: Java 21 + Micronaut 4.7, SQLite (Flyway migracje), Thumbnailator
- **icloud-service**: mikroserwis Python/FastAPI (pyicloud), port 8000
- **Frontend**: Angular (osobny kontener)
- **Build**: Gradle Kotlin DSL (`./gradlew`)
- **Compose dev**: `compose.dev.yaml` w roście projektu

### Architektura pakietów backendu (`backend/src/main/java/com/cloudsync/`)
```
controller/
  StatusController.java     ← GET /api/status/devices, POST /api/status/check-drive, check-iphone
  AgentController.java      ← POST /api/agent/detect-drive (SSE, AI agent)
  PhotoController.java
  AccountController.java
  FolderController.java
  HealthController.java

service/
  DeviceStatusService.java  ← sprawdzanie dysku i iPhone'a, zapis do DB
  DiskDetectionAgent.java   ← fasada; deleguje do AgentExecutor
  AccountService.java
  PhotoService.java
  SyncService.java
  ThumbnailService.java

agent/
  AgentExecutor.java        ← pętla tool-calling, emituje Flux<AgentStepEvent>
  AgentTool.java            ← interfejs narzędzia (getName, getDescription, execute)
  LlmProvider.java          ← interfejs; AgentAction = callTool | finalAnswer
  OpenRouterLlmProvider.java ← @Primary, aktywny gdy OPENROUTER_API_KEY != ""
  FallbackLlmProvider.java  ← heurystyczny fallback bez LLM
  tools/
    ListBlockDevicesTool.java
    CheckMountPointTool.java
    MountDeviceTool.java
    CheckDiskSpaceTool.java
    ReadDiskLabelTool.java

model/
  entity/DeviceStatus.java          ← id, deviceType, isConnected, lastCheckedAt, details
  dto/DeviceStatusResponse.java     ← id, deviceType, connected, lastCheckedAt, details
  dto/AgentStepEvent.java           ← step, action, result, success, message
  enums/DeviceType.java             ← EXTERNAL_DRIVE, IPHONE, ICLOUD

repository/DeviceStatusRepository.java  ← CrudRepository, findByDeviceType(String)
client/ICloudServiceClient.java         ← deklaratywny HTTP client do icloud-service
util/ShellExecutor.java                 ← uruchamianie skryptów z /scripts/
config/AppAuthenticationProvider.java   ← Basic Auth z APP_USERNAME / APP_PASSWORD
```

### Skrypty shell (`/scripts/`, montowane jako volume)
```
detect-iphone.sh        → {"connected": bool, "device_name": str|null, "udid": str|null}
check-drive.sh [path]   → {"available": bool, "path": str|null, "free_bytes": int|null}
mount-drive.sh /dev/X /mnt/Y → {"mounted": bool, ...}
iphone-list-devices.sh  → {"devices": [...udids...]}
iphone-get-info.sh [udid] → pełne info o urządzeniu
iphone-check-trust.sh [udid] → {"trusted": bool, ...}
```

### Zmienne środowiskowe (z `.env` w root projektu)
```
OPENROUTER_API_KEY=     # klucz OpenRouter (pusty → FallbackLlmProvider)
OPENROUTER_MODEL=google/gemini-2.0-flash-001
APP_USERNAME=admin
APP_PASSWORD=changeme
EXTERNAL_DRIVE_PATH=/mnt/external-drive
ICLOUD_SERVICE_URL=http://icloud-service:8000
```

---

## Cel tej fazy

Przebudować mechanizm device-status tak, żeby:
1. Wszystkie operacje wykrywania urządzeń **emitowały postęp przez SSE** (Server-Sent Events), a nie blokowały HTTP do końca.
2. Każdy krok agenta (co robi, co znalazł, co się nie udało) był **widoczny w czasie rzeczywistym** dla frontendu.
3. Istniały **rozróżnialne statusy** dla każdego urządzenia – nie tylko `connected: true/false`.
4. Użytkownik mógł **ręcznie retriggować** sprawdzenie dowolnego urządzenia.

---

## Nowe statusy urządzeń

Zastąp `isConnected: boolean` bogatszym typem. Dodaj pole `status` do encji i DTO.

### Enum `DeviceCheckStatus` (nowy: `model/enums/DeviceCheckStatus.java`)
```java
public enum DeviceCheckStatus {
    UNKNOWN,           // stan domyślny, nigdy nie sprawdzany
    CHECKING,          // w trakcie sprawdzania
    CONNECTED,         // urządzenie dostępne i gotowe
    DISCONNECTED,      // nie wykryto urządzenia
    MOUNTING,          // dysk znaleziony, trwa montowanie
    MOUNT_FAILED,      // próba montowania nieudana
    UNREACHABLE,       // iCloud/mikroserwis niedostępny
    ERROR              // nieoczekiwany błąd
}
```

### Zmiany w encji `DeviceStatus` (`model/entity/DeviceStatus.java`)
- Dodaj pole `String status` (przechowuje nazwę enuma jako tekst, SQLite nie ma enuma)
- Pole `isConnected` zostaje dla kompatybilności – ustaw na podstawie `status == CONNECTED`
- Pole `details` (String JSON) zostaje bez zmian

### Zmiany w DTO `DeviceStatusResponse` (`model/dto/DeviceStatusResponse.java`)
```java
public record DeviceStatusResponse(
    String id,
    String deviceType,
    String status,          // DeviceCheckStatus.name()
    boolean connected,      // shorthand
    Instant lastCheckedAt,
    String details          // JSON string z dodatkowymi danymi
) {}
```

### Migracja Flyway
Dodaj `V2__add_device_status_column.sql` w `src/main/resources/db/migration/`:
```sql
ALTER TABLE device_status ADD COLUMN status TEXT DEFAULT 'UNKNOWN';
```

---

## SSE – nowe DTO zdarzeń

### `DeviceCheckEvent` (nowy: `model/dto/DeviceCheckEvent.java`)
```java
@Serdeable
public record DeviceCheckEvent(
    String deviceType,        // "EXTERNAL_DRIVE" | "IPHONE" | "ICLOUD"
    String status,            // DeviceCheckStatus.name()
    String stepDescription,   // czytelny opis kroku, np. "Szukam urządzeń blokowych..."
    String details,           // opcjonalne dane JSON lub komunikat błędu
    boolean terminal          // true = ostatnie zdarzenie dla tego sprawdzenia
) {}
```

Zdarzenia SSE mają nazwę (`Event.name()`):
- `"device-check-step"` – krok pośredni
- `"device-check-final"` – końcowy status (terminal=true)

---

## Nowe endpointy w `StatusController`

### Istniejące (do przebudowy)
```
POST /api/status/check-drive   → ZMIANA: teraz zwraca SSE stream zamiast blokującego JSON
POST /api/status/check-iphone  → ZMIANA: teraz zwraca SSE stream zamiast blokującego JSON
GET  /api/status/devices       → zostaje (ostatni zapisany stan z DB)
```

### Nowe endpointy
```
POST /api/status/check-icloud  → sprawdź dostępność icloud-service + sesji
GET  /api/status/devices/stream → SSE: stream ostatnich statusów wszystkich urządzeń (opcja długiego live-update)
```

### Sygnatura kontrolera (Micronaut SSE)
```java
@Post("/check-drive")
@Produces(MediaType.TEXT_EVENT_STREAM)
public Publisher<Event<DeviceCheckEvent>> checkDrive() {
    return deviceStatusService.checkDriveStream()
        .map(evt -> Event.of(evt).name(evt.terminal() ? "device-check-final" : "device-check-step"));
}

@Post("/check-iphone")
@Produces(MediaType.TEXT_EVENT_STREAM)
public Publisher<Event<DeviceCheckEvent>> checkIPhone() { ... }

@Post("/check-icloud")
@Produces(MediaType.TEXT_EVENT_STREAM)
public Publisher<Event<DeviceCheckEvent>> checkICloud() { ... }
```

**Usuń `@ExecuteOn(TaskExecutors.BLOCKING)`** z całego kontrolera – SSE w Micronaut działa reaktywnie.

---

## Przebudowa `DeviceStatusService`

### Metody do dodania/zastąpienia

```java
// Zamiast DeviceStatusResponse checkDrive() → blokująca
public Flux<DeviceCheckEvent> checkDriveStream();

// Zamiast DeviceStatusResponse checkIPhone() → blokująca
public Flux<DeviceCheckEvent> checkIPhoneStream();

// Nowe
public Flux<DeviceCheckEvent> checkICloudStream();
```

### Logika `checkDriveStream()` – sekwencja kroków
Emituj zdarzenie przed każdą operacją:

```
1. Emit: status=CHECKING, step="Sprawdzam punkt montowania /mnt/external-drive..."
   → sprawdź czy ścieżka istnieje (Files.isDirectory)

2a. Jeśli tak → Emit: status=CONNECTED, step="Dysk dostępny.", terminal=true
    → zapisz CONNECTED do DB

2b. Jeśli nie → Emit: status=CHECKING, step="Dysk nie zamontowany. Uruchamiam agenta wykrywania..."
    → wywołaj DiskDetectionAgent (AgentExecutor.run())
    → każdy AgentStepEvent z agenta mapuj na DeviceCheckEvent i emituj (status=MOUNTING/CHECKING)
    → na podstawie finalnego wyniku agenta: CONNECTED lub MOUNT_FAILED
    → Emit: terminal=true, odpowiedni status
    → zapisz do DB

3. Na każdym błędzie (wyjątek) → Emit: status=ERROR, details=message, terminal=true
```

### Logika `checkIPhoneStream()` – sekwencja kroków
```
1. Emit: status=CHECKING, step="Uruchamiam skrypt wykrywania iPhone'a..."
   → ShellExecutor.executeScript(scriptsDir, "detect-iphone.sh")

2a. Jeśli stdout zawiera "connected": true
    → Emit: status=CHECKING, step="Sprawdzam zaufanie urządzenia..."
    → wywołaj iphone-check-trust.sh
    → Emit: status=CONNECTED, step="iPhone podłączony: {device_name}", details=pełne JSON, terminal=true

2b. Jeśli "connected": false
    → Emit: status=DISCONNECTED, step="Nie wykryto iPhone'a.", terminal=true

3. Na błędzie → Emit: status=ERROR, terminal=true
```

### Logika `checkICloudStream()` – sekwencja kroków
```
1. Emit: status=CHECKING, step="Sprawdzam dostępność icloud-service..."
   → wywołaj ICloudServiceClient.health()

2a. HTTP 200 → Emit: status=CHECKING, step="Serwis dostępny. Sprawdzam aktywne sesje..."
    → wywołaj ICloudServiceClient.listSessions()
    → Emit: status=CONNECTED lub DISCONNECTED (czy są aktywne sesje), terminal=true

2b. Wyjątek/timeout → Emit: status=UNREACHABLE, step="icloud-service niedostępny.", terminal=true
```

### Mapowanie AgentStepEvent → DeviceCheckEvent (dla dysku)
```java
private DeviceCheckEvent mapAgentStep(AgentStepEvent agentStep) {
    boolean isFinal = "final".equals(agentStep.step().toString());
    String status = isFinal
        ? (Boolean.TRUE.equals(agentStep.success()) ? "CONNECTED" : "MOUNT_FAILED")
        : "MOUNTING";
    String desc = agentStep.action() != null
        ? "Agent: " + agentStep.action()
        : agentStep.message();
    return new DeviceCheckEvent("EXTERNAL_DRIVE", status, desc, agentStep.result(), isFinal);
}
```

---

## Zapis stanu do bazy danych

Po każdym `terminal=true` zdarzeniu zapisz stan do tabeli `device_status` (upsert przez `DeviceStatusRepository`).
Pola do zapisania: `status`, `isConnected` (= status==CONNECTED), `lastCheckedAt`, `details`.

Metoda pomocnicza w serwisie:
```java
private void persistStatus(String deviceType, DeviceCheckStatus status, String details) { ... }
```

---

## Testy (stubs)

Utwórz/zaktualizuj pliki testowe z opisowymi metodami i komentarzem `// TODO: Implement test`:

### `StatusControllerTest.java`
```java
void checkDriveReturnsSseStream()
void checkIPhoneReturnsSseStream()
void checkICloudReturnsSseStream()
void getDevicesReturnsLastKnownStatus()
```

### `DeviceStatusServiceTest.java`
```java
void checkDriveStream_whenMounted_emitsConnectedImmediately()
void checkDriveStream_whenNotMounted_invokesAgent()
void checkDriveStream_onError_emitsErrorEvent()
void checkIPhoneStream_whenConnected_emitsTrustCheck()
void checkIPhoneStream_whenDisconnected_emitsDisconnected()
void checkICloudStream_whenReachable_checksActiveSessions()
void checkICloudStream_whenUnreachable_emitsUnreachable()
void persistStatus_savesCorrectDeviceStatus()
```

---

## Co NIE ulega zmianie

- `GET /api/status/devices` – bez zmian (zwraca JSON z ostatnim stanem)
- `AgentController` i `AgentExecutor` – bez zmian
- `DiskDetectionAgent` – bez zmian
- Encje `Photo`, `ICloudAccount`, `VirtualFolder` – bez zmian
- Logika synchronizacji (`SyncService`) – bez zmian
- Autentykacja Basic Auth – bez zmian

---

## Kolejność implementacji (sugerowana)

1. `DeviceCheckStatus.java` (enum)
2. `DeviceCheckEvent.java` (DTO)
3. Migracja `V2__add_device_status_column.sql`
4. Zaktualizuj `DeviceStatus.java` (dodaj pole `status`)
5. Zaktualizuj `DeviceStatusResponse.java` (dodaj pole `status`)
6. Przebuduj `DeviceStatusService` (nowe metody Flux)
7. Przebuduj `StatusController` (SSE, usuń @ExecuteOn BLOCKING)
8. Utwórz/zaktualizuj testy (stubs)

---

## Definicja ukończenia tej fazy

- `POST /api/status/check-drive` zwraca SSE z co najmniej 2 zdarzeniami (krok + final)
- `POST /api/status/check-iphone` zwraca SSE z krokami
- `POST /api/status/check-icloud` zwraca SSE z krokami
- `GET /api/status/devices` zwraca pole `status` z wartością enuma
- Backend kompiluje się bez błędów (`./gradlew compileJava`)
- Testy stub istnieją dla wszystkich nowych metod

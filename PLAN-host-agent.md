# Plan: Host Agent — Python Unix Socket Daemon

## Kontekst i motywacja

Obecna architektura komunikacji Docker → Host:
- Backend uruchamia skrypty z `scripts/bridge/` przez `ShellExecutor`
- Bridge skrypty używają `pipe-call.sh` → named FIFO (`/bridge/cmd`) → `pipe-daemon.sh` na hoście
- `pipe-daemon.sh` wykonuje odpowiedni skrypt z `scripts/host/` (z sudo tam gdzie potrzeba)

**Problem:** named FIFO jest kruchy (brak retry, polling co 0.1s, race conditions, trudne do debugowania, brak structured error handling).

**Nowe podejście:** Pythonowy daemon `cloudsync-host-agent` osadzony bezpośrednio na serwerze, słuchający na Unix Domain Socket, akceptujący JSON komendy i zwracający JSON odpowiedzi. Backend komunikuje się z nim przez socket zamiast przez FIFO.

---

## 1. Nowy katalog: `host-agent/`

```
host-agent/
├── agent.py              # główny plik daemona
├── handlers/
│   ├── __init__.py
│   ├── drive.py          # check_drive, list_disks, mount_drive, unmount_drive, read_device_id
│   └── iphone.py         # detect_iphone, iphone_list_devices, iphone_check_trust,
│                         # iphone_get_info, iphone_mount, iphone_unmount
├── requirements.txt      # brak zewnętrznych zależności — tylko stdlib
├── install.sh            # skrypt instalacyjny (uruchamiany ręcznie)
├── cloudsync-host-agent.service  # systemd unit
└── README.md             # instrukcja instalacji i obsługi
```

---

## 2. Protokół komunikacji

### Request (JSON, jedna linia, zakończona `\n`)
```json
{"action": "mount_drive", "params": {"device": "/dev/sdb1", "mount_point": "/mnt/external-drive"}}
```

### Response (JSON, jedna linia, zakończona `\n`)
```json
{"ok": true, "data": {"mounted": true, "device": "/dev/sdb1", "mount_point": "/mnt/external-drive", "message": "..."}}
```
lub przy błędzie:
```json
{"ok": false, "error": "Device not found", "code": "MOUNT_FAILED"}
```

Pole `data` ma **ściśle określoną strukturę** dla każdej akcji — patrz modele poniżej.

### Akcje — mapowanie na obecne skrypty

| Akcja JSON           | Odpowiednik w `scripts/host/` | Parametry                          | Wymaga root |
|----------------------|-------------------------------|------------------------------------|-------------|
| `check_drive`        | `check-drive.sh`              | `mount_path` (opcjonalny)          | nie         |
| `list_disks`         | `list-disks.sh`               | —                                  | nie         |
| `mount_drive`        | `mount-drive.sh`              | `device`, `mount_point` (opcj.)    | **tak**     |
| `unmount_drive`      | `unmount-drive.sh`            | `mount_point` (opcjonalny)         | **tak**     |
| `read_device_id`     | `read-device-id.sh`           | `device`                           | **tak**     |
| `detect_iphone`      | `detect-iphone.sh`            | —                                  | nie         |
| `iphone_list_devices`| `iphone-list-devices.sh`      | —                                  | nie         |
| `iphone_check_trust` | `iphone-check-trust.sh`       | `udid` (opcjonalny)                | nie         |
| `iphone_get_info`    | `iphone-get-info.sh`          | `udid` (opcjonalny)                | nie         |
| `iphone_mount`       | `iphone-mount.sh`             | `mount_path` (opcjonalny)          | nie (ifuse) |
| `iphone_unmount`     | `iphone-unmount.sh`           | `mount_path` (opcjonalny)          | nie (fuse)  |

---

## 3. Implementacja `agent.py`

- `asyncio`-based Unix Domain Socket server (`asyncio.start_unix_server`)
- Jedna połączenie = jedno żądanie (connection per request, keep-alive opcjonalne)
- Dispatch po polu `action` do odpowiedniego handlera
- Każdy handler wykonuje logikę inline w Pythonie (nie wywołuje już shell skryptów — logika jest przepisana bezpośrednio)
- Timeout na każde żądanie (np. 30s)
- Logi do `journald` przez `logging` (handler `StreamHandler` na stdout — systemd to zbiera)
- Konfiguracja przez `/etc/cloudsync-host-agent/config.json` lub zmienne środowiskowe:
  - `SOCKET_PATH` (default: `/run/cloudsync-host-agent/agent.sock`)
  - `EXTERNAL_DRIVE_PATH` (default: `/mnt/external-drive`)
  - `IPHONE_MOUNT_PATH` (default: `/mnt/iphone`)
  - `CONTAINER_UID` / `CONTAINER_GID` (default: `1000`) — dla chown po montowaniu ext4

### Modele odpowiedzi (Python `dataclass` + `dataclasses.asdict` do serializacji)

Każdy handler zwraca konkretny dataclass, który agent serializuje do JSON przez `dataclasses.asdict`. Nie ma gołych `dict` w warstwie biznesowej.

```python
# models.py

@dataclass
class DriveStatus:
    available: bool
    path: str | None
    free_bytes: int | None

@dataclass
class DiskInfo:
    name: str
    path: str
    size: str
    type: str
    mountpoint: str | None
    label: str | None
    vendor: str
    model: str

@dataclass
class MountDriveResult:
    mounted: bool
    device: str
    mount_point: str
    message: str

@dataclass
class UnmountDriveResult:
    success: bool
    message: str

@dataclass
class DeviceIdResult:
    uuid: str | None
    label: str | None
    device: str

@dataclass
class IPhoneDetectResult:
    connected: bool
    device_name: str | None
    udid: str | None

@dataclass
class IPhoneListDevicesResult:
    devices: list[str]

@dataclass
class IPhoneTrustResult:
    trusted: bool
    udid: str | None
    message: str

@dataclass
class IPhoneInfoResult:
    udid: str
    device_name: str
    product_type: str
    ios_version: str
    serial_number: str
    total_capacity_bytes: int | None
    free_bytes: int | None
    battery_percent: int | None

@dataclass
class IPhoneMountResult:
    mounted: bool
    mount_path: str | None
    udid: str | None
    error: str | None

@dataclass
class IPhoneUnmountResult:
    unmounted: bool
    error: str | None
```

### Uprawnienia dla operacji uprzywilejowanych

Daemon uruchamiany jest jako dedykowany user `cloudsync-agent` (nie root), ale:
- Należy do grup: `disk`, `plugdev`, `fuse`
- Dla operacji wymagających roota (`mount`, `umount`, `blkid`) — używa `sudo` z NOPASSWD regułą w `/etc/sudoers.d/cloudsync-host-agent`
- Alternatywa: uruchomić daemona jako root (prostsze, ale mniej bezpieczne — do decyzji podczas instalacji)

**Rekomendacja:** uruchomić jako root via systemd (`User=root` w unit file) bo to daemon systemowy z dobrze zdefiniowanym zakresem — jest izolowany przez Unix socket z właściwymi uprawnieniami do socketu.

---

## 4. `install.sh`

Kroki (skrypt nieinteraktywny, wymaga `sudo`):

1. Sprawdź dependencje: `python3` >= 3.9, `ifuse`, `libimobiledevice-utils`, `blkid`
2. Utwórz katalog `/opt/cloudsync-host-agent/` i skopiuj pliki
3. Utwórz katalog socketu `/run/cloudsync-host-agent/` (lub via `RuntimeDirectory` w systemd)
4. Zainstaluj unit systemd: skopiuj `.service` do `/etc/systemd/system/`
5. `systemctl daemon-reload && systemctl enable --now cloudsync-host-agent`
6. Wypisz status i ścieżkę socketu

---

## 5. `cloudsync-host-agent.service`

```ini
[Unit]
Description=CloudSync Host Agent
After=network.target

[Service]
Type=simple
User=root
ExecStart=/usr/bin/python3 /opt/cloudsync-host-agent/agent.py
Restart=on-failure
RestartSec=5
RuntimeDirectory=cloudsync-host-agent
RuntimeDirectoryMode=0755
StandardOutput=journal
StandardError=journal
SyslogIdentifier=cloudsync-host-agent

[Install]
WantedBy=multi-user.target
```

`RuntimeDirectory` tworzy `/run/cloudsync-host-agent/` automatycznie przy starcie i usuwa przy stopie — socket ląduje pod `/run/cloudsync-host-agent/agent.sock`.

---

## 6. Zmiany w backendzie (Java)

### 6a. Modele odpowiedzi (Java records, pakiet `com.cloudsync.client.hostmodel`)

Jeden record per akcja — lustrzane odbicie Pythonowych dataclassów. Deserializowane przez Micronaut Serde z pola `data` w odpowiedzi agenta.

```java
// Dysk zewnętrzny
public record DriveStatus(boolean available, @Nullable String path, @Nullable Long freeBytes) {}
public record DiskInfo(String name, String path, String size, String type,
                       @Nullable String mountpoint, @Nullable String label,
                       String vendor, String model) {}
public record MountDriveResult(boolean mounted, String device, String mountPoint, String message) {}
public record UnmountDriveResult(boolean success, String message) {}
public record DeviceIdResult(@Nullable String uuid, @Nullable String label, String device) {}

// iPhone
public record IPhoneDetectResult(boolean connected, @Nullable String deviceName, @Nullable String udid) {}
public record IPhoneListDevicesResult(List<String> devices) {}
public record IPhoneTrustResult(boolean trusted, @Nullable String udid, String message) {}
public record IPhoneInfoResult(String udid, String deviceName, String productType,
                                String iosVersion, String serialNumber,
                                @Nullable Long totalCapacityBytes, @Nullable Long freeBytes,
                                @Nullable Integer batteryPercent) {}
public record IPhoneMountResult(boolean mounted, @Nullable String mountPath,
                                 @Nullable String udid, @Nullable String error) {}
public record IPhoneUnmountResult(boolean unmounted, @Nullable String error) {}
```

### 6b. Nowy `HostAgentClient.java` (`com.cloudsync.client`)

Zastępuje bezpośrednie wywołania `ShellExecutor` dla operacji hosta. Metody zwracają konkretne rekordy.

```java
@Singleton
public class HostAgentClient {
    // Niskopoziomowe: serializuje request, deserializuje data do podanego typu
    private <T> T call(String action, Map<String, Object> params, Class<T> responseType);

    // Wysokopoziomowe API — każda metoda zwraca konkretny record:
    public DriveStatus checkDrive(String mountPath);
    public List<DiskInfo> listDisks();
    public MountDriveResult mountDrive(String device, String mountPoint);
    public UnmountDriveResult unmountDrive(String mountPoint);
    public DeviceIdResult readDeviceId(String device);
    public IPhoneDetectResult detectIphone();
    public IPhoneMountResult iphoneMount(String mountPath);
    public IPhoneUnmountResult iphoneUnmount(String mountPath);
    public IPhoneTrustResult iphoneCheckTrust(@Nullable String udid);
    public IPhoneInfoResult iphoneGetInfo(@Nullable String udid);
    public IPhoneListDevicesResult iphoneListDevices();
}
```

Gdy `ok=false` w odpowiedzi agenta — rzuca `HostAgentException(error, code)`.

**Socket path** konfigurowalny przez `app.host-agent-socket` w `application.yml`, default `/run/cloudsync-host-agent/agent.sock`.

**Transport:** Java 16+ ma `UnixDomainSocketAddress` (JEP-380). Backend używa Java 21 — brak dodatkowych dependencji.

### 6b. Modyfikacje istniejących serwisów

**`DiskSetupService`:**
- `listDisks()` → `hostAgentClient.listDisks()` zamiast `shell.executeScript(..., "list-disks.sh")`
- `mountAndRegister(device)` → `hostAgentClient.mountDrive(device, hostMountPath)`
- `unmount()` → `hostAgentClient.unmountDrive(hostMountPath)`
- `findMountedDevice()` → `hostAgentClient.readDeviceId(device)` (blkid przez agenta)
- Operacje `df` (freeBytes, sizeBytes) zostają jako `shell.execute("bash", "-c", "df ...")` — te działają lokalnie w kontenerze

**`DeviceStatusService`:**
- `checkDriveStream()` → `hostAgentClient.checkDrive(externalDrivePath)`
- `checkIPhoneStream()` → `hostAgentClient.detectIphone()`, `hostAgentClient.iphoneCheckTrust()`, `hostAgentClient.iphoneMount()`
- `unmountIPhone()` → `hostAgentClient.iphoneUnmount(iphoneMountPath)`

**`IPhoneSyncProvider`:**
- `doScan()` → `hostAgentClient.iphoneMount(iphoneMountPath)` przy re-mount

**`ShellExecutor`** — pozostaje w użyciu dla lokalnych operacji kontenerowych (df, findmnt). Nie jest usuwany.

### 6c. `application.yml` — nowy klucz

```yaml
app:
  host-agent-socket: ${HOST_AGENT_SOCKET:/run/cloudsync-host-agent/agent.sock}
```

### 6d. `compose.yaml` / `compose.dev.yaml`

- Dodać bind-mount socketu do backendu:
  ```yaml
  volumes:
    - /run/cloudsync-host-agent:/run/cloudsync-host-agent:ro
  ```
- Usunąć stary bind-mount `/var/run/cloudsync-bridge:/bridge`
- Dodać zmienną środowiskową `HOST_AGENT_SOCKET` jeśli ścieżka niestandardowa

---

## 7. Co zostaje, co odpada

| Element                        | Status       |
|-------------------------------|--------------|
| `scripts/host/*.sh`            | **Zostaje** — logika przepisana w Python, ale skrypty jako dokumentacja/backup |
| `scripts/bridge/`              | **Odpada** — cały katalog do usunięcia po migracji |
| `scripts/bridge/pipe-call.sh`  | **Odpada** |
| `pipe-daemon.sh` (host)        | **Odpada** — zastępowany przez `host-agent/agent.py` |
| `ShellExecutor.java`           | **Zostaje** — używany do lokalnych operacji df/findmnt |
| Bind-mount `/bridge`           | **Odpada** z compose |

---

## 8. Instrukcja instalacji (`host-agent/README.md`)

Treść:
1. Wymagania: Python 3.9+, `ifuse`, `libimobiledevice-utils` (`apt install ifuse libimobiledevice-utils`)
2. Klonowanie / kopiowanie `host-agent/` na serwer
3. `cd host-agent && sudo ./install.sh`
4. Weryfikacja: `systemctl status cloudsync-host-agent`
5. Test ręczny: `echo '{"action":"list_disks","params":{}}' | socat - UNIX-CONNECT:/run/cloudsync-host-agent/agent.sock`
6. Logi: `journalctl -u cloudsync-host-agent -f`
7. Aktualizacja: `sudo ./install.sh` (idempotentny, restartuje serwis)

---

## 9. Kolejność implementacji

1. `host-agent/agent.py` + `handlers/drive.py` + `handlers/iphone.py`
2. `host-agent/cloudsync-host-agent.service`
3. `host-agent/install.sh`
4. `host-agent/README.md`
5. `HostAgentClient.java` w backendzie
6. Modyfikacje `DiskSetupService`, `DeviceStatusService`, `IPhoneSyncProvider`
7. Zmiany w `compose.yaml` / `compose.dev.yaml` i `application.yml`
8. Testy integracyjne ręczne (`socat` + backend w dev mode)
9. Usunięcie `scripts/bridge/` i aktualizacja `CLAUDE.md`

---

## 10. Ryzyka i uwagi

- **Socket permissions:** socket musi być dostępny dla kontenera. Opcje: `chmod 666` na socket lub mapowanie GID. `RuntimeDirectory` w systemd tworzy katalog jako root — socket będzie `root:root 660`. Najprostsze: `chmod 666` w ExecStartPost lub `SocketMode=0666` (jeśli używamy socket activation).
- **WSL2:** `/run/` nie jest współdzielony między WSL2 i hostem Windows — na WSL2 dev należy użyć innej ścieżki (np. `/tmp/cloudsync-agent.sock`). Dev compose może overridować `HOST_AGENT_SOCKET`.
- **Restart policy:** systemd `Restart=on-failure` — daemon wstaje sam po crashu.
- **Concurrent requests:** `asyncio` + `start_unix_server` obsługuje wiele równoległych połączeń bez blokowania. Operacje mountowania (synchroniczne syscalle) są krótkie.
- **Atomowość odpowiedzi:** każde połączenie = jedno request/response — brak potrzeby message framing poza `\n`.

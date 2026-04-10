# Analiza problemu uprawnień w kontenerze Docker

## Problem

POST `/api/context/mkdir` z body `{parentPath: "/mnt/external-drive", name: "browse"}` zwraca 500:
```json
{"message": "Access denied: /mnt/external-drive/browse", "error": "ACCESS_DENIED"}
```

Błąd pochodzi z `AppContextController.java:168` – Java rzuca `AccessDeniedException`, który jest mapowany na odpowiedź `ACCESS_DENIED`.

---

## Diagnoza

### Użytkownik kontenera

`Dockerfile.dev` bazuje na `gradle:8-jdk21` i kończy instrukcje z `USER gradle`. Użytkownik `gradle` w tym obrazie ma **UID=1000, GID=1000**.

### Uprawnienia katalogu mock-drive

```
drwxr-xr-x  2 root root  4096  backend/dev-drive
```

Katalog `dev-drive` jest własnością **root:root** z uprawnieniami **755** (`rwxr-xr-x`). Oznacza to:
- `root` może czytać i pisać
- `gradle` (UID 1000, "other") może tylko **czytać i wchodzić** (brak `w` dla `others`)

### Bind-mount zachowuje uprawnienia hosta

W `backend/compose.dev.yaml`:
```yaml
volumes:
  - ./dev-drive:/mnt/external-drive
```

Bind-mount **nie zmienia uprawnień** — kontener widzi dokładnie te same atrybuty `uid:gid` i bity chmod co host. Żaden mechanizm Dockera nie mapuje automatycznie uprawnień przy zwykłym bind-mount.

### Scenariusz produkcyjny (rshared)

W głównym `compose.dev.yaml`:
```yaml
volumes:
  - type: bind
    source: /mnt/external-drive
    target: /mnt/external-drive
    bind:
      propagation: rshared
```

Tu problem jest analogiczny ale inny w naturze: `/mnt/external-drive` na hoście jest tworzony przez `create_host_path: true` — Docker tworzy ten katalog jako **root**. Kiedy host podmontowuje fizyczny dysk pod ten punkt, kontener widzi system plików dysku. Uprawnienia zależą wtedy od systemu plików dysku (np. FAT32/exFAT montuje domyślnie jako root z 755/644).

---

## Możliwości naprawy

### Opcja A — Fix uprawnień na hoście (szybki fix dla mocka)

Najprościej: zmienić właściciela `dev-drive` na UID 1000 (gradle user w kontenerze):

```bash
# w katalogu backend/
sudo chown -R 1000:1000 dev-drive
```

lub dać zapis wszystkim (mniej bezpieczne, ale w dev akceptowalne):

```bash
chmod 777 backend/dev-drive
```

**Wada:** `sudo chown` trzeba powtarzać ręcznie po `docker compose down -v` i ponownym `up --build` jeśli katalog jest tworzony od nowa. `chmod 777` to OK w dev.

**Zaleta:** Zero zmian w kodzie/compose, działa od razu.

---

### Opcja B — Dockerfile.dev tworzy katalog z właściwymi uprawnieniami

Dodać do `Dockerfile.dev` tworzenie punktu montowania zanim jeszcze bind-mount nadpisze jego zawartość:

```dockerfile
USER root
RUN mkdir -p /mnt/external-drive && chown gradle:gradle /mnt/external-drive
USER gradle
```

**Uwaga:** Bind-mount **nadpisuje punkt montowania** – katalog `/mnt/external-drive` wewnątrz kontenera (z prawidłowymi uprawnieniami z obrazu) zostaje przykryty katalogiem z hosta. Po zamontowaniu widoczne są uprawnienia hosta, nie obrazu. Ta opcja **nie rozwiązuje** problemu sama w sobie dla bind-mount.

Jest jednak przydatna dla scenariusza z **named volume** (opcja D).

---

### Opcja C — `user:` w compose.dev.yaml (uruchomienie kontenera jako root)

```yaml
services:
  backend:
    user: "root"
```

lub jako bieżący użytkownik hosta:

```yaml
services:
  backend:
    user: "${UID}:${GID}"
```

**Wada opcji root:** Złe praktyki bezpieczeństwa, Gradle też działa jako root.  
**Wada opcji UID hosta:** Gradle cache (`/home/gradle/.gradle`) jest własnością `gradle:gradle` w obrazie — UID hosta może nie mieć do niego dostępu. Wymagałoby to dodatkowych zmian w volumach.

---

### Opcja D — Named volume zamiast bind-mount (dla mocka)

Zamienić bind-mount `./dev-drive` na named volume:

```yaml
volumes:
  - dev-drive-data:/mnt/external-drive

volumes:
  dev-drive-data:
    driver: local
```

Docker przy named volume **tworzy katalog jako root ale kopiuje uprawnienia** z obrazu jeśli punkt montowania istnieje w obrazie z właściwymi uprawnieniami. Razem z opcją B (tworzenie `/mnt/external-drive` z `chown gradle:gradle` w Dockerfile) — kontener miałby pełne prawa zapisu.

**Wada:** Dane mock-drive nie są widoczne bezpośrednio na hoście (trudniejszy debugging). Named volume jest zarządzany przez Dockera.  
**Zaleta:** Czysty setup, brak problemów z uprawnieniami, działa po `docker compose up --build`.

---

### Opcja E — Entrypoint script fixujący uprawnienia (dla scenariusza rshared)

Dla scenariusza produkcyjnego (`rshared` + rzeczywisty dysk) problem jest trudniejszy bo uprawnienia zależą od zamontowanego systemu plików. Rozwiązanie: backend po wykryciu zamontowanego dysku wywołuje skrypt który remontuje z opcjami `uid`/`gid`:

W `mount-drive.sh` (dla FAT32/exFAT):
```bash
mount -o uid=1000,gid=1000,umask=022 /dev/sdX /mnt/external-drive
```

Dla ext4:
```bash
chown 1000:1000 /mnt/external-drive
```

**Wada:** Skrypty montowania wymagają `sudo`/root, co i tak jest już założeniem (skrypty są wykonywane przez root w kontenerze lub mają suid). Wymaga zmian w skryptach dla każdego FS.

---

## Rekomendacja dla obecnego stanu (mock)

**Natychmiastowy fix:** `chmod 777 backend/dev-drive`

**Docelowe rozwiązanie dla mock (w compose):** Opcja D — named volume + Dockerfile tworzy mountpoint z prawidłowymi uprawnieniami. Eliminuje problem raz na zawsze bez ręcznych kroków.

**Docelowe rozwiązanie dla produkcji (rshared):** Opcja E — skrypt montowania przekazuje `uid=1000,gid=1000` dla FAT/exFAT lub `chown` dla ext4 jako część `mountAndRegister`.

---

## Dlaczego `dev-drive` jest własnością root?

Docker podczas `docker compose up` z bind-mount `./dev-drive:/mnt/external-drive` i opcją `create_host_path: true` (lub kiedy katalog nie istnieje) tworzy go przez demona Dockera działającego jako **root**. Stąd `root:root`.

W `backend/compose.dev.yaml` opcji `create_host_path` nie ma wprost, ale efekt jest ten sam — jeśli katalog nie istniał, Docker go tworzy jako root. Jeśli istniał (jak teraz), zachowuje jego aktualne uprawnienia.

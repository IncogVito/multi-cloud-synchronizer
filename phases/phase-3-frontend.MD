# Phase 3: Frontend (Angular/Vite)

## Cel
Interfejs użytkownika: delikatny, minimalistyczny design UX. Logowanie, statusy urządzeń, przeglądanie zdjęć, operacje batch.

## Design System

### Filozofia
- Czysty, jasny, minimalistyczny design.
- Delikatne cienie, zaokrąglone rogi, dużo white space.
- Animacje subtelne (transitions 200-300ms).
- Paleta neutralna z jednym kolorem akcentowym.

### Globalne style (styles/global.scss)
```scss
:root {
  // Colors
  --color-primary: #4A90D9;
  --color-primary-hover: #357ABD;
  --color-success: #27AE60;
  --color-warning: #F2994A;
  --color-danger: #EB5757;
  --color-bg: #F8F9FA;
  --color-surface: #FFFFFF;
  --color-text: #2D3436;
  --color-text-secondary: #636E72;
  --color-border: #E0E0E0;

  // Spacing
  --space-xs: 4px;
  --space-sm: 8px;
  --space-md: 16px;
  --space-lg: 24px;
  --space-xl: 32px;
  --space-2xl: 48px;

  // Typography
  --font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  --font-size-sm: 0.875rem;
  --font-size-md: 1rem;
  --font-size-lg: 1.25rem;
  --font-size-xl: 1.5rem;
  --font-size-2xl: 2rem;

  // Borders & Shadows
  --border-radius: 8px;
  --border-radius-lg: 12px;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.08);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.1);
  --shadow-lg: 0 8px 24px rgba(0,0,0,0.12);

  // Transitions
  --transition-fast: 150ms ease;
  --transition-normal: 250ms ease;
}
```

### Klasy utility
- `.card` – biała karta z shadow-sm, border-radius, padding.
- `.btn` – bazowy przycisk.
- `.btn-primary`, `.btn-danger`, `.btn-ghost` – warianty.
- `.status-badge` – kolorowy badge statusu (`.connected`, `.disconnected`, `.pending`).
- `.container` – centered max-width container.
- `.grid-photos` – CSS grid do wyświetlania thumbnailów.
- `.section-header` – nagłówek sekcji z opcjonalnymi akcjami.

## Routing & Struktura modułów
```
/                          → redirect do /dashboard
/login                     → logowanie do aplikacji (Basic Auth)
/dashboard                 → główna strona z wyborem konta i statusami
/photos                    → przeglądanie zdjęć
/photos?account=X&year=Y   → filtrowane widoki
```

### Moduły Angular
```
src/app/
├── core/
│   ├── services/
│   │   ├── auth.service.ts          # logowanie do aplikacji
│   │   ├── api.service.ts           # bazowy HTTP client
│   │   ├── account.service.ts       # zarządzanie kontami iCloud
│   │   ├── photo.service.ts         # operacje na zdjęciach
│   │   ├── device-status.service.ts # status urządzeń
│   │   └── sse.service.ts           # obsługa Server-Sent Events
│   ├── guards/
│   │   └── auth.guard.ts
│   ├── interceptors/
│   │   └── auth.interceptor.ts      # dodaje Basic Auth header
│   └── models/
│       ├── photo.model.ts
│       ├── account.model.ts
│       └── device-status.model.ts
├── shared/
│   ├── components/
│   │   ├── status-badge/            # badge z kolorowym statusem
│   │   ├── photo-thumbnail/         # thumbnail z checkbox selection
│   │   ├── loading-spinner/
│   │   └── confirm-dialog/          # modal potwierdzenia
│   └── pipes/
│       ├── file-size.pipe.ts        # formatowanie rozmiaru pliku
│       └── relative-date.pipe.ts    # "2 days ago"
├── features/
│   ├── login/
│   │   └── login.component.ts       # prosty formularz login
│   ├── dashboard/
│   │   ├── dashboard.component.ts   # główny widok
│   │   ├── account-selector/        # wybór/dodanie konta iCloud
│   │   ├── device-status-panel/     # panel statusów urządzeń
│   │   └── icloud-login-modal/      # modal logowania do iCloud + 2FA
│   └── photos/
│       ├── photos.component.ts      # widok zdjęć
│       ├── photo-grid/              # siatka thumbnailów
│       ├── photo-timeline/          # grupowanie wg lat/miesięcy
│       ├── photo-detail-modal/      # podgląd pełnego zdjęcia
│       ├── batch-actions-bar/       # pasek akcji batch (usuń z iCloud/iPhone)
│       └── granularity-toggle/      # przełącznik Year/Month
└── app.component.ts
```

## Ekrany – szczegóły

### 1. Login (/login)
- Prosty formularz: username, password.
- Logowanie do aplikacji (Basic Auth backend).
- Po zalogowaniu → redirect do /dashboard.

### 2. Dashboard (/dashboard)
**Panel statusów urządzeń (górna część):**
- Trzy status-badge obok siebie:
  - 🟢/🔴 External Drive – "Connected" / "Not detected" + przycisk "Re-check"
  - 🟢/🔴 iPhone – "Connected" / "Not connected" + przycisk "Re-check"
  - 🟢/🔴 iCloud – "Logged in (user@icloud.com)" / "Not connected"
- Przy kliknięciu "Re-check" na dysku → SSE stream z krokami agenta wyświetlany jako mały log w tooltipie/expandable sekcji.

**Wybór konta iCloud (środkowa część):**
- Lista zalogowanych kont (karty).
- Przycisk "+ Add iCloud Account" → otwiera modal.
- Modal: apple_id + password → jeśli 2FA → pole na kod.
- Po zalogowaniu konto pojawia się na liście.

**Quick stats:**
- Ilość zsynchronizowanych zdjęć.
- Zajęte miejsce na dysku.
- Ostatnia synchronizacja.

### 3. Photos (/photos)
**Toolbar (góra):**
- Dropdown: wybór konta iCloud.
- Granularity toggle: Year | Month (przełącznik wizualny).
- Przycisk "Sync Now" → triggeruje synchronizację.
- Wyszukiwarka (by filename) – `// OPEN: na MVP może pominąć.`

**Timeline View:**
- Zdjęcia pogrupowane w sekcje.
- Każda sekcja ma header: np. "2024" lub "January 2024".
- Header zawiera: nazwę, liczbę zdjęć, checkbox "select all in section".

**Photo Grid:**
- CSS Grid, responsywny (auto-fill, minmax ~150px).
- Każdy thumbnail:
  - Kwadratowy, object-fit: cover.
  - Overlay checkbox (widoczny on hover lub gdy tryb selekcji aktywny).
  - Mała ikonka typu (📷 photo, 🎥 video).
  - Klik → otwiera modal z pełnym zdjęciem + metadane.

**Batch Actions Bar (sticky bottom, widoczny gdy coś zaznaczone):**
- "X items selected"
- Przycisk "Delete from iCloud" (aktywny tylko jeśli wszystkie zaznaczone mają synced_to_disk=true).
- Przycisk "Delete from iPhone" (aktywny tylko jeśli wszystkie zaznaczone mają synced_to_disk=true).
- Przycisk "Clear selection".
- Przed usunięciem → modal potwierdzenia z listą zaznaczonych.

**Photo Detail Modal:**
- Pełnoekranowe zdjęcie.
- Metadane: nazwa, rozmiar, data, wymiary.
- Statusy: ☁️ on iCloud, 📱 on iPhone, 💾 on disk.
- Przyciski: "Delete from iCloud", "Delete from iPhone" (z warunkami jak wyżej).

## Komunikacja z backendem
- Wszystkie requesty przez ApiService → bazowy URL z konfiguracji environment.
- AuthInterceptor dodaje Basic Auth credentials (przechowywane w sessionStorage).
- SSE dla detekcji dysku: użyj EventSource API.
- `// OPEN: WebSocket dla real-time status updates?`

## Testy
- Twórz pliki .spec.ts dla każdego komponentu, serwisu i pipe'a.
- Testy puste z `it('should ...')` i komentarzem `// TODO: Implement test`.
- Minimum describe block z prawidłowymi importami i TestBed setup.

## Definicja ukończenia
- Użytkownik może się zalogować do aplikacji.
- Dashboard wyświetla statusy urządzeń (z danymi z backendu).
- Można dodać konto iCloud i przejść 2FA.
- Zdjęcia wyświetlane jako thumbnails pogrupowane wg lat/miesięcy.
- Selekcja zdjęć i batch delete działa.
- Design jest spójny z design systemem.

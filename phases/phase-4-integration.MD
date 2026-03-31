# Phase 4: Integracja & Polish

## Cel
Weryfikacja end-to-end, poprawki, dokumentacja, przygotowanie do deploymentu.

## Zadania

### Integracja end-to-end
- Przetestuj pełny flow: `docker-compose up` → login → dodanie konta iCloud → sync → przeglądanie → batch delete.
- Napraw wszelkie problemy z komunikacją między serwisami.
- Zweryfikuj volumes i montowanie dysku zewnętrznego.

### Error handling
- Upewnij się, że wszystkie błędy z mikroserwisu są sensownie propagowane na frontend.
- Toast notifications na frontendzie dla error/success.
- Graceful handling gdy dysk jest niedostępny.

### Dokumentacja
- Zaktualizuj README.md z pełną instrukcją.
- Dodaj sekcję "Configuration" z opisem zmiennych środowiskowych.
- Dodaj sekcję "Architecture" z diagramem (ASCII art lub opis).
- Dodaj sekcję "Development" z instrukcją uruchomienia bez Dockera.

### Performance
- Lazy loading thumbnailów (intersection observer).
- Paginacja zdjęć (infinite scroll lub klasyczna).
- `// OPEN: Cache headers na thumbnailach.`

### Skrypty CI/CD
- `// OPEN: GitHub Actions workflow do budowania obrazów Docker.`
- Przygotuj Makefile lub skrypty helper: `make build`, `make up`, `make down`, `make logs`.

## Definicja ukończenia
- Pełny flow działa bez błędów z `docker-compose up`.
- Dokumentacja kompletna.
- Wszystkie pliki testowe (stuby) istnieją.
- Projekt gotowy do przekazania do uzupełnienia testów przez secondary models.

---

## Nota dla modelu uzupełniającego testy
Wszystkie pliki testowe w projekcie zawierają puste metody testowe oznaczone `// TODO: Implement test`. Twoje zadanie: zaimplementuj każdy test jednostkowy. Korzystaj z mocków (Mockito/JUnit 5 dla Javy, pytest + unittest.mock dla Pythona, Jasmine/Karma lub Jest dla Angulara). Testuj logikę biznesową, nie infrastrukturę. Każdy test powinien mieć arrange/act/assert (given/when/then). Nie modyfikuj kodu produkcyjnego.

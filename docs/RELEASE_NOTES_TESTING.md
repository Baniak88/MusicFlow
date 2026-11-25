## MusicFlow — wersja testowa / test build

**Nowości w tej wersji (beta.5):**
- **Przenikanie bez ubytków dźwięku:** końcówka utworu jest buforowana kilka sekund wcześniej,
  a przełączenie następuje dopiero, gdy faktycznie gra. Gdyby nie zdążyła się załadować,
  przejście odbywa się bez przenikania, ale też bez dziury.
- **Lepsza jakość dźwięku:** domyślnie najlepsza jakość (Opus ok. 160 kb/s także na danych
  komórkowych zamiast AAC 128 kb/s). Normalizacja w trybach Cicho/Normalnie tylko ścisza zbyt
  głośne utwory, bez limitera w torze dźwięku. Podbijanie cichych utworów tylko w trybie Głośno.
  Uwaga: jeśli wcześniej zmieniałeś jakość w Ustawieniach, ustaw „Najlepsza dostępna”.
- **Nowy wygląd i ikona:** fioletowy motyw, „Kontynuuj słuchanie” z animacją odtwarzanego
  utworu, mini odtwarzacz połączony z nawigacją, nowy odtwarzacz („Odtwarzanie na: <urządzenie>”,
  udostępnianie, „Odtwarzanie z: <źródło>”), nowa ikona aplikacji.

- **Automatyczne aktualizacje NewPipe Extractor:** co 6 godzin serwer budowania sprawdza, czy
  YouTube działa, pobiera nowszą wersję NewPipe Extractor, testuje ją i publikuje nową wersję
  testową. Aplikacja sama sprawdza (co kilka godzin), czy jest nowsza wersja i pokazuje baner
  „Dostępna nowa wersja… — Pobierz” na ekranie głównym. Można to wyłączyć w Ustawieniach
  („Sprawdzaj aktualizacje aplikacji”), jest też „Sprawdź aktualizacje teraz”.

**Wcześniej (beta.4):** normalizacja głośności, poprawki nawigacji, albumy na stronach
wykonawców, mały plik (~9,5 MB), stały klucz podpisu (aktualizacja bez odinstalowywania).

**EN:** Test build (pre-release). Download the `MusicFlow-*.apk` below and install it on
Android 9+. See the tester guide (docs/TESTY.md, Polish) and TESTING.md for the full test plan.
A `-beta` APK is an optimised test build signed with a stable CI test key; newer betas
install over it.

`SHA256SUMS.txt` contains checksums of the attached files.

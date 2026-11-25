## MusicFlow — wersja testowa / test build

**Poprawki w beta.10:**
- SoundCloud: wyszukiwanie nie kończy się już błędem „Wystąpił nieoczekiwany błąd”, gdy w którejś
  kategorii nic nie znaleziono — pokazuje to, co znalazło. Jeśli pojawi się jakiś nieoczekiwany
  błąd, pod komunikatem widać teraz jego techniczną przyczynę (przydatne przy zgłoszeniach).

**Nowości w beta.9:**
- **SoundCloud:** w wynikach wyszukiwania wybierz źródło „SoundCloud” (obok „YouTube Music”).
  Działają utwory, wykonawcy, playlisty/albumy, podobne utwory i wklejone linki soundcloud.com.
  Utwory tylko dla SoundCloud Go+ (30-sekundowy podgląd) są oznaczane jako niedostępne.
- **Aktualizacje w tle:** co ok. 6 godzin aplikacja sama sprawdza, pobiera, weryfikuje
  (suma kontrolna i podpis) i instaluje nowszą wersję — w tym nowe wersje NewPipe Extractor
  dla YouTube. Za pierwszym razem Android poprosi o zgodę („Instalowanie nieznanych aplikacji”
  dla MusicFlow — przycisk w Ustawieniach); na Androidzie 12+ kolejne aktualizacje instalują
  się już bez pytania. Baner na ekranie głównym ma teraz przycisk „Zainstaluj”.
  W Ustawieniach widać wersję NewPipe Extractor; aktualizacje w tle można wyłączyć.

**Beta.8:** pierwsza wersja instalowana jako **aktualizacja** na beta.7 (ten sam podpis,
sprawdzony przed publikacją). Jeśli masz beta.6 lub starszą, trzeba ją jeden raz odinstalować
(najpierw Ustawienia → Kopia zapasowa → Eksport), zainstalować tę wersję i zrobić Import.
Zmiany względem beta.7: brak (wersja kontrolna aktualizacji).

**Ważne (beta.7): ostatni raz trzeba odinstalować starą wersję.** Wcześniejsze wersje testowe
były przez błąd podpisywane za każdym razem innym kluczem, dlatego Android nie pozwalał ich
zaktualizować. Od beta.7 klucz jest stały, a serwer budowania sprawdza podpis przed każdą
publikacją — kolejne wersje (beta.8 i dalej) zainstalują się na tej bez odinstalowywania.
Przed odinstalowaniem możesz zachować bibliotekę: Ustawienia → Kopia zapasowa → Eksport,
a po instalacji Import.

**Poprawki w beta.7:**
- **Kompresja dynamiki działa stale:** efekty dźwiękowe telefonu (np. Samsung) potrafiły
  przejąć lub wyłączyć kompresję MusicFlow — w ustawieniach była włączona, ale nie działała,
  dopóki się jej nie wyłączyło i włączyło. Teraz aplikacja sama to wykrywa i przywraca
  kompresję (a także korektor i podbicie cichych utworów) przy każdym starcie odtwarzania
  i zmianie utworu.

**Wcześniej (beta.6):**
- **Przenikanie jak w Spotify:** poprzedni utwór zawsze płynnie wycisza się do samego końca,
  a następny płynnie się pojawia — nawet jeśli następny utwór z YouTube ładuje się 1–2 sekundy.
  Wcześniej w takiej sytuacji poprzedni utwór urywał się w połowie przenikania.
- Krótkie doładowywanie następnego utworu nie przerywa już przenikania, a moment przejęcia
  dźwięku jest zsynchronizowany (bez „zająknięcia” na początku przenikania).
- Wskazówka: przenikanie włączasz w Ustawienia → Odtwarzanie → Przenikanie (np. 6–8 s).
  Działa przy automatycznym przejściu do następnego utworu (jak w Spotify), nie przy ręcznym
  przeskakiwaniu.

**Wcześniej (beta.5):** automatyczne aktualizacje NewPipe Extractor i powiadomienie o nowej
wersji w aplikacji, nowy wygląd i ikona, lepsza jakość dźwięku. (beta.4): normalizacja
głośności, poprawki nawigacji.

**EN:** Test build (pre-release). Download the `MusicFlow-*.apk` below and install it on
Android 9+. See the tester guide (docs/TESTY.md, Polish) and TESTING.md for the full test plan.
A `-beta` APK is an optimised test build signed with a stable CI test key (verified against
the previous beta before publishing); from beta.7 on, newer betas install over it.

`SHA256SUMS.txt` contains checksums of the attached files.

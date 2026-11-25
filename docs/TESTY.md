# MusicFlow — instrukcja dla testerów

## 1. Instalacja

1. Otwórz na telefonie stronę **Releases** repozytorium i wybierz najnowszą wersję oznaczoną
   jako *Pre-release*.
2. Pobierz plik `MusicFlow-<wersja>-beta.apk` (lub `MusicFlow-<wersja>.apk`).
3. Otwórz pobrany plik. Jeśli Android zapyta, zezwól przeglądarce/menedżerowi plików na
   *instalowanie nieznanych aplikacji*.
4. Wymagany jest Android 9 lub nowszy. Wersja testowa instaluje się jako osobna aplikacja
   „MusicFlow” (identyfikator `app.musicflow.beta`).
5. **Aktualizacja:** od wersji beta.7 nowszą wersję instalujesz na starszą, bez
   odinstalowywania (biblioteka zostaje). Przy przejściu z beta.6 lub starszej na beta.7 trzeba
   jeden raz odinstalować starą wersję (wcześniej każda wersja miała inny podpis).

Alternatywa: zakładka **Actions → Android CI → najnowsze uruchomienie → Artifacts →
`MusicFlow-debug-<numer>`** (plik ZIP z APK, wymaga zalogowania do GitHuba).

## 2. Co sprawdzić (lista kontrolna)

Zaznacz ✅ / ❌ i dopisz uwagi.

**Podstawy**
- [ ] Aplikacja uruchamia się, widać zakładki Główna / Odkrywaj / Szukaj / Biblioteka.
- [ ] Ustawienia → Język: przełączenie na Polski i English działa.
- [ ] Nawigacja: wejdź w podstronę (np. wykonawca albo Ustawienia) i stuknij tę samą zakładkę —
      wracasz do jej ekranu głównego; zakładka jest cały czas podświetlona; przycisk Wstecz
      cofa w obrębie zakładki, potem do Głównej.
- [ ] Ustawienia → Motyw: ciemny, jasny, czysta czerń (AMOLED).

**Muzyka online (eksperymentalna — YouTube Music)**
- [ ] Wyszukiwanie utworu, wykonawcy, albumu, playlisty; filtry i przewijanie kolejnych wyników.
- [ ] Utwory, których nie ma w oficjalnym katalogu (np. „D-Bomb Powiedz mi co czujesz”) są
      w wynikach; wklejenie linku z YouTube w wyszukiwarkę od razu pokazuje utwór.
- [ ] Odtworzenie utworu z wyników; na odtwarzaczu widać kodek i bitrate (np. „OPUS · 160 kbps”).
- [ ] Strona wykonawcy i albumu/playlisty.
- [ ] Ekran Główna po przesłuchaniu kilku utworów pokazuje rekomendacje.
- [ ] Odkrywaj → wybrana kategoria pokazuje playlisty i utwory.
- [ ] Tekst utworu (ikona tekstu w odtwarzaczu).

**Muzyka z telefonu**
- [ ] Biblioteka → Na tym urządzeniu: po udzieleniu zgody widać pliki audio; odtwarzanie działa.

**Odtwarzanie w tle (najważniejsze)**
- [ ] Muzyka gra po wyjściu z aplikacji i po wyłączeniu ekranu (min. 10 minut).
- [ ] Sterowanie z powiadomienia i ekranu blokady.
- [ ] Słuchawki Bluetooth: przyciski play/pauza/następny; odłączenie słuchawek wstrzymuje muzykę.
- [ ] Połączenie przychodzące wstrzymuje muzykę, a po rozmowie wraca.
- [ ] Przełączenie Wi-Fi ↔ dane komórkowe w trakcie odtwarzania — muzyka wznawia się sama.
- [ ] Android Auto (jeśli dostępne): przeglądanie i odtwarzanie.
- [ ] Wyłącznik czasowy zatrzymuje muzykę.
- [ ] Normalizacja głośności: przełączaj utwory z różnych lat/gatunków — głośność powinna być
      podobna; w odtwarzaczu (linia z formatem) widać „Normalizacja: … dB”. Sprawdź też
      poziomy Cicho / Normalnie / Głośno i Wyłączona.
- [ ] Przenikanie (Ustawienia → Odtwarzanie → Przenikanie, np. 6 s): koniec utworu płynnie
      nakłada się na początek następnego; powiadomienie pokazuje już nowy utwór.
- [ ] Jakość dźwięku: w odtwarzaczu stuknij linię z formatem (np. „OPUS · 160 kbps”) lub ⋮ →
      Jakość dźwięku → zmiana działa od razu, utwór gra dalej od tego samego miejsca.

**Biblioteka i playlisty**
- [ ] Polubienie utworu, utworzenie playlisty, dodanie utworów, zmiana kolejności przeciąganiem,
      usunięcie, zmiana nazwy.
- [ ] Import playlisty z linku YouTube Music; import/eksport pliku M3U.
- [ ] Ustawienia → Kopia zapasowa: eksport i przywrócenie.
- [ ] Biblioteka działa bez internetu (tryb samolotowy).

**Aktualizacje**
- [ ] Ustawienia → O aplikacji → „Sprawdź aktualizacje teraz”: komunikat „Masz najnowszą
      wersję” albo „Dostępna wersja …”. Gdy jest nowsza wersja, na ekranie głównym pojawia się
      baner „Dostępna nowa wersja MusicFlow … — Pobierz”; „Pobierz” otwiera plik APK w przeglądarce.
- [ ] Po wyłączeniu „Sprawdzaj aktualizacje aplikacji” baner się nie pojawia.
- [ ] Ustawienia → „Zezwól MusicFlow na instalowanie aktualizacji” → włącz. Gdy wyjdzie nowsza
      wersja, w ciągu kilku godzin powinna zainstalować się sama (Android 12+) albo pojawić się
      baner „Zainstaluj”. Biblioteka i ustawienia zostają.

**Radio i MusicBrainz**
- [ ] Odkrywaj → Radio → „Popularne radia w Twoim kraju”: stacja gra, w powiadomieniu po chwili
      pojawia się tytuł aktualnego utworu.
- [ ] Wyszukaj „rmf” albo „jazz” i wybierz źródło „Radio Browser”.
- [ ] Wyszukaj „OK Computer” ze źródłem „MusicBrainz” → album Radiohead z okładką i listą
      utworów; utwór z albumu gra (przez YouTube Music) albo pojawia się komunikat, że nie
      znaleziono pewnego nagrania.
- [ ] Strona wykonawcy z YouTube (np. Radiohead): sekcja „O wykonawcy · MusicBrainz” i link
      „Pełna dyskografia w MusicBrainz”.

**SoundCloud**
- [ ] Wyszukaj coś, w wynikach wybierz „SoundCloud”: utwory, wykonawcy, playlisty.
- [ ] Odtwarzanie utworu z SoundCloud (także w tle i z przenikaniem), strona wykonawcy, playlista.
- [ ] Wklej link soundcloud.com w wyszukiwarkę – otwiera ten utwór/playlistę.

## 3. Zgłaszanie błędów

1. Odtwórz problem, potem **Ustawienia → Diagnostyka → ikona eksportu** i zapisz plik
   (dziennik nie zawiera haseł, ciasteczek ani tokenów).
2. Utwórz zgłoszenie w zakładce **Issues** repozytorium: model telefonu, wersja Androida,
   wersja aplikacji (Ustawienia → Wersja), kroki, co się stało, czego oczekiwałeś; dołącz dziennik
   i ewentualnie zrzut ekranu.

## 4. Znane ograniczenia wersji testowej

- YouTube Music działa przez nieoficjalny interfejs (NewPipe Extractor) i może okresowo przestać
  działać; muzyka z telefonu działa zawsze. Szczegóły: `PROVIDERS.md`.
- Utwory z ograniczeniem wiekowym, tylko dla członków kanału lub tylko dla YouTube Music Premium
  nie są odtwarzane (aplikacja nie loguje się do konta).
- Część testów (60 min odtwarzania, Bluetooth, połączenia, bateria) nie była jeszcze wykonana na
  fizycznym telefonie — to właśnie Twoje zadanie. Dziękujemy!

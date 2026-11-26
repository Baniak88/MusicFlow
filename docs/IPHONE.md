# MusicFlow na iPhone'a — instalacja krok po kroku

Wersja na iPhone'a to osobna aplikacja (Swift/SwiftUI, kod w katalogu `ios/`). Apple nie
dopuści jej do App Store, bo odtwarza YouTube Music bez oficjalnej aplikacji. Dlatego
instaluje się ją samodzielnie z pliku **`.ipa`** („sideloading”), podpisanego Twoim
własnym Apple ID.

> **Wymagania**
> - iPhone z **iOS 17** lub nowszym.
> - Komputer z Windows albo macOS: potrzebny do pierwszej instalacji.
> - Apple ID. Wystarczy darmowe konto, ale wtedy podpis trzeba odnawiać co **7 dni**
>   (opis niżej).

## 1. Pobierz aplikację

1. Otwórz stronę [Releases](https://github.com/Baniak88/MusicFlow/releases) repozytorium i wybierz najnowsze wydanie
   **„MusicFlow iOS …”**.
2. Pobierz plik `MusicFlow-iOS-<wersja>.ipa`.
   - Na komputer, jeśli instalujesz przez Sideloadly.
   - Na iPhone'a (do aplikacji *Pliki*), jeśli instalujesz przez AltStore lub SideStore.

## 2. Zainstaluj: wybierz jeden sposób

### Sposób A: AltStore (polecany, sam odnawia podpis)

1. Na komputerze zainstaluj **AltServer** ze strony altstore.io.
   - Na Windows potrzebne są też iTunes i iCloud pobrane ze strony Apple, a nie z Microsoft Store.
2. Podłącz iPhone'a kablem, odblokuj go i stuknij **„Ufaj temu komputerowi”**.
3. W ikonie AltServer (pasek zadań lub pasek menu) wybierz **Install AltStore** → Twój
   iPhone i zaloguj się swoim Apple ID.
4. Na iPhonie włącz **Tryb dewelopera**: Ustawienia → Prywatność i ochrona → Tryb dewelopera.
   iPhone uruchomi się ponownie; po restarcie potwierdź włączenie.
5. Otwórz **AltStore** → zakładka **My Apps** → **+** → wybierz pobrany plik
   `MusicFlow-iOS-….ipa`.
6. Gotowe. Odnawianie podpisu co 7 dni:
   - AltStore robi to sam przez Wi-Fi, gdy na komputerze w tej samej sieci działa AltServer;
   - albo ręcznie: My Apps → **Refresh All**.

### Sposób B: Sideloadly (prosty, z komputera)

1. Zainstaluj **Sideloadly** (sideloadly.io).
   - Na Windows potrzebne są też iTunes i iCloud ze strony Apple.
2. Podłącz iPhone'a kablem i stuknij **„Ufaj temu komputerowi”**.
3. Przeciągnij plik `.ipa` do okna Sideloadly, wpisz swoje Apple ID i kliknij **Start**.
4. Na iPhonie zrób dwie rzeczy:
   - Ustawienia → Ogólne → **VPN i zarządzanie urządzeniem** → wybierz swoje Apple ID →
     **Ufaj**;
   - włącz **Tryb dewelopera** (Ustawienia → Prywatność i ochrona).
5. Co 7 dni powtórz kroki 2–3: wgranie na istniejącą aplikację zachowuje bibliotekę.

### Sposób C: SideStore

Działa jak AltStore, ale po jednorazowej konfiguracji z komputerem odnawia podpis na samym
telefonie. Instrukcja jest na stronie sidestore.io.

### Płatne konto Apple Developer (opcjonalnie)

Z płatnym kontem (99 USD rocznie) podpis jest ważny **rok**, a nie 7 dni. Aplikację można
wtedy też rozprowadzać przez TestFlight. Jeśli założysz takie konto, daj znać: dodam
podpisywanie do budowania na GitHubie (certyfikat trafi tylko do sekretów repozytorium).

## 3. Aktualizacje

Nowe wydanie instaluje się tak samo jak pierwsze, „na” istniejącą aplikację. Biblioteka
(polubione, playlisty, historia) zostaje, o ile używasz tego samego Apple ID i tego samego
narzędzia.

Wersja na iPhone'a nie aktualizuje się sama, tak jak robi to wersja na Androida: system
iOS na to nie pozwala.

## Co działa w pierwszej wersji (etap 1)

- Wyszukiwanie i odtwarzanie z **YouTube Music**: utwory i teledyski, AAC 128 kb/s albo
  48 kb/s w trybie oszczędzania danych.
- **Radio** z katalogu Radio Browser:
  - wyszukiwanie po nazwie, mieście i gatunku;
  - popularne stacje w Polsce;
  - tytuł aktualnie granego utworu (jeśli stacja go podaje).
- Katalog **MusicBrainz**: utwór gra z YouTube Music tylko wtedy, gdy znajdzie się to samo
  nagranie (tytuł, wykonawca i długość). W przeciwnym razie aplikacja wyświetli komunikat.
- **Odtwarzanie w tle**, na ekranie blokady i w Centrum sterowania. Działają też przyciski na
  słuchawkach Bluetooth i w samochodzie. Odłączenie słuchawek wstrzymuje muzykę.
- **Kolejka**: odtwórz jako następny, dodaj do kolejki, losowo, powtarzanie, zmiana
  kolejności i usuwanie.
- **Biblioteka**: polubione, playlisty, historia. Wszystko zapisane tylko na iPhonie.
- Szklany wygląd jak na Androidzie, język polski i angielski.

**Etap 2 (później):**
- SoundCloud;
- crossfade i korektor;
- eksport i import biblioteki między Androidem a iPhone'em;
- tekst piosenek;
- CarPlay jako osobna aplikacja na ekranie auta. Na razie działa sterowanie odtwarzaniem.

## Gdy coś nie działa

- **„Nie można zweryfikować aplikacji” / aplikacja się nie otwiera.** Upłynęło 7 dni:
  odśwież podpis w AltStore albo wgraj ponownie przez Sideloadly.
- **YouTube przestał grać.** YouTube zmienił swoje API. Napisz: poprawka wymaga nowej
  wersji aplikacji.
- **Radio milczy.** Niektóre stacje nadają tylko przez HTTP. Sprawdź w Ustawieniach, czy
  włączone są „Nieszyfrowane strumienie radia”.

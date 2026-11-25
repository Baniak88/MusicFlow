# Klucz wydań MusicFlow — instrukcja krok po kroku

Klucz wydań podpisuje finalne wersje aplikacji (`MusicFlow-<wersja>.apk`, identyfikator
`app.musicflow`). Android instaluje aktualizację tylko wtedy, gdy jest podpisana **tym samym
kluczem** — dlatego tworzy się go **raz** i przechowuje bezpiecznie przez cały czas życia
aplikacji.

> **Zasady bezpieczeństwa**
> - Pliku klucza (`.jks`) ani haseł **nigdy** nie wrzucaj do repozytorium, czatu, e-maila
>   ani zgłoszenia na GitHubie. Trafiają wyłącznie do *sekretów* repozytorium (krok 4).
> - Zgubienie klucza = koniec aktualizacji (użytkownicy musieliby odinstalować aplikację).
>   Wyciek klucza = ktoś może podpisać fałszywą „aktualizację”.
> - Potrzebny jest komputer (Windows, macOS lub Linux). Nie generuj klucza na cudzym komputerze.

---

## Krok 1. Przygotuj program `keytool`

`keytool` jest częścią Javy.

- **Masz Android Studio:** `keytool` już jest:
  - Windows: `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"`
  - macOS: `"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"`
- **Nie masz:** zainstaluj darmową Javę *Eclipse Temurin 21* ze strony adoptium.net
  (podczas instalacji na Windows zaznacz „Add to PATH”). Potem `keytool` działa w terminalu.

Sprawdzenie (Windows: PowerShell, macOS/Linux: Terminal):

```bash
keytool -help
```

Powinna się pokazać lista poleceń. Jeśli „nie rozpoznano polecenia” — użyj pełnej ścieżki
podanej wyżej (w cudzysłowie, na Windows poprzedzonej znakiem `&`, np.
`& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -help`).

## Krok 2. Wymyśl i zapisz hasło

Jedno silne hasło (min. 16 znaków, najlepiej wygenerowane w menedżerze haseł). Używasz go
**dwa razy** — dla magazynu kluczy i dla samego klucza (w nowym formacie PKCS12 muszą być
takie same). Od razu zapisz je w menedżerze haseł.

Unikaj w haśle znaków `"`, `'`, `` ` ``, `$` i `\` — łatwo o pomyłkę przy wklejaniu.

## Krok 3. Utwórz klucz

Otwórz terminal w folderze, w którym chcesz zapisać plik (np. utwórz folder
`MusicFlow-klucz` w Dokumentach), i wykonaj **jedno** polecenie:

```bash
keytool -genkeypair -v -keystore musicflow-release.jks -storetype PKCS12 -alias musicflow -keyalg RSA -keysize 4096 -validity 10000
```

Program zapyta kolejno:

| Pytanie (ang.) | Co wpisać |
|---|---|
| Enter keystore password | Twoje hasło (nie widać znaków podczas pisania — to normalne) |
| Re-enter new password | to samo hasło |
| What is your first and last name? | np. `MusicFlow` (to nie musi być imię i nazwisko) |
| organizational unit / organization | można zostawić puste (Enter) |
| City / State / country code | można zostawić puste lub wpisać `PL` jako kraj |
| Is CN=… correct? | `yes` |

Na końcu pojawi się `Storing musicflow-release.jks`. Plik klucza jest gotowy.

## Krok 4. Zamień klucz na tekst (Base64)

GitHub przechowuje sekrety jako tekst, więc plik trzeba zakodować.

**Windows (PowerShell, w tym samym folderze):**

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\musicflow-release.jks")) | Set-Content -NoNewline keystore.b64
```

(Nie używaj `certutil -encode` — dopisuje nagłówki, przez które GitHub nie odczyta klucza.)

**macOS:**

```bash
base64 -i musicflow-release.jks | tr -d '\n' > keystore.b64
```

**Linux:**

```bash
base64 -w0 musicflow-release.jks > keystore.b64
```

Powstanie plik `keystore.b64` z jedną długą linią tekstu.

## Krok 5. Dodaj 4 sekrety na GitHubie

1. Otwórz repozytorium **Baniak88/MusicFlow** na github.com (w przeglądarce na komputerze).
2. **Settings** → w menu po lewej **Secrets and variables** → **Actions**.
3. Zakładka **Secrets** → przycisk **New repository secret** — dodaj kolejno:

| Name (dokładnie tak) | Secret (wartość) |
|---|---|
| `MUSICFLOW_KEYSTORE_BASE64` | cała zawartość pliku `keystore.b64` (otwórz go w Notatniku, Ctrl+A, Ctrl+C) |
| `MUSICFLOW_KEYSTORE_PASSWORD` | Twoje hasło |
| `MUSICFLOW_KEY_ALIAS` | `musicflow` |
| `MUSICFLOW_KEY_PASSWORD` | Twoje hasło (to samo) |

Po dodaniu GitHub pokazuje tylko nazwy sekretów — wartości nie da się już podejrzeć
(i nie widzi ich nikt, także Claude).

## Krok 6. Kopia zapasowa i sprzątanie

1. Skopiuj `musicflow-release.jks` w **dwa bezpieczne miejsca**, np. zaszyfrowany pendrive
   i menedżer haseł z załącznikami / zaszyfrowany dysk w chmurze. Hasło trzymaj osobno.
2. Usuń z komputera plik `keystore.b64` (to ten sam klucz, tylko jako tekst) — opróżnij też
   kosz.

## Krok 7. Daj znać

Napisz, że sekrety są dodane. Wtedy:

- uruchomię wydanie **MusicFlow 1.0.0** (`MusicFlow-1.0.0.apk`, identyfikator `app.musicflow`),
- sprawdzę w logach GitHuba, że podpis pochodzi z Twojego klucza (pokażę jego odcisk SHA-256),
- przełączę automatyczne aktualizacje (NewPipe Extractor i baner w aplikacji) na wersje finalne.

Wersja 1.0.0 to **nowa aplikacja obok wersji testowej** — przenieś bibliotekę przez
Ustawienia → Kopia zapasowa → Eksport (w wersji beta) i Import (w wersji 1.0.0), a potem
odinstaluj wersję beta. Kolejne wersje (1.0.1, 1.0.2…) będą już zwykłymi aktualizacjami.

---

### Sprawdzenie odcisku klucza (opcjonalnie)

Żeby porównać z tym, co pokażę z GitHuba:

```bash
keytool -list -v -keystore musicflow-release.jks -alias musicflow
```

Szukaj linii `SHA256:`.

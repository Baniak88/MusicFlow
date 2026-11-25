## MusicFlow — wersja testowa / test build

**Nowości w tej wersji (beta.4):**
- **Normalizacja głośności** jak w Spotify (Ustawienia → Odtwarzanie → Normalizacja głośności;
  domyślnie *Normalnie*). Każdy utwór gra z tą samą głośnością, na podstawie głośności zmierzonej
  przez YouTube albo tagów ReplayGain w plikach lokalnych. Zastosowaną wartość widać w odtwarzaczu
  (stuknij linię z formatem).
- **Nawigacja:** podwójne stuknięcie nie otwiera już ekranu dwa razy, podwójne „wstecz” nie cofa
  o dwa ekrany, przejścia są szybkie (wcześniej 0,7 s przenikania), stuknięcie polubionego
  utworu w Bibliotece go odtwarza, a w Szukaj nie wyskakuje sama klawiatura.
- Strony wykonawców pokazują albumy i single.
- **Mniejszy plik (ok. 9,5 MB zamiast 84 MB)** i stały klucz podpisu: od tej wersji kolejne bety
  instalują się na poprzednią bez odinstalowywania (biblioteka zostaje).
- Uwaga: beta.4 to nowa aplikacja „MusicFlow” (`app.musicflow.beta`); poprzednie bety
  (`app.musicflow.debug`) możesz odinstalować.

**EN:** Test build (pre-release). Download the `MusicFlow-*.apk` below and install it on
Android 9+. See the tester guide (docs/TESTY.md, Polish) and TESTING.md for the full test plan.
A `-beta` APK is an optimised test build signed with a stable CI test key; newer betas
install over it.

`SHA256SUMS.txt` contains checksums of the attached files.

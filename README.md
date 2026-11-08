# MyTube

YouTube ohne Shorts und ohne Werbung – als eigenständige Android-App, die die
offizielle YouTube-App ersetzt. Angemeldet bleiben, Empfehlungen sehen, aber keine Shorts.

Wie und warum: siehe [KONZEPT.md](KONZEPT.md).

---

## Für Freunde: App installieren

1. Auf dem Handy die neueste `MyTube-x.y.z.apk` von der
   [Releases-Seite](https://github.com/Phoenix-153/MyTube/releases/latest) herunterladen.
2. Die Datei antippen. Android fragt, ob der Browser Apps installieren darf → **Zulassen**.
3. **Installieren** antippen und MyTube öffnen. Der Einrichtungsassistent führt durch den Rest:
   - YouTube-Links für MyTube freischalten
   - die YouTube-App deaktivieren
4. In MyTube oben rechts mit dem Google-Konto anmelden.

Updates meldet die App selbst (einmal täglich). Man bleibt dabei angemeldet.

---

## Für Entwickler: selbst bauen

### Voraussetzungen
- JDK 17 oder neuer
- Android SDK mit Platform `android-37.2` (am einfachsten: Android Studio installieren)
- `local.properties` mit `sdk.dir=C:/Users/<Name>/AppData/Local/Android/Sdk`
  (legt Android Studio automatisch an)

### Bauen
```bash
./gradlew assembleDebug
```
Ergebnis: `app/build/outputs/apk/debug/app-debug.apk`

### Direkt aufs eigene Handy (USB)
1. Auf dem Handy: *Einstellungen → Über das Telefon* → 7× auf *Build-Nummer* tippen →
   *Entwickleroptionen → USB-Debugging* einschalten.
2. Handy per USB verbinden, Anfrage am Handy bestätigen.
3. Installieren:
```bash
./gradlew installDebug
```

Alternativ die APK-Datei aufs Handy kopieren und dort antippen.

### Release erstellen (zum Weitergeben)
Die Release-APK wird mit `mytube-release.keystore` signiert. Die Zugangsdaten stehen in
`keystore.properties`. **Beide Dateien sind absichtlich nicht im Git** – sicher sichern
(z. B. Passwort-Manager + USB-Stick). Ohne sie kann niemand mehr Updates installieren,
ohne die App neu einzurichten.

1. In `app/build.gradle.kts` `versionCode` um 1 und `versionName` erhöhen (z. B. `1.1.0`).
2. Bauen:
   ```bash
   ./gradlew assembleRelease
   ```
3. `app/build/outputs/apk/release/app-release.apk` in `MyTube-1.1.0.apk` umbenennen.
4. Auf GitHub ein Release mit dem Tag **`v1.1.0`** anlegen und die APK anhängen.
   Die App findet es automatisch über die GitHub-API.

### Wartung
- **Shorts tauchen wieder auf:** YouTube hat die Seite umgebaut. Selektoren in
  `app/src/main/assets/shorts-blocker/content.css` ergänzen (Anleitung steht in der Datei).
- **GeckoView aktualisieren** (Sicherheitsupdates, etwa alle 1–3 Monate): Version in
  `app/build.gradle.kts` anheben, neue Versionen stehen unter
  https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-arm64-v8a/

### Aufbau
| Datei | Aufgabe |
|---|---|
| `App.kt` | Browser-Engine (GeckoRuntime), installiert Shorts-Blocker und uBlock Origin |
| `MainActivity.kt` | Browser-Bildschirm, Shorts-Sperre, Links, Vollbild, Zurück-Taste |
| `OnboardingActivity.kt` | Einrichtungsassistent beim ersten Start |
| `Updater.kt` | Update-Prüfung über GitHub Releases |
| `assets/shorts-blocker/` | Erweiterung, die Shorts ausblendet und Klicks abfängt |

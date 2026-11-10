# MyTube – Konzept

## Ziel
Eine Android-App, die man installiert, einmal anmeldet und die dann die offizielle
YouTube-App ersetzt: vollständiges mobiles YouTube (m.youtube.com) mit Login und
Empfehlungen, aber **ohne Shorts** und **ohne Werbung** – ohne dass Freunde und
Bekannte etwas einrichten müssen (kein Firefox, keine Add-ons, keine Systemänderung).

Zweck: Selbstkontrolle. Weitergabe als APK (Sideloading, kein Play Store).

## Technik
- Kotlin, GeckoView (Mozilla) statt Android-WebView – Google blockiert den Login im WebView
- minSdk 26 (Android 8), nur `arm64-v8a` (hält die APK bei ca. 90 MB)
- Eine GeckoRuntime pro App-Prozess, dauerhaftes Profil → Login bleibt erhalten
- Keine Hintergrunddienste, keine Bedienungshilfen-Berechtigung

## Shorts (Variante c: überall ausblenden, hart sperren falls man doch hinkommt)
1. **Eingebaute Erweiterung** `shorts-blocker` (CSS + kleines Skript):
   blendet Shorts-Regale, Shorts-Tab, Shorts in Suche/Kanälen aus und fängt Klicks
   auf Shorts-Links ab, bevor YouTube sie öffnet.
2. **Sicherheitsnetz in Kotlin**: landet man trotzdem auf einer Shorts-URL
   (z. B. über einen geteilten Link), wird zur Startseite umgeleitet und
   „Shorts sind gesperrt“ angezeigt.
3. Alle Selektoren stehen an einer Stelle (`content.css` / `content.js`).

## Werbung
uBlock Origin wird beim ersten Start automatisch aus dem offiziellen
Firefox-Add-on-Store installiert und täglich aktualisiert.
Hinweis: verstößt gegen die YouTube-Nutzungsbedingungen.

## Grundfunktionen
- Zurück-Taste, Vollbild für Videos inkl. Querformat
- Nur YouTube- und Google-Login-Seiten bleiben in der App, alle anderen Links
  öffnen sich im normalen Browser
- Übernimmt YouTube-Links (youtube.com, youtu.be) und „Teilen → MyTube“
- Deep Link `mytube://open` (optional `?url=…`)

## Funktionen wie in der YouTube-App
- **Teilen**: YouTubes Teilen-Knopf öffnet das Android-Teilen-Menü
- **Pull-to-refresh**: ganz oben nach unten ziehen lädt neu (nicht auf der Videoseite)
- **Sprachsuche**: Mikrofon-Knopf in der Kopfzeile → Android-Spracheingabe → YouTube-Suche
- **Miniplayer**: zwei Tabs (GeckoSessions) – Stöbern und Video. Videos laufen ganz
  normal im Stöber-Tab (sofortiger Start). Sobald eins läuft, lädt der versteckte
  Video-Tab es pausiert vor. Nach unten wischen über dem Video oder Zurück: Der
  Stöber-Tab geht zurück, der Video-Tab spielt an derselben Stelle in einer schwebenden
  Karte unten rechts weiter (Pause, Schließen, Titel, Fortschrittsbalken). Die Karte
  lässt sich in jede Ecke ziehen; seitlich hinauswischen schließt; antippen vergrößert.
  Kanal- und Suchlinks aus dem vergrößerten Video öffnen im Stöber-Tab. Es läuft immer
  nur ein Video.

## Weitergabe
- APKs als **GitHub Releases** in diesem (öffentlichen) Repo
- Die App prüft einmal täglich auf eine neue Version und bietet das Update an
- Fester Release-Signaturschlüssel – **sicher aufbewahren**, sonst keine Updates mehr möglich
- Einrichtungsassistent beim ersten Start: Anmelden, YouTube-Links freischalten,
  YouTube-App deaktivieren
- Eigener Name und eigenes Icon, kein YouTube-Logo (Markenrecht)

## Vorerst nicht dabei
Einstellungen, Management-Webapp, Hintergrundwiedergabe, Bild-im-Bild, Uploads.
Nicht machbar bzw. gegen das Konzept: Chromecast, Benachrichtigungen bei neuen Videos,
Offline-Downloads.

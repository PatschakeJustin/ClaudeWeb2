# DevDeck

Android-App (Smartphone und Tablet) zum Programmieren unterwegs, mit Claude als Coding-Agent.

## Funktionen

| Bereich | Was es kann |
|---|---|
| **Claude** | Chat mit einem Coding-Agenten (Claude-API). Er hat Zugriff auf deine Verbindungen: Befehle per SSH ausführen, Dateien per SFTP oder Nextcloud lesen, schreiben und gezielt bearbeiten. Befehle und Änderungen fragt die App vorher ab (abschaltbar). |
| **Terminal** | Vollwertiges SSH-Terminal (xterm.js) mit Sondertasten (Esc, Tab, Ctrl, Pfeile …). Über ⋮ startest du das echte **Claude Code** (`claude`) auf dem Server, installierst es oder öffnest eine tmux-Sitzung. |
| **Dateien** | Dateibrowser für SSH (SFTP) und Nextcloud (WebDAV) mit Editor (Zeilennummern, Speichern, Anlegen, Löschen per langem Tippen). Auf dem Tablet zweispaltig. |
| **Verbindungen** | SSH (Passwort oder Schlüssel), Nextcloud (App-Passwort), RDP. RDP-Verbindungen öffnen sich in einer installierten Remote-Desktop-App (z. B. Microsoft „Windows App“). Claude selbst hat auf RDP keinen Zugriff. |
| **Einstellungen** | API-Key, Modell, Gründlichkeit (effort), automatische Freigabe. |

Alle Zugangsdaten werden mit einem Schlüssel aus dem Android Keystore (AES-256-GCM) verschlüsselt und nur auf dem Gerät gespeichert. Sie sind vom Backup ausgenommen. SSH-Host-Schlüssel werden beim ersten Verbinden gespeichert („trust on first use“). Ändert sich ein Host-Schlüssel später, bricht die App die Verbindung ab.

## Installieren

1. Im Repository unter **Actions → Android build** den neuesten erfolgreichen Lauf öffnen.
2. Unter *Artifacts* `DevDeck-debug-apk` herunterladen und entpacken.
3. `app-debug.apk` auf dem Android-Gerät öffnen. Dafür muss „Installation aus unbekannten Quellen“ für den Browser bzw. Dateimanager erlaubt sein.

Voraussetzung: Android 8.0 oder neuer.

## Einrichten

1. **Einstellungen:** Anthropic-API-Key eintragen (console.anthropic.com → API Keys).
2. **Verbindungen:** Server anlegen und mit „Verbindung testen“ prüfen.
3. **Claude:** z. B. „Zeig mir den git status der Projekte auf webserver und lass die Tests laufen.“

## Aufbau

```
core/   Reines Kotlin (JVM), testbar ohne Android:
        SSH/SFTP (sshj), Nextcloud-WebDAV (OkHttp), Claude-Agent (Anthropic Java SDK, Streaming + Werkzeuge)
app/    Android-App (Jetpack Compose), xterm.js-Terminal in einer WebView
```

Bauen:

```bash
./gradlew :core:test            # Tests: eingebetteter SSH-Server, WebDAV-Mock, simulierte Claude-API
./gradlew :app:assembleDebug    # APK (braucht das Android SDK)
SKIP_ANDROID=1 ./gradlew :core:test   # nur core, ohne Android SDK
```

Lizenzen von Drittkomponenten: xterm.js (MIT, `app/src/main/assets/terminal/LICENSE-xterm.txt`).

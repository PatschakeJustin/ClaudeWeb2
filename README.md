# DevDeck

Android-App (Smartphone und Tablet) zum Programmieren unterwegs, mit Claude als Coding-Agent.

## Funktionen

| Bereich | Was es kann |
|---|---|
| **Claude – Abo** | Chat mit dem echten **Claude Code** auf deinem Server (`claude -p` über SSH). Nutzt dein **Claude-Abo (Pro/Max)**, das auf dem Server angemeldet ist – kein API-Key nötig. Server, Arbeitsordner und Berechtigungsstufe (nur planen / Dateien ändern / alles) wählst du oben im Chat; Folgefragen setzen dieselbe Sitzung fort. |
| **Server einrichten** | Unter *Verbindungen → Server einrichten*: Zugangsdaten eintragen, DevDeck installiert Claude Code (plus curl, git, tmux) per SSH mit Live-Protokoll und führt dich danach im Terminal durch die Anmeldung mit deinem Claude-Abo. |
| **Claude – API** | Alternativ: Chat mit einem Coding-Agenten über die Claude-API (eigener API-Key). Er hat Zugriff auf deine Verbindungen: Befehle per SSH ausführen, Dateien per SFTP oder Nextcloud lesen, schreiben und gezielt bearbeiten. Befehle und Änderungen fragt die App vorher ab (abschaltbar). |
| **Terminal** | Vollwertiges SSH-Terminal (xterm.js) mit Sondertasten (Esc, Tab, Ctrl, Pfeile …). Über ⋮ startest du das echte **Claude Code** (`claude`) auf dem Server, installierst es oder öffnest eine tmux-Sitzung. Das Link-Symbol listet alle Links aus der Ausgabe (z. B. den Login-Link) zum Öffnen/Kopieren; „Einfügen“ und „Kopieren“ in der Tastenleiste. |
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

**Mit Claude-Abo (empfohlen):**
1. **Verbindungen → Server einrichten:** Host, Benutzer, Passwort/Schlüssel eintragen → „Installieren“.
2. **Anmelden:** Das Terminal startet `claude`. „Claude account with subscription“ wählen, Link über das Link-Symbol öffnen, im Browser anmelden, Code kopieren, im Terminal „Einfügen“ + Enter.
3. **Claude-Tab → „Abo (Claude Code)“:** Arbeitsordner wählen und loslegen.

**Mit API-Key:** In den Einstellungen den Anthropic-API-Key eintragen und im Claude-Tab „API-Key“ wählen.

Hinweis: Anthropic erlaubt Drittanbieter-Apps keine direkte Anmeldung mit dem Claude-Abo. Deshalb läuft der Abo-Modus über das offizielle Claude Code auf deinem Server.

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

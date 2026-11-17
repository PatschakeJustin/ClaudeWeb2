# Eingebauter RDP-Client (FreeRDP)

Dieses Modul enthält den Android-Kern von [FreeRDP](https://github.com/FreeRDP/FreeRDP) 3.17.2
(`client/Android/Studio/freeRDPCore`). Lizenz: Java-Teile Mozilla Public License 2.0, übriger Code
Apache 2.0 (siehe `LICENSE-FreeRDP-Apache-2.0` und die Dateiköpfe).

Änderungen für Humantize DevApp:
- `GlobalApp` ist keine `Application` mehr, sondern wird beim ersten RDP-Start initialisiert
  (`GlobalApp.init`), damit die native Bibliothek nur bei Bedarf geladen wird.
- `SessionActivity` kann über `PARAM_PENDING` eine Verbindung direkt aus dem Speicher öffnen
  (das Passwort wird nicht in FreeRDPs eigener Datenbank abgelegt).
- Broadcast-Receiver über `ContextCompat` registriert (Android 7 kompatibel), Broadcasts nur an die eigene App.
- Gekürztes Manifest (nur Sitzung und deren Dialoge).

Die nativen Bibliotheken baut `scripts/build-freerdp-native.sh` (CI-Job `freerdp-native`, zwischengespeichert).

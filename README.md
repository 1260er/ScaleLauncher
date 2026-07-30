# ScaleLauncher 1.0

Eigenständige, quelloffene Android-App, die eine ausgewählte Bluetooth-LE-Waage erkennt und openScale öffnet.

## Architektur

ScaleLauncher bleibt bewusst von openScale und openScale Sync getrennt. Es greift nicht auf interne Klassen von openScale zu, sondern nutzt ausschließlich den offiziellen Android-App-Startmechanismus.

Die Erkennung wurde für 1.0 neu aufgebaut:

1. Nach Dienststart ist die App **nicht scharf**.
2. Sie wartet, bis von der Waage mehrere Sekunden kein BLE-Signal mehr kommt.
3. Erst dann ist sie **bereit**.
4. Beim nächsten Einschalten müssen mindestens zwei Pakete in kurzer Folge eintreffen.
5. openScale wird genau einmal gestartet.
6. Eine neue Messung ist erst möglich, nachdem die Waage wieder vollständig verschwunden ist.

Damit gibt es weder eine künstliche Startphase noch einen pauschalen Cooldown.

## openScale-Erkenntnisse

- openScale unterstützt die Xiaomi S400 und benötigt dafür den BLE-Bind-Key.
- Die aktuelle App-Struktur stellt keinen dokumentierten, exportierten Hintergrunddienst oder öffentlichen Broadcast bereit, über den eine andere App eine Messung direkt starten könnte.
- ScaleLauncher startet daher nur die normale openScale-App. openScale muss dort für automatisches Verbinden eingerichtet sein.
- Android kann das Öffnen einer App aus dem Hintergrund oder bei gesperrtem Gerät blockieren. ScaleLauncher zeigt dann zusätzlich eine antippbare Benachrichtigung.

## Datenschutz

- keine Internetberechtigung
- keine Tracker
- keine Werbung
- keine Cloud

## Build

Jeder Push auf `main` baut per GitHub Actions eine Debug-APK. Das Artefakt heißt `ScaleLauncher-debug-apk`.

## Lizenz

GPL-3.0

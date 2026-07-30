# ScaleLauncher

ScaleLauncher erkennt eine ausgewählte BLE-Waage und öffnet openScale.

## Version 0.2.0

- Waage per BLE-Scan auswählen
- Ereignisprotokoll
- kein unnötiger openScale-Start direkt beim Aktivieren
- erneute Auslösung erst, nachdem die Waage wieder verschwunden war
- stille Dienstbenachrichtigung mit eigenem Kanal
- Hinweisbenachrichtigung bei gesperrtem Telefon
- kein Internetzugriff, keine Tracker

## Wichtige Android-Grenze

Bei gesperrtem Telefon darf eine normale App openScale nicht zuverlässig im Hintergrund als sichtbare Activity starten. ScaleLauncher zeigt deshalb eine antippbare Benachrichtigung. openScale stellt derzeit keinen exportierten Hintergrunddienst bereit, den ScaleLauncher direkt aufrufen könnte.

## Build

Der GitHub-Actions-Workflow erzeugt `app-debug.apk` und lädt sie als Artefakt hoch.

Lizenz: GPL-3.0

# ScaleLauncher 2.0 – BLE-Diagnose

Eigenständiges Projekt. Diese Version analysiert die tatsächlichen BLE-Werbepakete der ausgewählten Waage, statt auf ein vollständiges Verschwinden des Geräts zu warten.

## Neu

- Protokolliert das erste BLE-Muster und jede Änderung der Rohdaten.
- Zeigt RSSI, Connectable, TX-Power, Manufacturer Data, Service Data, UUIDs und RAW-Paket.
- Startet openScale versuchsweise, wenn nach einer 8-sekündigen Lernphase ein anderes BLE-Muster erscheint.
- 25 Sekunden Auslösesperre gegen Mehrfachstarts.
- Schaltfläche **Kopieren**, die das komplette Protokoll in die Zwischenablage legt.
- Keine Internetberechtigung, keine Tracker, keine Cloud.

## Testablauf

1. Überwachung starten und die Waage 10 Sekunden nicht betreten.
2. Danach einmal normal auf die Waage stellen.
3. Protokoll mit **Kopieren** kopieren und zur Auswertung bereitstellen.

Die automatische Erkennung ist in 2.0 bewusst experimentell. Entscheidend sind zunächst die protokollierten Paketänderungen.

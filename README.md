# ScaleLauncher 2.3

Unabhängige Begleit-App für openScale. ScaleLauncher überwacht die BLE-Werbepakete einer Xiaomi S400 und öffnet openScale beim erkannten Messbeginn.

## Neu in 2.3

- BLE-Scan wird unmittelbar vor dem Start von openScale gestoppt
- 25 Sekunden Pause, damit openScale die Waage allein verwenden kann
- automatischer Neustart des BLE-Scans nach der Pause
- Overlay-Hintergrundstart aus Version 2.2 bleibt unverändert erhalten
- Aktivitätserkennung wird weiterhin nur einmal pro Messung ausgelöst

Die Overlay-Berechtigung **„Über anderen Apps einblenden“** muss einmal manuell erteilt werden.

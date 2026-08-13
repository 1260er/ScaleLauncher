# Änderungsprotokoll

## 1.2.1

- BLE-Watchdog nach längerem Ruhezustand korrigiert; der Scan startet zuverlässig neu
- openScale Provider API 2 ist jetzt erforderlich; Unterstützung für Provider API 1 entfernt
- vollständige Übertragung und Prüfung aller 15 Messwerte über Provider API 2
- Dokumentation an die neue API-2-Anforderung angepasst

## 1.2.0

- BLE-Scan-Watchdog startet einen festgefahrenen Scan wieder automatisch neu
- erweiterte BLE-Diagnose protokolliert geänderte Paketmuster zur Fehlersuche
- Fehlerbehebung dokumentiert, wenn die S400 erreichbar ist, aber keine Messdaten sendet
- englische Screenshots in der englischen Dokumentation korrigiert
- gleichzeitiger Betrieb von ScaleLauncher auf zwei Geräten mit derselben S400 erfolgreich getestet

## 1.1.0

- Unterstützung für openScale Provider API 1 und API 2
- Provider API 1 speichert Gewicht, Körperfett, Körperwasser und Muskelanteil
- Provider API 2 speichert den vollständigen Wertesatz
- automatische Benutzerzuordnung anhand von Gewicht und individueller Toleranz überarbeitet
- mehrdeutige Messungen werden nicht mehr automatisch zugeordnet
- Standardtoleranz für neue Profile auf 2 kg gesetzt
- alle in openScale vorhandenen Benutzer werden bei der Zuordnung berücksichtigt
- Monitoring startet nur bei vollständig eingerichteten Benutzerprofilen
- Health Connect kann vollständig deaktiviert werden
- Benutzer- und Profilvalidierung verbessert
- Waagenstatus überarbeitet: erreichbar nur nach tatsächlich empfangenem BLE-Signal
- kein fehlerhafter Wechsel mehr zwischen erreichbar und nicht erreichbar
- deutsche und englische Texte sowie Dokumentation aktualisiert
- signierte Dev-Builds über GitHub Actions für Obtanium

## 1.0.0

- erste veröffentlichte Version von ScaleLauncher

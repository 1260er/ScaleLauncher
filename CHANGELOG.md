# Änderungsprotokoll

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

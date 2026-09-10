# Änderungsprotokoll

## 1.6.0

- openScale-Integration auf Provider API 3 umgestellt; Provider API 2 wird für 1.6.0 bewusst abgelehnt
- Profile, offene Messungen, openScale-Warteschlange, Peer-Outbox, Empfangs-Deduplizierung und Schreibjournal dauerhaft über Room abgesichert
- bestätigte openScale-Schreibvorgänge können nach Prozessabbrüchen ohne doppelten Eintrag sicher abgeschlossen werden
- openScale-Warteschlangenläufe gegen später eingereihte ältere Messungen abgegrenzt
- bestätigter openScale-Erfolg bleibt auch bei nachgelagerten lokalen Fehlern erhalten
- verspätete Health-Connect-Callbacks können keinen neueren sichtbaren Dienststatus mehr überschreiben
- Peer-Werbung und Präsenz nach langfristigen stillen BLE-Ausfällen selbstheilend gemacht
- fehlende Peer-ACKs werden schneller erneut versucht; echte Transportfehler behalten den gestaffelten Backoff
- verwaiste Peer-Daten nach dem Entfernen eines gekoppelten Geräts werden repariert
- lokale Pending-Messungen werden erst nach dauerhaft eingereihtem CLOSED entfernt; vorhandener CLOSED-Fortschritt bleibt bei Retries erhalten
- unterbrochene Rescue-Übergänge und bereits STORED bestätigte lokale Abschlüsse können bei späterem Peer-Sync sicher fortgesetzt werden
- Remote-Handoffs werden erst nach dauerhaft eingereihter Route und einem gültigen ACK für genau diese noch offene Route abgeschlossen; CLOSED wird vor dem Entfernen des lokalen Pending dauerhaft abgesichert
- „Nicht meine Messung“ lehnt alle Kandidaten des ablehnenden Peer-Geräts gemeinsam ab, bevor eine automatische Restzuordnung erfolgen kann
- zusätzliche Regressionstests für openScale-Recovery, Queue-Grenzen, Peer-Reparatur, CLOSED-Dauerhaftigkeit, ACK-Retry, Health-Connect-Status und Notification-Status ergänzt
- aktive Peer-Dedup-Einträge werden weiterhin nicht zeitbasiert gelöscht, weil alte noch nicht bestätigte Peer-Nachrichten sonst erneut als neu verarbeitet werden könnten

## 1.5.1

- Gradle Wrapper 8.9 ins Upstream-Repository aufgenommen, damit F-Droid direkt mit dem projektseitigen Wrapper bauen kann
- VCS-Buildmetadaten im APK deaktiviert, damit der Build nicht von der Git-Revision abhängt
- Vorbereitung für weiterhin reproduzierbare F-Droid-Builds
- keine funktionalen Änderungen an Messlogik, Bluetooth-Routing oder Benutzerzuordnung gegenüber 1.5.0

## 1.5.0

- Build-Toolchain auf Java 21 umgestellt
- Android-Abhängigkeitsmetadaten im APK und App Bundle deaktiviert, um F-Droid-Prüfungen und reproduzierbare Builds zu unterstützen
- Dev-Release-Workflow auf den Entwicklungszweig ui-v1.5.0 umgestellt
- keine funktionalen Änderungen an Messlogik, Bluetooth-Routing oder Benutzerzuordnung gegenüber 1.4.0

## 1.4.0

- Release-Build für unabhängige F-Droid-Quellbuilds vorbereitet; private GitHub-Signierung bleibt getrennt abgesichert
- Fastlane-Metadaten mit deutschen und englischen Beschreibungen, App-Icon und Screenshots ergänzt
- Datenschutz-, Lizenz- und Asset-Dokumentation für die Veröffentlichung vervollständigt
- Dev-Release-Workflow auf den Entwicklungszweig ui-v1.4.0 umgestellt
- Status nach Geräteneustart korrigiert: bei deaktiviertem Autostart wird der Dienst als gestoppt angezeigt statt eine veraltete Bluetooth-Fehlermeldung zu übernehmen
- keine Änderungen an Messlogik, Bluetooth-Routing oder Benutzerzuordnung gegenüber 1.3.0

## 1.3.0

- direkte Bluetooth-Kommunikation zwischen gekoppelten ScaleLauncher-Geräten für gemeinsame Haushaltsprofile und Messungsrouting ergänzt
- Collector-Erreichbarkeit wird auf gekoppelten Geräten erkannt und im Dienststatus angezeigt
- eindeutige Remote-Messungen werden automatisch an das Besitzergerät des passenden Profils weitergeleitet
- mehrdeutige Messungen unterstützen Claims, Entscheidungen und manuelle Rescue-Zuordnung über gekoppelte Geräte
- Peer-Kommunikation durch ACK-Wiederverwendung, getrennte Empfangsrahmen und Schutz vor veralteten GATT-Callbacks stabilisiert
- Bluetooth-Wiederverbindung nach erneutem Einschalten ohne veralteten GATT-Backoff verbessert
- deaktivierte Benutzerprofile bleiben beim Speichern und Synchronisieren deaktiviert
- leere Haushaltsprofil-Manifeste entfernen veraltete Remote-Profile zuverlässig
- eingehende Profiländerungen werden gegen den authentifizierten Absender geprüft und verwenden monotone Revisionsnummern
- openScale-Schreibvorgänge durch persistentes Journal gegen doppelte Messungen nach Abstürzen oder Neustarts abgesichert
- ausstehende Messungen und Peer-Outbox-Nachrichten werden nicht mehr durch feste Größenlimits still verworfen oder abgebrochen
- gezielte Regressionstests für Pending-Messungen und Peer-Outbox ergänzt
- Bluetooth-Aus-Zustand bleibt stabil sichtbar und erholt sich nach erneutem Einschalten automatisch ohne Dienst-Neustart
- neue Peer-Outbox-Einträge stoßen die Zustellung unmittelbar an, statt auf den regulären Synchronisierungszyklus zu warten
- Peer-Retries verwenden bei nicht erreichbaren Geräten einen gestaffelten Backoff bis 60 Sekunden und reduzieren wiederholte Warnmeldungen
- Abbrechen-Schaltflächen in destruktiven Sicherheitsdialogen verwenden eine neutrale, Theme-kompatible Textfarbe

## 1.2.1

- BLE-Überwachung stabilisiert: laufende Scans werden bei reiner Funkstille nicht mehr vorsorglich neu gestartet; echte Scanfehler bleiben selbstheilend
- BLE-Erkennung für schwache Signale mit ALL_MATCHES und aggressivem Match-Modus verbessert
- Startseite zeigt jetzt direkt, ob Health Connect aktiv oder deaktiviert ist
- Health-Connect-Schalter bleibt beim Aktivieren gesetzt; der Zustand wird erst nach erfolgreicher Prüfung über „Speichern“ übernommen
- Health-Connect-Schalter bleibt beim Aktivieren gesetzt; fehlende Werte oder Schreibrechte werden nur noch als Status gemeldet
- openScale Provider API 2 ist jetzt erforderlich; Unterstützung für Provider API 1 entfernt
- vollständige Übertragung und Prüfung aller 15 Messwerte über Provider API 2
- Dokumentation an BLE-Verhalten, Health-Connect-Status und API-2-Anforderung angepasst

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

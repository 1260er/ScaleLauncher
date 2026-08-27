# ScaleLauncher – Abnahme- und Regressionstestplan

Stand: 27. August 2026  
Technisch abgenommener Teststand: Dev-Build **VersionCode 199**  
Branch: `ui-v1.2.0`  
Letzter funktionaler Fix vor der Abnahme: `0e01dab` (`Auto resolve last remaining candidate`)

## Zweck

Dieser Testplan dokumentiert die praktische Abnahme der aktuellen ScaleLauncher-Funktionen und dient zugleich als reproduzierbarer Regressionstest für spätere Änderungen.

Die technische Logik wurde nach erfolgreicher Abnahme eingefroren. Weitere Arbeiten nach diesem Stand sollen sich auf UI/UX und Dokumentation beschränken, solange kein reproduzierbarer technischer Fehler auftritt.

Die Tests 1–21 und 26 wurden in vorhergehenden Abnahmerunden erfolgreich durchgeführt. Nach den letzten funktionalen Korrekturen wurden die betroffenen bzw. noch offenen Bereiche 17, 22, 23, 24 und 25 mit VersionCode 199 gezielt erneut praktisch geprüft. Auf eine vollständige Wiederholung aller 26 Tests mit Build 199 wurde anschließend bewusst verzichtet, weil die relevanten Regressionen erfolgreich abgesichert waren.

## Testaufbau

Für Mehrbenutzer- und Mehrgeräte-Tests wird folgende Rollenbezeichnung verwendet:

- **Collector:** das Handy, das aktuell die aktive S400-Verbindung hält und die Messung empfängt.
- **Remote:** ein gekoppeltes zweites ScaleLauncher-Handy.
- **Benutzer A:** lokaler Benutzer auf dem Collector, im praktischen Test Andre.
- **Benutzer B:** zweiter lokaler Benutzer auf dem Collector.
- **Benutzer R:** Benutzer auf dem Remote-Handy.

Typische Referenzwerte für reproduzierbare Zuordnungstests:

- passender Benutzer: ca. `70 kg ± 2 kg`
- bewusst nicht passender Benutzer: z. B. `80 kg ± 2 kg`

Vor jedem Test sollen alte Pending-Messungen abgeschlossen sein. Wenn nicht ausdrücklich anders beschrieben, sind Bluetooth und Überwachung auf den beteiligten Geräten aktiv und die Geräte vollständig miteinander gekoppelt.

## Master-Tabelle

| Nr. | Bezeichnung | Ausgangslage / Rollen | Referenzgewichte | Schritte | Erwartetes Ergebnis | Tatsächliches Ergebnis / Abnahme | Status | Logs |
|---:|---|---|---|---|---|---|---|---|
| 1 | Dienststart | Ein eingerichtetes Handy, S400 konfiguriert | beliebig gültig | Überwachung starten. | Dienst startet ohne Fehler; Status wechselt in laufende Überwachung. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Nur bei Abweichung |
| 2 | Erste Waagenerkennung | Dienst läuft, Waage zunächst inaktiv | beliebig gültig | Waage aufwecken / betreten. | S400 wird erkannt und BLE-Empfang beginnt ohne Neustart des Dienstes. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Nur bei Abweichung |
| 3 | Zweite Waagenerkennung ohne Stop/Start | Erste Messung/Erkennung abgeschlossen, Dienst läuft weiter | beliebig gültig | Waage erneut benutzen, ohne Überwachung zu stoppen. | Zweite Erkennung und Messung funktionieren selbstständig. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei Fehler Scan-/GATT-Log |
| 4 | Lange Pause | Dienst bleibt über längere Zeit aktiv | beliebig gültig | Nach längerer Funk-/Nutzungspause erneut wiegen. | Waage wird ohne manuelles Stop/Start wieder erkannt. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei Fehler Scan-/GATT-Log |
| 5 | Eindeutig lokal | Collector mit genau einem passenden lokalen Profil | A: 70±2, B: außerhalb | Ca. 70 kg messen. | Messung wird automatisch Benutzer A zugeordnet und einmal in dessen openScale gespeichert. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Nur bei Abweichung |
| 6 | Referenzgewicht | Lokale Profile vollständig konfiguriert | Referenz/Toleranz so setzen, dass nur ein Profil passt | Messung innerhalb und außerhalb der Toleranz durchführen. | Automatische Kandidatenerkennung folgt `Referenzgewicht ± Toleranz`. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Nur bei Abweichung |
| 7 | Lokal mehrdeutig | Zwei lokale Profile passen gleichzeitig | A: 70±2, B: 70±2 | Ca. 70 kg messen. | Keine automatische falsche Zuordnung; Messung bleibt zur Entscheidung offen. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Nur bei Abweichung |
| 8 | Mehrdeutig → Benutzer A | Lokale Mehrdeutigkeit wie Test 7 | A: 70±2, B: 70±2 | Offene Messung manuell Benutzer A zuordnen. | Genau eine Speicherung bei Benutzer A; Pending verschwindet. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Nur bei Abweichung |
| 9 | Mehrdeutig → Benutzer B | Lokale Mehrdeutigkeit wie Test 7 | A: 70±2, B: 70±2 | Offene Messung manuell Benutzer B zuordnen. | Genau eine Speicherung bei Benutzer B; Pending verschwindet. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Nur bei Abweichung |
| 10 | Mehrdeutig → Nicht meins | Mehrdeutige offene Messung | mindestens zwei Kandidaten | Auf Collector „Nicht meins“ wählen. | Lokale Kandidaten werden verworfen; keine falsche lokale Speicherung. Zustand folgt verbleibenden Kandidaten. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei unerwarteter Zuordnung |
| 11 | Echtes NO_MATCH | Kein Profil liegt innerhalb der Gewichtstoleranz | alle Profile außerhalb | Ca. 70 kg messen. | Messung bleibt als nicht zugeordnet / NO_MATCH offen; keine automatische Speicherung. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Nur bei Abweichung |
| 12 | NO_MATCH manuell lokal | NO_MATCH-Pending aus Test 11 | Zielprofil absichtlich außerhalb | Offene Messung manuell einem gültigen lokalen Benutzer zuordnen. | Manuelle Zuordnung ist möglich und wird korrekt in openScale gespeichert. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Nur bei Abweichung |
| 13 | Pending-Persistenz | Eine offene, noch nicht entschiedene Messung vorhanden | mehrdeutig oder NO_MATCH | App verlassen und später erneut öffnen. | Offene Messung bleibt erhalten und ist weiterhin entscheidbar. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei Verlust des Pending |
| 14 | Dienstneustart mit Pending | Offene Messung vorhanden | mehrdeutig oder NO_MATCH | Überwachung/Dienst neu starten, ohne Pending vorher zu entscheiden. | Pending bleibt erhalten und kann danach korrekt abgeschlossen werden. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei Verlust des Pending |
| 15 | Remote AMBIGUOUS | Collector + Remote gekoppelt; lokaler und Remote-Benutzer passen | A: 70±2, R: 70±2, B: außerhalb | Ca. 70 kg am Collector messen. | Collector zeigt Mehrdeutigkeit; Remote erhält eine Zuordnungsanfrage. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei Peer-Ausfall beide Logs |
| 16 | Remote ACCEPT | Remote-Pending aus Test 15 | A: 70±2, R: 70±2 | Auf Remote Benutzer R annehmen. | Messung wird an Besitzer-Handy geroutet, dort einmal verarbeitet/gespeichert; Collector-Pending verschwindet nach ACK. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei hängendem Abschluss beide Logs |
| 17 | Remote REJECT / letzter Kandidat | Collector A + Remote R sind Kandidaten; B außerhalb | A: 70±2, R: 70±2, B: 80±2 | Variante 1: Remote lehnt ab. Variante 2: Collector verwirft lokalen Kandidaten. | Bleibt bei normaler Mehrdeutigkeit exakt ein gültiger Kandidat übrig, wird dieser automatisch gewählt. Lokal und remote; nicht bei manuellem NO_MATCH-Rescue. | Build 199 in beiden Richtungen praktisch bestätigt. | ✅ Bestanden | Nur bei Abweichung |
| 18 | Collector entscheidet zuerst | Remote-Anfrage ist bereits aktiv | A: 70±2, R: 70±2 | Collector entscheidet lokal, bevor Remote antwortet. | Collector-Entscheidung gewinnt; Remote-Pending wird geschlossen; keine Doppelverarbeitung. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei Doppelverarbeitung beide Logs |
| 19 | NO_MATCH Remote Rescue | Collector findet keinen automatischen Kandidaten; Remote erreichbar | Collector lokal außerhalb | NO_MATCH erzeugen und Remote-Rescue auslösen/abwarten. | Remote kann zur manuellen Rettung der NO_MATCH-Messung einbezogen werden. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei fehlender Anfrage beide Logs |
| 20 | Rescue ACCEPT | Remote-Rescue-Pending aktiv | wie Test 19 | Auf Remote Benutzer annehmen. | Messung wird sicher zum Remote-Besitzer geroutet, einmal gespeichert und abgeschlossen. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei hängendem Abschluss beide Logs |
| 21 | Rescue REJECT | Remote-Rescue-Pending aktiv | wie Test 19 | Remote lehnt Rescue ab. | Ursprüngliches NO_MATCH bleibt offen; keine automatische Zuordnung nur wegen eines letzten Kandidaten. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei falscher Auto-Zuordnung |
| 22 | Peer zeitweise weg / Bluetooth-Recovery | Dein Handy als Collector, Remote gekoppelt | A: 70±2, R: 70±2, B: außerhalb | Remote-Anfrage erzeugen; Entscheidung treffen; Bluetooth zeitweise aus; nach ca. 2 min wieder an; Überwachung nicht stoppen/starten. | Peer-Transport erholt sich nach Bluetooth ON selbstständig; gepufferte Anfrage/Entscheidung wird zugestellt. | Build 199: Anfrage nach BT-Rückkehr automatisch. Zusätzlich Remote „Nicht meins“ vor BT-Aus; nach ca. 2 min BT an → Entscheidung sofort synchronisiert und verarbeitet. | ✅ Bestanden | Bei Fehler beide Logs ab BT-Umschaltung |
| 23 | Retry / Deduplizierung | Collector + Remote; Remote-Zuordnung während Transport unterbrochen | A: 70±2, R: 70±2, B: außerhalb | Remote R annehmen; Collector-Bluetooth sofort ausschalten; später wieder einschalten; nichts manuell neu auslösen. | Retry beendet Route selbstständig; Remote speichert exakt einmal; Collector-Pending verschwindet nach Abschluss. | Build 199: Zwischenstatus „Zuordnung … wird abgeschlossen“, danach Pending weg; auf Remote genau ein openScale-Eintrag. Collector-Rollenwechsel während Recovery unauffällig. | ✅ Bestanden | Bei Duplikat oder hängendem Pending beide Logs |
| 24 | Remote-Benachrichtigung | Beide Apps nicht im Vordergrund; Überwachung aktiv | A: 70±2, R: 70±2, B: außerhalb | Apps verlassen/schließen, ca. 70 kg messen, Sperrbildschirm beobachten. | Benachrichtigung zur unzugeordneten Messung erscheint auch ohne offene App; nach Entsperren ist die offene Messung sichtbar. | Build 199: Hinweis auf beiden Sperrbildschirmen; nach Entsperren Hinweis auf unzugeordnete Messung. | ✅ Bestanden | Nur bei fehlender Notification |
| 25 | Manuelle Auswahl außerhalb Gewichtskandidaten | Collector mit zwei lokalen Benutzern + einem Remote-Benutzer | A: 80±2, B: 70±2, R: 70±2 | Ca. 70 kg messen. A ist kein automatischer Kandidat. Auf Collector manuell A auswählen. | Gewichtsmatching begrenzt nur automatische Kandidaten. Gültiger lokaler Benutzer A bleibt manuell auswählbar und kann korrekt gespeichert werden. | Build 199: A war auswählbar und Messung wurde korrekt lokal gespeichert. | ✅ Bestanden | Nur bei Abweichung |
| 26 | Status nach abgeschlossener Messung | Eine Messung vollständig verarbeitet | beliebig | Nach Speicherung/ACK Startseite und Pending-Anzeige beobachten. | Kein veraltetes Pending; Status kehrt in normalen Bereitschafts-/Überwachungszustand zurück. | In vorheriger Abnahmerunde erfolgreich. | ✅ Bestanden | Bei falschem Status |

## Besonders relevante Regressionen des finalen Teststands

### Letzter verbleibender Kandidat

Bei einer normalen mehrdeutigen Messung wird nach Ablehnungen automatisch weitergelöst, wenn exakt **ein** gültiger Kandidat übrig bleibt. Der Kandidat darf lokal oder remote sein.

Diese Automatik gilt bewusst **nicht** für ein manuelles NO_MATCH-Rescue.

### Bluetooth- und Peer-Recovery

Ein Bluetooth-Ausfall auf einem Peer darf keinen manuellen Neustart der Überwachung erforderlich machen. Nach Bluetooth ON wird der Peer-Transport neu aufgebaut und persistente Nachrichten werden erneut zugestellt.

### Deduplizierung

Persistente Retries dürfen niemals zu doppelten openScale-Messungen führen. Eine bereits verarbeitete geroutete Messung wird anhand ihrer Messungs-ID erkannt und nur bestätigt, nicht erneut gespeichert.

### Collector-Rollenwechsel

Der Collector ist nicht dauerhaft an ein bestimmtes Handy gebunden. Im praktischen Recovery-Test wechselte die Collector-Rolle nach Bluetooth-Aus/An, ohne dass die laufende Zuordnung verloren ging oder doppelt verarbeitet wurde.

### Manuelle Zuordnung

Das Referenzgewicht steuert die **automatische** Kandidatenerkennung. Sobald eine menschliche Entscheidung erforderlich ist, darf der Collector eine offene Messung auch einem anderen gültigen lokalen Benutzer zuordnen, selbst wenn dessen Referenzgewicht nicht zur Messung passt.

## Abnahmeentscheidung

Der oben dokumentierte Funktionsstand wurde am 27. August 2026 als **technisch bestanden** erklärt.

Für den nächsten Projektabschnitt gilt:

- keine vorsorglichen Änderungen an Routing-, BLE-, Pending-, Retry-, Deduplizierungs- oder Zuordnungslogik
- UI-/UX-Änderungen nur so, dass die getestete technische Logik unverändert bleibt
- bei späteren technischen Änderungen die betroffenen Tests dieses Plans gezielt wiederholen
- vor einem größeren zukünftigen Funktionsrelease diesen Plan als Regressionstest verwenden

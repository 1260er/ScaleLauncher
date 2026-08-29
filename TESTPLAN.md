# ScaleLauncher – Abnahme- und Regressionstestplan

Stand: 29. August 2026
Branch: `ui-v1.2.0`
Status: **Finale Gesamtabnahme noch ausstehend**

## Zweck

Dieser Plan ist die verbindliche vollständige Abnahme- und Regressionstestreihe für ScaleLauncher.

Technisch gleiche Abläufe werden nicht mehrfach als eigene Tests geführt. Stattdessen werden unterschiedliche Entscheidungen desselben Grundzustands als Varianten innerhalb eines Testblocks geprüft.

Die komplette Testreihe wird erneut durchgeführt, sobald die App funktional und hinsichtlich UI/UX fertiggestellt ist. Bis dahin werden bei Änderungen nur die direkt betroffenen Testblöcke gezielt wiederholt.

## Testaufbau und Bezeichnungen

Für Mehrbenutzer- und Mehrgeräte-Tests gelten folgende Rollen:

- **Collector:** das Handy, das aktuell die aktive S400-Verbindung hält und die Messung empfängt.
- **Remote:** ein vollständig gekoppeltes zweites ScaleLauncher-Handy.
- **Benutzer A:** lokaler Benutzer auf dem Collector, im praktischen Test typischerweise Andre.
- **Benutzer B:** zweiter lokaler Benutzer auf dem Collector.
- **Benutzer R:** Benutzer auf dem Remote-Handy, im praktischen Test typischerweise Ela.

Typische Referenzwerte für reproduzierbare Zuordnungstests:

- passend: z. B. `70 kg ± 2 kg`
- bewusst nicht passend: z. B. `80 kg ± 2 kg`
- für Rescue außerhalb der Toleranz: Referenz so wählen, dass das reale Messgewicht knapp außerhalb der automatischen Toleranz von Benutzer R liegt.

Wenn nicht ausdrücklich anders beschrieben:

- Bluetooth ist auf allen beteiligten Geräten aktiv.
- Beide Handys sind vollständig gekoppelt.
- Die Überwachung läuft auf beiden Handys.
- Das Collector-Handy ist eindeutig bekannt.
- Alte offene Messungen sind vor Testbeginn abgeschlossen.
- Nach jedem Abschluss wird kontrolliert, dass keine unerwartete zweite openScale-Messung entstanden ist.

## Globale Abschlusskriterien

Diese Punkte gelten für **jeden** Test und werden nicht zusätzlich als eigener Test wiederholt:

1. Eine Messung darf höchstens einmal in openScale gespeichert werden.
2. Nach einem abgeschlossenen ACCEPT-, REJECT- oder Routing-Vorgang darf kein veraltetes Pending zurückbleiben.
3. Remote-Pendings müssen nach Abschluss oder Schließen der Messung verschwinden.
4. Referenzgewichte dürfen nur durch eine tatsächlich erfolgreich zugeordnete und gespeicherte Messung verändert werden.
5. Nach abgeschlossenem Vorgang muss die App wieder in einen normalen Bereitschafts-/Überwachungszustand zurückkehren.
6. Keine Entscheidung darf einen bereits ausdrücklich abgelehnten Benutzer wieder als gültigen Kandidaten aktivieren.

Damit ist der frühere separate Test „Status nach abgeschlossener Messung“ in allen Tests enthalten.

---

## Test 1 – Dienst, Waagenerkennung und Dauerbetrieb

### Ausgangslage

Ein vollständig eingerichtetes Handy, gültige S400-Konfiguration und mindestens ein gültiges Benutzerprofil.

### Ablauf

**1A – Start und erste Erkennung**

1. Überwachung starten.
2. Warten, bis die Überwachung betriebsbereit ist.
3. Waage aufwecken und eine Messung durchführen.

**Soll:** Dienst startet ohne Fehler. Die S400 wird erkannt und die erste Messung ohne zusätzlichen Neustart empfangen.

**1B – Zweite Erkennung ohne Stop/Start**

1. Dienst nach 1A weiterlaufen lassen.
2. Waage erneut benutzen.

**Soll:** Zweite Erkennung und Messung funktionieren selbstständig.

**1C – Erkennung nach längerer Pause**

1. Überwachung weiterlaufen lassen.
2. Nach längerer Nutzungs-/Funkpause erneut wiegen.

**Soll:** Waage wird ohne manuelles Stoppen oder erneutes Starten der Überwachung wieder erkannt.

**1D – „Überwachen“ bei bereits laufendem Dienst**

1. Während der Dienst bereits korrekt läuft erneut „Überwachen“ drücken.

**Soll:** Der laufende Collector bleibt unverändert aktiv. Die Statusanzeige darf nicht dauerhaft auf `STARTET` wechseln und das Waagensymbol darf nicht fälschlich einen Verbindungsverlust anzeigen.

> Dieser Fall wurde am 29. August 2026 als reproduzierbare UI-/Statusabweichung gefunden und muss vor der finalen Abnahme behoben sein.

---

## Test 2 – Gewichtsmatching und eindeutige lokale Zuordnung

### Ausgangslage

Collector mit mindestens zwei lokalen Benutzern.

Beispiel:

- A passend zur Messung, z. B. `70 ± 2 kg`
- B eindeutig außerhalb, z. B. `80 ± 2 kg`

### Ablauf

1. Messung innerhalb der Toleranz von A durchführen.
2. Prüfen, welcher Benutzer automatisch gewählt wurde.
3. Mit geänderten Referenzen/Toleranzen zusätzlich sicherstellen, dass ein außerhalb der Toleranz liegendes Profil nicht automatisch Kandidat wird.

### Soll

- Automatische Kandidatenerkennung folgt `Referenzgewicht ± Toleranz`.
- Genau ein passender lokaler Benutzer wird automatisch zugeordnet.
- Speicherung erfolgt genau einmal beim richtigen openScale-Benutzer.
- Ein außerhalb der Toleranz liegendes Profil wird nicht automatisch gewählt.

---

## Test 3 – Lokale Mehrdeutigkeit und bewusste manuelle Auswahl

### Ausgangslage

Collector mit zwei gültigen lokalen Profilen A und B.

### Variante 3A – Zwei lokale automatische Kandidaten

Beispiel:

- A: `70 ± 2 kg`
- B: `70 ± 2 kg`

### Ablauf

1. Passende Messung durchführen.
2. Prüfen, dass beide Benutzer zur Entscheidung angeboten werden.
3. Einen der beiden Benutzer bewusst auswählen.

### Soll

- Keine automatische falsche Zuordnung.
- Messung bleibt bis zur menschlichen Entscheidung offen.
- Beide gültigen lokalen Kandidaten sind auswählbar.
- Der gewählte Benutzer wird genau einmal gespeichert.
- Es ist kein eigener symmetrischer Test nur für „A statt B“ nötig, da beide denselben technischen Auswahlpfad verwenden.

### Variante 3B – Manuelle lokale Auswahl außerhalb der Gewichtstoleranz

Beispiel:

- A: `80 ± 2 kg` und damit kein automatischer Kandidat
- B: `70 ± 2 kg`
- optional R: `70 ± 2 kg`

### Ablauf

1. Ca. 70 kg messen, sodass A nicht automatisch passt.
2. In der offenen menschlichen Entscheidung A bewusst manuell auswählen.

### Soll

Gewichtsmatching begrenzt nur die **automatische** Kandidatenerkennung. Ein gültiger lokaler Benutzer darf bei einer offenen menschlichen Entscheidung trotzdem bewusst ausgewählt und korrekt gespeichert werden.

---

## Test 4 – Lokale Ablehnung und Remote-Rescue außerhalb der Toleranz

Dieser Test sichert die am 29. August 2026 gefundene und korrigierte Routing-Lücke ab.

### Ausgangslage

- Collector mit zwei lokalen Benutzern A und B.
- A und B liegen für die reale Messung innerhalb ihrer Toleranz.
- Benutzer R gehört zum gekoppelten Remote-Handy, liegt für dieselbe Messung aber **außerhalb seiner automatischen Gewichtstoleranz**.
- Keine alte offene Messung.

### Variante 4A – Remote übernimmt manuell

1. Messung durchführen.
2. Collector zeigt nur die normalen lokalen Kandidaten.
3. Auf dem Collector „Nicht meine Messung“ wählen.
4. Prüfen, dass alle lokalen Benutzer für diese Messung ausgeschlossen sind und nicht wieder in der Auswahl auftauchen.
5. Remote muss nun eine **manuelle Rescue-Anfrage** erhalten, obwohl R außerhalb der automatischen Toleranz liegt.
6. Auf Remote R möglichst unmittelbar nach Erscheinen der Anfrage annehmen.

### Soll

- Kein lokaler Benutzer wird gespeichert.
- Abgelehnte lokale Benutzer erscheinen nicht wieder.
- Remote wird nicht automatisch außerhalb der Toleranz zugeordnet, sondern nur zur manuellen Entscheidung angeboten.
- Die Remote-Entscheidung muss auch dann gültig verarbeitet werden, wenn sie zeitlich vor der zugehörigen CLAIM-Antwort beim Collector eintrifft.
- Messung wird genau einmal bei R gespeichert.
- Collector-Pending und Remote-Pending verschwinden nach Abschluss.

### Variante 4B – Danach lehnt auch Remote ab

1. Gleichen Ausgangszustand mit neuer Messung herstellen.
2. Collector „Nicht meine Messung“.
3. Remote anschließend ebenfalls „Nicht meine Messung“.

### Soll

Sobald alle Kandidaten ausdrücklich abgelehnt haben, existiert kein gültiger Kandidat mehr und die offene Messung muss auch auf dem Collector vollständig verschwinden. Keine Speicherung und keine automatische Zuordnung.

---

## Test 5 – Echtes NO_MATCH und manuelle Rettung

### Ausgangslage

Die reale Messung liegt außerhalb der Gewichtstoleranz **aller lokalen und aller Remote-Benutzer**.

### Grundzustand

1. Messung durchführen.

### Soll

- Collector erkennt `kein Benutzer innerhalb der Gewichtstoleranz`.
- Keine automatische lokale oder Remote-Zuordnung.
- Messung bleibt zunächst als offene menschlich entscheidbare Messung vorhanden.
- Ein gekoppeltes Remote-Handy darf eine manuelle Rescue-Möglichkeit anbieten.

### Variante 5A – Manuell lokal übernehmen

1. Aus dem NO_MATCH-Zustand einen gültigen lokalen Benutzer bewusst auswählen, obwohl er außerhalb der Toleranz liegt.

**Soll:** Manuelle lokale Zuordnung ist möglich, wird einmal gespeichert und schließt die Messung vollständig ab.

### Variante 5B – Remote Rescue ACCEPT

1. Neuen echten NO_MATCH-Zustand erzeugen.
2. Auf Remote Benutzer R manuell annehmen.

**Soll:** Messung wird sicher zum Besitzer-Handy geroutet, dort genau einmal gespeichert und auf beiden Geräten abgeschlossen.

### Variante 5C – Alle lehnen NO_MATCH ab

1. Neuen echten NO_MATCH-Zustand erzeugen.
2. Auf Collector „Nicht meine Messung“.
3. Auf Remote ebenfalls „Nicht meine Messung“.

**Soll:** Wenn alle möglichen Kandidaten ausdrücklich abgelehnt haben, verschwindet die offene Messung auf dem Collector und auf Remote. Keine Speicherung.

---

## Test 6 – Normale Mehrdeutigkeit zwischen Collector und Remote

### Ausgangslage

- A auf Collector und R auf Remote liegen beide innerhalb der normalen Gewichtstoleranz.
- B liegt außerhalb.
- Beide Dienste laufen und Peer-Synchronisierung funktioniert.

### Variante 6A – Mehrdeutigkeit entsteht

1. Passende Messung durchführen.
2. Zunächst nichts bestätigen oder ablehnen.

**Soll:** Collector zeigt eine offene mehrdeutige Messung und Remote erhält eine Zuordnungsanfrage. Noch keine Speicherung.

### Variante 6B – Remote ACCEPT

1. Zustand aus 6A erneut herstellen.
2. Auf Remote R annehmen.

**Soll:** Messung wird genau einmal zu R geroutet und gespeichert. Collector schließt nach Bestätigung/ACK. Keine offene Restmessung.

### Variante 6C – Remote REJECT, lokaler Kandidat bleibt

1. Zustand aus 6A erneut herstellen.
2. Remote „Nicht meine Messung“.

**Soll:** Bleibt genau ein normaler lokaler Kandidat übrig, wird dieser automatisch gewählt und einmal lokal gespeichert.

### Variante 6D – Collector verwirft lokal, Remote bleibt

1. Zustand aus 6A erneut herstellen.
2. Auf Collector „Nicht meine Messung“.

**Soll:** Bleibt genau ein **normaler Remote-Kandidat** übrig, wird dieser automatisch weitergeroutet. Es darf keine manuelle Rescue-Schleife entstehen.

### Variante 6E – Collector entscheidet zuerst

1. Zustand aus 6A erneut herstellen.
2. Collector entscheidet lokal, bevor Remote antwortet.

**Soll:** Collector-Entscheidung gewinnt. Remote-Pending wird geschlossen. Keine Doppelverarbeitung und keine spätere zweite Zuordnung durch eine verspätete Remote-Antwort.

---

## Test 7 – Pending-Persistenz einschließlich Dienst-Stop/Start

Ein separater Test nur für „Dienst neu starten“ ist nicht erforderlich, weil Stop/Start hier gezielt mit einer offenen Messung geprüft wird.

### Ausgangslage

Eine noch nicht entschiedene mehrdeutige oder NO_MATCH-Messung ist offen.

### Ablauf

1. Messungs-ID bzw. Gewicht und offenen Zustand merken.
2. App verlassen und später erneut öffnen.
3. Prüfen, dass dieselbe offene Messung weiterhin vorhanden ist.
4. Ohne die Messung zu entscheiden den ScaleLauncher-Dienst stoppen.
5. Dienst wieder starten.
6. App bzw. Pending-Anzeige erneut prüfen.
7. Messung anschließend normal abschließen.

### Soll

- Pending überlebt App-Verlassen und erneutes Öffnen.
- Pending überlebt Dienst-Stop/Start.
- Es entsteht keine zweite Kopie.
- Keine automatische falsche Zuordnung allein durch Neustart.
- Messung kann danach normal abgeschlossen werden.

---

## Test 8 – Peer-Recovery, Retry, Deduplizierung und Collector-Wechsel

### Variante 8A – Unterbrochene Remote-Zuordnung

#### Ausgangslage

Normale lokale/Remote-Mehrdeutigkeit wie Test 6.

#### Ablauf

1. Remote R annehmen.
2. Während der Übertragung Bluetooth auf einem beteiligten Gerät ausschalten bzw. die Peer-Verbindung gezielt unterbrechen.
3. Mindestens so lange warten, dass ein Retry erforderlich wird.
4. Bluetooth wieder einschalten.
5. Überwachung nicht manuell neu starten.

#### Soll

- Peer-Transport erholt sich selbstständig.
- Persistente Entscheidung/Route wird erneut zugestellt.
- Messung wird exakt einmal gespeichert.
- Collector-Pending verschwindet nach erfolgreichem Abschluss.
- Keine manuelle Wiederholung der Entscheidung erforderlich.

### Variante 8B – Collector-Failover

1. Beide Handys überwachen und aktuellen Collector feststellen.
2. Dienst auf dem aktuellen Collector stoppen, während das andere Handy weiter überwacht.
3. Prüfen, dass das andere Handy die Collector-Rolle selbstständig übernimmt.
4. Optional den Vorgang in Gegenrichtung wiederholen.

### Soll

Collector-Rolle ist nicht dauerhaft an ein bestimmtes Handy gebunden. Nach Wegfall des aktuellen Collectors übernimmt das andere betriebsbereite Handy selbstständig, ohne dass Messungs-, Pending- oder Peer-Daten beschädigt werden.

---

## Test 9 – Benachrichtigungen im Hintergrund und Bereinigung beim Öffnen

### Ausgangslage

- Beide Apps nicht im Vordergrund bzw. Geräte gesperrt.
- Überwachung aktiv.
- Messung so konfigurieren, dass eine menschliche Zuordnungsentscheidung erforderlich ist.

### Variante 9A – Hintergrundbenachrichtigung

1. Messung durchführen, während die Apps nicht geöffnet sind.
2. Sperrbildschirm/Benachrichtigungen prüfen.

### Soll

Die notwendige Benachrichtigung zur offenen Messung erscheint auch ohne geöffnete App. Nach Entsperren ist die offene Messung in ScaleLauncher sichtbar.

### Variante 9B – Transiente Benachrichtigungen beim Öffnen entfernen

1. Eine Zuordnungs- oder Ergebnisbenachrichtigung erzeugen.
2. ScaleLauncher aktiv öffnen bzw. in den Vordergrund holen.

### Soll

Transiente Zuordnungs-/Ergebnisbenachrichtigungen werden beim aktiven Öffnen entfernt. Die dauerhafte Foreground-/Überwachungsbenachrichtigung bleibt bestehen.

---

## Zusätzliche Regressionen aus den Tests vom 29. August 2026

Folgende besonders wichtige Randfälle sind ausdrücklich abgesichert:

- Nach „Nicht meine Messung“ auf dem Collector sind **alle lokalen Benutzer** für diese Messung ausgeschlossen und dürfen nicht erneut in der Auswahl erscheinen.
- Sind danach keine normalen Kandidaten mehr vorhanden, darf ein gekoppelter Remote-Benutzer **außerhalb seiner automatischen Gewichtstoleranz** eine manuelle Rescue-Anfrage erhalten.
- Ein solcher Remote-Benutzer darf außerhalb der Toleranz niemals automatisch zugeordnet werden.
- Bei `manualRescue` muss eine gültige ACCEPT-Entscheidung auch dann verarbeitet werden, wenn sie vor der CLAIM-Antwort beim Collector ankommt.
- Wenn **alle** Kandidaten ausdrücklich ablehnen, muss die Messung vollständig verschwinden; sie darf nicht ohne mögliche Entscheidung auf dem Collector gestrandet bleiben.
- Bei normaler Mehrdeutigkeit bleibt die bestehende Regel erhalten: Wenn nach Ablehnungen exakt ein normaler Kandidat übrig bleibt, wird dieser automatisch aufgelöst.
- Erneutes Drücken auf „Überwachen“ bei bereits laufendem Dienst darf die UI nicht fälschlich auf `STARTET`/nicht verbundene Waage setzen.
- Transiente Zuordnungs- und Ergebnisbenachrichtigungen werden beim Öffnen der App entfernt, die Foreground-Überwachung bleibt sichtbar.

## Durchführung der finalen Gesamtabnahme

Wenn die App fertiggestellt ist:

1. Den dann aktuellen Build auf beiden Testgeräten installieren.
2. Alle neun Testblöcke einschließlich aller aufgeführten Varianten vollständig durchführen.
3. Vor jedem Test die jeweilige Ausgangslage exakt herstellen.
4. Bei jeder Abweichung beide Geräteprotokolle sichern, bevor Dienste neu gestartet oder Zustände manuell bereinigt werden.
5. Erst nach vollständigem Bestehen aller Blöcke den Build als technisch abgenommen markieren.

## Entwicklungsregel bis zur finalen Abnahme

- Keine vorsorglichen Änderungen an Routing-, BLE-, Pending-, Retry-, Deduplizierungs- oder Zuordnungslogik.
- Technische Änderungen nur bei einem klar reproduzierbaren Fehler und anschließend gezielte Wiederholung des betroffenen Testblocks.
- UI-/UX-Arbeiten dürfen die hier dokumentierten Routing- und Persistenzregeln nicht verändern.
- Nach Abschluss der Entwicklung wird **der gesamte Plan** erneut von Anfang bis Ende durchgeführt.

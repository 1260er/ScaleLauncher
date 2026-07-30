# ScaleLauncher 2.4

Separate Companion-App für die Xiaomi Body Composition Scale S400 und openScale.

## Neu in 2.4

- S400-Werbepakete werden lokal mit dem BLE Bind-Key entschlüsselt.
- Messungen werden direkt über den offiziellen openScale Content Provider eingetragen.
- openScale muss während des Wiegens nicht geöffnet sein.
- Unterstützt Gewicht sowie – bei vorhandener Impedanz und hinterlegten Körperdaten – Körperfett, Wasser und Muskelanteil.
- Schutz gegen doppelte Messungen.
- Kein Internetzugriff und keine Xiaomi-Cloud.

## Einrichtung

1. Waage auswählen oder MAC-Adresse eintragen.
2. 32-stelligen S400 Bind-Key eintragen.
3. „openScale-Zugriff erlauben und Benutzer laden“ antippen.
4. openScale-Benutzer auswählen.
5. Alter, Größe und Geschlecht eintragen.
6. Überwachung starten.

Die Berechnung der Körperzusammensetzung und die S400-Entschlüsselung basieren auf den entsprechenden GPLv3-Komponenten von openScale.

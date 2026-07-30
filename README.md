# ScaleLauncher 2.6

Separate Companion-App für die Xiaomi Body Composition Scale S400 und openScale.

## Neu in 2.6

- Geburtstag statt fest eingetragenem Alter.
- Das Alter wird bei jeder Messung aus Geburtstag und Messdatum neu berechnet.
- Automatische Erkennung der openScale-Provider-API über `content://…/meta`.
- Provider-API 1: verifizierte Übergabe von Gewicht, Fett, Wasser und Muskel.
- Provider-API 2: zusätzliche S400-Werte werden über `values_json` übertragen und anschließend zurückgelesen.
- Nach jeder Übergabe prüft ScaleLauncher anhand des Zeitstempels, ob die Messung in openScale sichtbar ist.
- Das Protokoll unterscheidet klar zwischen lokal berechneten und tatsächlich von openScale bestätigten Werten.
- Doppelte Paket-A-/Paket-B-Zeilen wurden entfernt; pro Wägung erscheint nur noch ein zusammengefasster BLE-Eintrag.
- Kein Internetzugriff und keine Xiaomi-Cloud.

## Warum kann openScale per Bluetooth mehr Werte speichern als eine externe App?

Der Bluetooth-Handler läuft innerhalb von openScale. Er erzeugt intern eine vollständige `ScaleMeasurement` und übergibt sie an die interne Messpipeline. Damit stehen Knochenmasse, Protein, Puls, Impedanzen und weitere Werte direkt zur Verfügung.

ScaleLauncher ist dagegen eine getrennte App und darf nur den exportierten Content Provider verwenden. In der stabilen openScale-Version 3.1.1 ist das Provider-API 1 und unterstützt beim externen Einfügen nur Gewicht, Fett, Wasser und Muskel. Der aktuelle Entwicklungsstand hat Provider-API 2 und unterstützt zusätzlich `values_json`.

## Einrichtung

1. Waage auswählen oder MAC-Adresse eintragen.
2. 32-stelligen S400 Bind-Key eintragen.
3. „openScale-Zugriff erlauben und Benutzer laden“ antippen.
4. openScale-Benutzer auswählen.
5. Geburtstag, Größe und Geschlecht eintragen.
6. Überwachung starten.

Für die vollständige Körperanalyse gelten Alter 18–120 Jahre und Größe 100–230 cm. Nach einem Update von 2.5 muss der Geburtstag einmalig ausgewählt werden.

Die S400-Entschlüsselung, Paketaggregation und Körperanalyse basieren auf den entsprechenden GPLv3-Komponenten von openScale. ScaleLauncher bleibt eine eigenständige Companion-App.

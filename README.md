# ScaleLauncher 2.5

Separate Companion-App für die Xiaomi Body Composition Scale S400 und openScale.

## Neu in 2.5

- Die beiden S400-Pakettypen werden getrennt erkannt und zu einer Messung zusammengeführt:
  - Paket A: Gewicht, Puls und hohe Impedanz
  - Paket B: niedrige Impedanz
- Vollständige lokale Körperanalyse nach der aktuellen openScale-S400-Pipeline.
- Übergabe zusätzlicher Werte über `values_json` an den offiziellen openScale Content Provider.
- Neue Werte: Knochenmasse, fettfreie Masse (LBM), viszerales Fett, Grundumsatz (BMR), Protein, extrazelluläres Wasser (ECW), intrazelluläres Wasser (ICW), Körperzellmasse (BCM), Puls sowie beide Impedanzen.
- Gewicht, Fett, Wasser und Muskel werden weiterhin zusätzlich über die kompatiblen Provider-Felder übertragen.
- Ein 10-Sekunden-Fallback verarbeitet Messungen auch dann, wenn Paket B ausnahmsweise fehlt.
- Kein Internetzugriff und keine Xiaomi-Cloud.

## Einrichtung

1. Waage auswählen oder MAC-Adresse eintragen.
2. 32-stelligen S400 Bind-Key eintragen.
3. „openScale-Zugriff erlauben und Benutzer laden“ antippen.
4. openScale-Benutzer auswählen.
5. Alter, Größe und Geschlecht eintragen.
6. Überwachung starten.

Für die vollständige Körperanalyse gelten entsprechend der verwendeten Berechnung Alter 18–120 Jahre und Größe 100–230 cm. Bereits vorhandene Messungen werden nicht nachträglich ergänzt.

Die S400-Entschlüsselung, Paketaggregation und Körperanalyse basieren auf den entsprechenden GPLv3-Komponenten von openScale. ScaleLauncher bleibt eine eigenständige Companion-App.

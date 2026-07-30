# ScaleLauncher 2.7

Separate Companion-App für die Xiaomi Body Composition Scale S400, openScale und Android Health Connect.

## Neu in 2.7

- Eine fertige S400-Messung wird weiterhin vollständig an openScale übertragen.
- Optional werden unterstützte Werte gleichzeitig direkt nach Health Connect geschrieben.
- Health Connect erhält:
  - Gewicht
  - Körperfett
  - Körperwassermasse
  - Knochenmasse
  - fettfreie Masse
  - Grundumsatz
  - Puls
- Protein, viszerales Fett, Muskelanteil, ECW, ICW, BCM und Impedanzen bleiben in openScale, da Health Connect dafür keine passenden eigenen Datentypen bereitstellt.
- Für jede Messung werden stabile Client-IDs verwendet. Eine erneute Übertragung derselben Messung erzeugt dadurch keine zusätzlichen Datensätze.
- Die Waage wird als Xiaomi Body Composition Scale S400 mit dem Gerätetyp „Waage“ gekennzeichnet.
- ScaleLauncher fordert ausschließlich Schreibrechte an und liest keine Gesundheitsdaten aus Health Connect.
- Kein Internetzugriff und keine Xiaomi-Cloud.

## Voraussetzungen

- Android 14 oder neuer für die direkte Health-Connect-Übertragung.
- openScale mit Provider-API 2 für den vollständigen openScale-Datensatz. Mit Provider-API 1 werden dort nur die unterstützten Grundwerte gespeichert.

## Einrichtung

1. Waage auswählen oder MAC-Adresse eintragen.
2. 32-stelligen S400 Bind-Key eintragen.
3. „openScale-Zugriff erlauben und Benutzer laden“ antippen.
4. openScale-Benutzer auswählen.
5. „Health Connect verbinden“ antippen und die sieben Schreibrechte erlauben.
6. „Messungen parallel nach Health Connect schreiben“ aktivieren.
7. Geburtstag, Größe und Geschlecht eintragen.
8. „Speichern und Überwachung starten“ antippen.

Beim erfolgreichen Wiegen sollte das Protokoll getrennt melden:

```text
openScale vollständig gespeichert und geprüft …
Health Connect: 7 Werte gespeichert
```

Je nach Messung können weniger als sieben Health-Connect-Werte geschrieben werden, etwa wenn kein gültiger Puls oder einzelner Körperwert vorliegt.

## Datenschutz

Alle BLE-Pakete werden lokal entschlüsselt und ausgewertet. ScaleLauncher besitzt keine Internetberechtigung, liest keine Daten aus Health Connect und übermittelt nichts an Xiaomi oder andere Server. Health-Connect-Rechte können jederzeit in den Android-Einstellungen entzogen werden.

## Altersberechnung

Das Alter wird bei jeder Messung aus Geburtstag und Messzeitpunkt berechnet. Für die vollständige Körperanalyse gelten Alter 18–120 Jahre und Größe 100–230 cm.

## Mehrere Benutzer

Version 2.7 schreibt noch in den ausgewählten openScale-Benutzer und in das Health-Connect-Profil des Telefons. Eine automatische Zuordnung anhand von Gewichtsbereichen und Benutzerprofilen ist für den nächsten Entwicklungsschritt vorgesehen.

Die S400-Entschlüsselung, Paketaggregation und Körperanalyse basieren auf den entsprechenden GPLv3-Komponenten von openScale. ScaleLauncher bleibt eine eigenständige Companion-App.

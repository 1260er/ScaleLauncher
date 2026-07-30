# ScaleLauncher 2.8

Separate Companion-App für die Xiaomi Body Composition Scale S400, openScale und Android Health Connect.

## Neu in 2.8

- Für Health Connect kann jeder Datentyp einzeln ein- oder ausgeschaltet werden:
  - Gewicht
  - Körperfett
  - Körperwassermasse
  - Knochenmasse
  - fettfreie Masse
  - Grundumsatz
  - Puls
- Die Schaltfläche für Health Connect fordert nur die Rechte für die aktuell ausgewählten Werte an.
- Nicht ausgewählte Werte werden nicht geschrieben – auch wenn Android dafür aus einer früheren Version noch eine Berechtigung gespeichert hat.
- Diagnoseprotokoll zuschaltbar:
  - Normalmodus: nur wichtige Statusmeldungen, erfolgreiche Übergaben, Warnungen und Fehler
  - Diagnosemodus: zusätzlich technische BLE-, Impedanz- und Berechnungsdetails
- Das Protokoll ist als Ringspeicher begrenzt:
  - maximal 150 Einträge
  - maximal ungefähr 48 KB
  - älteste Einträge werden automatisch gelöscht
- Datum, Uhrzeit und Meldungsart werden im Protokoll angezeigt.
- Die Waage wird beim ersten empfangenen BLE-Paket als erkannt protokolliert.

## Health Connect

ScaleLauncher schreibt ausschließlich die ausgewählten Werte. Es liest keine Daten aus Health Connect. Die Auswahl wird lokal gespeichert und kann jederzeit geändert werden.

Beim erfolgreichen Wiegen erscheinen im Normalprotokoll beispielsweise:

```text
[INFO] Waage erkannt – BLE-Empfang aktiv
[INFO] Messung erkannt: 71,0 kg | Puls 81
[INFO] openScale: vollständige Messung gespeichert (… Werte)
[INFO] Health Connect: 4 Werte gespeichert
```

Im Diagnosemodus erscheinen zusätzlich die entschlüsselten Impedanzen, berechneten Körperwerte und die konkret nach Health Connect geschriebenen Datentypen.

## Einrichtung

1. Waage und Bind-Key wie bisher konfigurieren.
2. openScale verbinden und Benutzer auswählen.
3. Unter „Health Connect“ die gewünschten Werte markieren.
4. „Schreibrechte für ausgewählte Werte erlauben“ antippen.
5. Health-Connect-Synchronisierung aktivieren.
6. Speichern und Überwachung starten.

## Datenschutz

Alle BLE-Pakete werden lokal entschlüsselt und ausgewertet. ScaleLauncher besitzt keine Internetberechtigung, liest keine Daten aus Health Connect und übermittelt nichts an Xiaomi oder andere Server.

## Mehrere Benutzer

Version 2.8 verwendet weiterhin den ausgewählten openScale-Benutzer und das Health-Connect-Profil des Telefons. Die automatische Benutzerzuordnung folgt als eigener Entwicklungsschritt.

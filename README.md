# ScaleLauncher 2.9

Separate Companion-App für die Xiaomi Body Composition Scale S400, openScale und Android Health Connect.

## Neu in 2.9

- Die Pulsauswertung wurde vollständig aus der App-Oberfläche und allen Datenausgaben entfernt:
  - keine Health-Connect-Berechtigung für Puls
  - kein Puls-Datensatz in Health Connect
  - kein Pulswert in openScale
  - kein Pulswert im Normal- oder Diagnoseprotokoll
- Stattdessen gibt es die Auswahl **„BMI ermöglichen“**.
- Health Connect besitzt keinen eigenen BMI-Datentyp. ScaleLauncher schreibt bei aktivierter BMI-Option deshalb:
  - das gemessene Gewicht
  - die konfigurierte Körpergröße
- Kompatible Health-Connect-Apps können daraus den BMI berechnen.
- In openScale wird der berechnete BMI weiterhin direkt über die Provider-API 2 gespeichert.

## Health Connect

Folgende Werte können einzeln ein- oder ausgeschaltet werden:

- Gewicht
- BMI-Grundlage (Gewicht und Körpergröße)
- Körperfett
- Körperwassermasse
- Knochenmasse
- fettfreie Masse
- Grundumsatz

Die BMI-Option kann auch aktiviert werden, wenn „Gewicht“ nicht separat ausgewählt ist. ScaleLauncher überträgt das Gewicht dann einmal als notwendige BMI-Grundlage und vermeidet doppelte Gewichtseinträge.

## Protokoll

- Normalmodus: nur wichtige Statusmeldungen, erfolgreiche Übergaben, Warnungen und Fehler
- Diagnosemodus: zusätzlich technische BLE-, Impedanz- und Berechnungsdetails einschließlich des berechneten BMI
- Ringspeicher: maximal 150 Einträge beziehungsweise ungefähr 48 KB; ältere Einträge werden automatisch gelöscht

## Einrichtung

1. Waage und Bind-Key konfigurieren.
2. openScale verbinden und Benutzer auswählen.
3. Unter „Health Connect“ die gewünschten Werte markieren.
4. Für eine BMI-Auswertung „BMI ermöglichen“ aktivieren.
5. „Schreibrechte für ausgewählte Werte erlauben“ antippen.
6. Health-Connect-Synchronisierung aktivieren.
7. Speichern und Überwachung starten.

## Datenschutz

Alle BLE-Pakete werden lokal entschlüsselt und ausgewertet. ScaleLauncher besitzt keine Internetberechtigung, liest keine Daten aus Health Connect und übermittelt nichts an Xiaomi oder andere Server.

## Mehrere Benutzer

Version 2.9 verwendet weiterhin den ausgewählten openScale-Benutzer und das Health-Connect-Profil des Telefons. Die automatische Benutzerzuordnung folgt als eigener Entwicklungsschritt.

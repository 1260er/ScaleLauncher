# ScaleLauncher

Kleine, trackerfreie Android-App für die Xiaomi Body Composition Scale S400.
Sie überwacht BLE-Werbepakete der eingetragenen MAC-Adresse und versucht bei Erkennung, openScale zu öffnen.

## Datenschutz
- Keine Internetberechtigung
- Keine Werbung oder Tracker
- Speicherung nur lokal auf dem Gerät

## Unterstützte openScale-Pakete
- `com.health.openscale.oss`
- `com.health.openscale.beta`
- `com.health.openscale`

## Wichtige Android-Einschränkung
Android kann das automatische Öffnen fremder Apps aus dem Hintergrund blockieren. In diesem Fall zeigt ScaleLauncher eine Benachrichtigung an. Das Verhalten hängt von Android-Version und Hersteller ab.

## Bauen
Android Studio öffnen und `assembleDebug` oder `assembleRelease` ausführen. Benötigt JDK 17+ und Android SDK 35.

## Lizenz
GPL-3.0-only

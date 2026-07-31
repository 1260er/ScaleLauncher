# Änderungsprotokoll

## 3.3.0

- feste, dauerhaft wiederverwendbare Release-Signatur vorbereitet
- signierter Release-Build über GitHub Actions
- automatische Prüfung der APK-Signatur
- SHA-256-Prüfsumme für jede Release-APK
- automatische GitHub Releases bei Tags wie `v3.3.0`
- Debug- und Release-App klar getrennt (`.dev` nur für Debug)
- Build-Prüfung und Veröffentlichung in getrennte Workflows aufgeteilt
- keine funktionalen Änderungen an BLE, openScale oder Health Connect

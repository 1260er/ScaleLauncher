# Einmalige Einrichtung der festen Release-Signatur

ScaleLauncher verwendet ab Version 3.3.0 eine dauerhaft gleichbleibende Release-Signatur.
Der private Signierschlüssel wird nicht in Git gespeichert. GitHub Actions erhält ihn nur
über verschlüsselte Repository-Secrets.

## 1. Signierschlüssel lokal erzeugen

Auf dem eigenen Linux-Rechner:

```bash
mkdir -p "$HOME/ScaleLauncher-signing"
chmod 700 "$HOME/ScaleLauncher-signing"

keytool -genkeypair -v \
  -keystore "$HOME/ScaleLauncher-signing/scalelauncher-release.p12" \
  -storetype PKCS12 \
  -alias scalelauncher \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=ScaleLauncher, OU=Release, O=Pritcloud, C=DE"
```

Das Passwort sicher in einem Passwortmanager speichern. Der Schlüssel muss dauerhaft
aufbewahrt werden; ohne ihn sind keine Updates derselben App mehr möglich.

## 2. Zwei getrennte Sicherungskopien anlegen

```bash
sha256sum "$HOME/ScaleLauncher-signing/scalelauncher-release.p12"
keytool -list -v \
  -keystore "$HOME/ScaleLauncher-signing/scalelauncher-release.p12" \
  -alias scalelauncher
```

Mindestens zwei verschlüsselte Sicherungskopien an getrennten Orten aufbewahren.
Nicht ins Git-Repository committen.

## 3. GitHub-Secrets einrichten

Im Repository `1260er/ScaleLauncher` unter:

`Settings → Secrets and variables → Actions → New repository secret`

werden diese vier Secrets angelegt:

- `SCALELAUNCHER_KEYSTORE_BASE64`
- `SCALELAUNCHER_KEYSTORE_PASSWORD`
- `SCALELAUNCHER_KEY_ALIAS`
- `SCALELAUNCHER_KEY_PASSWORD`

Werte:

- `SCALELAUNCHER_KEYSTORE_BASE64`: Ausgabe dieses Befehls:

  ```bash
  base64 -w 0 "$HOME/ScaleLauncher-signing/scalelauncher-release.p12"
  ```

- `SCALELAUNCHER_KEYSTORE_PASSWORD`: Passwort des Keystores
- `SCALELAUNCHER_KEY_ALIAS`: `scalelauncher`
- `SCALELAUNCHER_KEY_PASSWORD`: Passwort des Schlüssels

Bei PKCS12 ist das Schlüsselpasswort üblicherweise identisch mit dem Keystore-Passwort.

## 4. Erste signierte Veröffentlichung

Nach Commit und Push von Version 3.3.0:

```bash
git tag -a v3.3.0 -m "ScaleLauncher 3.3.0"
git push origin v3.3.0
```

Der Workflow `Signed release` erzeugt:

- `ScaleLauncher-3.3.0.apk`
- `ScaleLauncher-3.3.0.apk.sha256`
- eine GitHub Release-Seite für Obtainium und manuelle Downloads

## 5. Einmaliger Wechsel von Debug auf Release

Die bisherige Debug-APK besitzt eine andere Signatur. Deshalb ist genau einmal nötig:

1. Einstellungen und Benutzerprofile notieren.
2. ScaleLauncher-Dienst stoppen.
3. Bisherige ScaleLauncher-App deinstallieren.
4. `ScaleLauncher-3.3.0.apk` aus der GitHub Release installieren.
5. Berechtigungen und Profile neu einrichten.

Danach werden alle weiteren signierten Versionen direkt als Update installiert, sofern
der gleiche Keystore verwendet und `versionCode` erhöht wird.

## 6. Jede weitere Veröffentlichung

Vor jeder Veröffentlichung in `app/build.gradle.kts` beide Werte erhöhen, zum Beispiel:

```kotlin
versionCode = 34
versionName = "3.4.0"
```

Dann committen, pushen und den passenden Tag setzen:

```bash
git tag -a v3.4.0 -m "ScaleLauncher 3.4.0"
git push origin v3.4.0
```

Die Tag-Version muss exakt mit `versionName` übereinstimmen. Der Release-Workflow bricht
ansonsten absichtlich ab.
